package cn.lincoln.aiplatform.platform.retrieval;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 检索引擎配置的读写入口（配置页与 API 都走这里）。
 *
 * <h3>★ 为什么改一次要打 INFO、还要回读一次</h3>
 *
 * <p>这几格是**全局**的：改 `rerankTopK` 会让所有人的检索变慢、改 `queryUnderstandingEnabled` 会让 所有人的 B1/B3
 * 读数换口径。所以保存后必须落一条日志说清"现在的值是什么"（而不是"改成了什么" —— 只写请求体的话，回落路径与并发覆盖都看不出来），并回读一次确认落盘值。
 *
 * <p>★ 校验在 {@link RetrievalEngineSettings#checked()}，不在本类重复写一遍：范围上限在迁移的 `check` 约束、record
 * 的常量、页面提示三处被引用，**单一真源必须是那个常量的所在地**。
 */
@Service
public class RetrievalEngineSettingsService {

  private static final Logger log = LoggerFactory.getLogger(RetrievalEngineSettingsService.class);

  private final RetrievalEngineSettingsStore store;

  public RetrievalEngineSettingsService(RetrievalEngineSettingsStore store) {
    this.store = store;
  }

  /** 当前生效值。 */
  public RetrievalEngineSettings current() {
    return store.current();
  }

  /**
   * 保存（整表提交）。
   *
   * @param command 表单
   * @param operator 操作人
   * @return 落盘后的值
   * @throws IllegalArgumentException 任一格越界
   */
  @Transactional
  public RetrievalEngineSettings update(UpdateCommand command, UUID operator) {
    RetrievalEngineSettings candidate =
        new RetrievalEngineSettings(
                command.queryUnderstandingEnabled(),
                command.queryUnderstandingRetry(),
                command.rerankEnabled(),
                command.rerankTopK(),
                command.rerankForwardBatch(),
                command.listwiseEnabled(),
                command.listwiseTopK(),
                null)
            .checked();
    RetrievalEngineSettings saved = store.save(candidate, operator);
    log.info(
        "检索引擎配置保存 by {}：qu={} retry={} rerank={} 窗口={} 批={} listwise={}/{}",
        operator,
        saved.queryUnderstandingEnabled(),
        saved.queryUnderstandingRetry(),
        saved.rerankEnabled(),
        saved.rerankTopK(),
        saved.rerankForwardBatch(),
        saved.listwiseEnabled(),
        saved.listwiseTopK());
    return saved;
  }

  /**
   * 更新请求体（配置页一次提交整张表单）。
   *
   * <p>★ 与 {@link RetrievalEngineSettings} 分开的类型：入参没有 {@code updatedAt}（服务端写）。
   * 把两者合并成同一个类型会让"客户端能不能传 updatedAt"变成一个靠约定的问题。
   */
  public record UpdateCommand(
      boolean queryUnderstandingEnabled,
      boolean queryUnderstandingRetry,
      boolean rerankEnabled,
      int rerankTopK,
      int rerankForwardBatch,
      boolean listwiseEnabled,
      int listwiseTopK) {}
}
