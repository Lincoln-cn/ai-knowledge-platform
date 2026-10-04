package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrFastScanner;
import cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 解析执行器（第 9 周 · 0.3.1）—— 「建档即解析」从**同步**改成**异步流水线**。
 *
 * <h3>★ 为什么必须异步（判据 ⑤「OCR 异步，不阻塞其它文档」）</h3>
 *
 * <p>实测：3 页扫描件全量 OCR 约 **11 秒**（每页约 3.6 秒），混合型 PDF 约 9 秒。同步跑意味着 上传请求挂在那里十几秒到几分钟 ——
 * 网关超时、用户以为卡死，而且**进度根本看不见**（0/M 与 M/M 同时返回）。 后台跑 + 逐页回写进度，才让「已识别 3/10 页」这件事成立（{@code 12 §11.8}）。
 *
 * <h3>★ 一次解析分三步，边界就按"谁需要事务"划</h3>
 *
 * <ol>
 *   <li><b>快扫</b>（{@link OcrFastScanner}，约 0.3 秒）：关掉 OCR 读每页字符数 ⇒ 得到进度**分母**
 *   <li><b>全量解析</b>（可能分钟级）：打开 OCR，带逐页回调 ⇒ 每页做完就回写一次进度 <br>
 *       ★ 这一步<strong>不在事务里</strong>：进度必须是**提交后**的事实，否则轮询的前端看不到任何中间值
 *   <li><b>落库</b>（{@link DocumentService#finishParse}，一个短事务）：解析结果 + 分块，原子完成
 * </ol>
 *
 * <p>★ 启动时机由 {@link TransactionalEventListener} 的 {@code AFTER_COMMIT} 保证 —— 见 {@link
 * DocumentUploaded}（那是 issue #18 的教训）。
 */
@Service
public class ParseRunner {

  private static final Logger log = LoggerFactory.getLogger(ParseRunner.class);

  /** 虚拟线程（{@code ADR-01}：IO/CPU 混合密集，且要"一篇慢不挡别的"）。 */
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  private final TikaMarkdownParser parser;
  private final OcrFastScanner scanner;
  private final ParseProgressService progress;
  private final DocumentService documents;

  public ParseRunner(
      TikaMarkdownParser parser,
      OcrFastScanner scanner,
      ParseProgressService progress,
      DocumentService documents) {
    this.parser = parser;
    this.scanner = scanner;
    this.progress = progress;
    this.documents = documents;
  }

  /**
   * 收到上传事件 ⇒ 提交一次解析。
   *
   * <p>★ {@code AFTER_COMMIT}：上传那个事务提交之后才提交任务 —— 见 {@link DocumentUploaded}。
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onUploaded(DocumentUploaded event) {
    executor.submit(
        () -> run(event.documentId(), event.content(), event.filename(), event.userId()));
  }

  /**
   * 跑一轮解析（{@link #onUploaded} 在后台调它；测试与排障也直接调它）。
   *
   * @param documentId 文档 id
   * @param content 文件字节
   * @param filename 文件名
   * @param userId 建档人
   */
  public void run(UUID documentId, byte[] content, String filename, UUID userId) {
    try {
      // ── ① 分母：关掉 OCR 的快扫（约 0.3 秒 vs 全量的 11 秒）──
      OcrFastScanner.OcrPageInventory inventory = scanner.scan(content, filename);
      // 状态进「分段中」并对外可见 0/M —— 这一步之后前端就能看到进度行了
      progress.start(documentId, inventory.ocrPages());

      // ── ② 全量解析 + 逐页回写（OCR 在这里，分钟级）──
      // ★ 只对"走了 OCR 的页"报进度：分母就是"需要 OCR 的页数"，两者同口径。
      //   文本型的页不报 —— 否则混合型 PDF 会出现「已识别 3/3 但只有 2 页走过 OCR」这种对不上的数。
      AtomicInteger donePages = new AtomicInteger();
      ParseResult parsed =
          parser.parse(
              content,
              filename,
              (pageIndex, ocrPage) -> {
                if (ocrPage) {
                  progress.reportOcrPage(documentId, donePages.incrementAndGet());
                }
              });

      // ── ③ 两处口径核对：快扫（分母）vs Tika（事实）──
      //   实测三个夹具完全一致（0/2/3）；不一致说明两处口径漂了，要**可见**而不是静默按一个走。
      if (parsed.success() && inventory.ocrPages() != parsed.ocrPageCount()) {
        log.warn(
            "OCR 页数口径不一致：快扫 {} 页 vs Tika {} 页（文档 {}，{}）—— 进度分母取自快扫，最终以 Tika 为准",
            inventory.ocrPages(),
            parsed.ocrPageCount(),
            documentId,
            filename);
      }

      // ── ④ 落库（一个事务：解析结果 + 分块）──
      documents.finishParse(documentId, parsed, userId);
      if (parsed.success()) {
        log.info(
            "解析完成：文档 {}（{}）共 {} 页，其中 {} 页走了 OCR",
            documentId,
            filename,
            parsed.pageCount(),
            parsed.ocrPageCount());
      }
    } catch (RuntimeException e) {
      // ★ 任何异常都要留下可读原因 —— 不许让文档停在「分段中」等一个永远不会来的结果
      log.warn("解析失败：文档 {}（{}）—— {}", documentId, filename, e.toString());
      documents.failParse(documentId, e);
    }
  }
}
