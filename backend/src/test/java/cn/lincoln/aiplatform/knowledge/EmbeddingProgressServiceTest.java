package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.application.EmbeddingProgressService;
import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import cn.lincoln.aiplatform.knowledge.domain.ImportType;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 进度回写（第 8 周 · 0.3.0 · 判据 ②）。
 *
 * <p>★ 这条判据的两个读数都在<strong>库里</strong>：落的是"已完成多少段的事实"（覆盖写，不是 {@code += 1}），
 * 而且它必须<strong>持久化</strong> —— 前端刷新后还能看到进度，靠的就是这一条（"刷新页面不重置"的唯一实现方式）。
 *
 * <p>★★ 2026-10-29（{@code #12} 裁定 C+）：这个类原先还有四条"发布事实事件"的断言（{@code @RecordApplicationEvents} +
 * {@code EmbeddingProgressed}）。取证结论是那条事件<strong>一个消费者都没有</strong>，发布点已删（见 {@link
 * EmbeddingProgressService} 的类注释）⇒ 那四条断言一并删除 —— 它们守的是一个不存在的东西。 ★
 * 将来真有消费者时，要补的是<strong>"它收到了"</strong>的断言（落库/副作用），而不是"有人发了"。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class EmbeddingProgressServiceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /**
   * 组织树的根节点 id（{@code V25__identity_organization.sql} 的种子里是<strong>字面量</strong>，所以确定）。
   *
   * <p>★ 为什么不能随手 {@code UUID.randomUUID()}：领域构造器只做"非空"校验、不查组织树，但 {@code kb_document.org_id}
   * 是<strong>外键 → {@code iam_org}</strong>（{@code V27}）—— 随机 uuid 会以一次 23503
   * 违约炸在插入处，看起来像"进度服务坏了"，实际只是夹具给了个不存在的部门。
   */
  private static final UUID ROOT_ORG_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @Autowired EmbeddingProgressService progress;
  @Autowired DocumentRepository documents;
  @Autowired KnowledgeBaseRepository knowledgeBases;

  // ────────────────────────── 1 · 每批落的是事实（覆盖写） ──────────────────────────

  /** ★ 每批报一次 ⇒ 库里每批都是那一批的当前事实（3，然后 7），不是累加。 */
  @Test
  void everyBatchWritePersistsThatBatchsFactualProgress() {
    Document doc = persistedEmbedding(10);

    progress.report(doc.id(), 3);
    assertThat(reload(doc.id()).processedSegments()).isEqualTo(3);

    progress.report(doc.id(), 7);
    assertThat(reload(doc.id()).processedSegments())
        .as("第二批落的是 7（已完成 7 段的事实），不是 3 + 7")
        .isEqualTo(7);
  }

  /** ★ 同一批重投 ⇒ 进度**不翻倍**（累加写法在这里会变成 6）。 */
  @Test
  void reportingTheSameBatchTwiceDoesNotDoubleTheProgress() {
    Document doc = persistedEmbedding(10);

    progress.report(doc.id(), 3);
    progress.report(doc.id(), 3);

    assertThat(reload(doc.id()).processedSegments()).as("覆盖写：重投同一批仍是 3（累加写法在这里会变成 6）").isEqualTo(3);
  }

  /** ★ 旧事件重投（回退值）⇒ 无害 no-op：进度停在较大的那个值，不倒退、也不报错。 */
  @Test
  void replayingAnOlderBatchIsANoOp() {
    Document doc = persistedEmbedding(10);

    progress.report(doc.id(), 7);
    progress.report(doc.id(), 4);

    assertThat(reload(doc.id()).processedSegments()).as("回退值被领域层判为 no-op ⇒ 库里仍是 7").isEqualTo(7);
  }

  // ────────────────────────── 2 · 落库与拒绝 ──────────────────────────

  /** ★ 进度确实落库（刷新页面还能看到，靠的就是这一条）。 */
  @Test
  void progressIsPersisted() {
    Document doc = persistedEmbedding(10);
    progress.report(doc.id(), 5);

    Document reloaded = reload(doc.id());
    assertThat(reloaded.processedSegments()).isEqualTo(5);
    assertThat(reloaded.segmentCount()).isEqualTo(10);
    assertThat(reloaded.status()).isEqualTo(DocumentStatus.EMBEDDING);
  }

  /** ★ 越界被拒 ⇒ 一行都不许改（假进度比没进度更坏）。 */
  @Test
  void outOfRangeProgressIsRejectedAndPersistsNothing() {
    Document doc = persistedEmbedding(10);

    assertThatThrownBy(() -> progress.report(doc.id(), 11))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(reload(doc.id()).processedSegments()).isZero();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 落库一个「向量化中、10 段」的文档。 */
  private Document persistedEmbedding(int segmentCount) {
    UUID owner = UUID.randomUUID();
    KnowledgeBase kb =
        knowledgeBases.save(
            new KnowledgeBase(
                UUID.randomUUID(),
                "向量进度库-" + UUID.randomUUID().toString().substring(0, 8),
                "制度规范",
                "进度闸门夹具",
                "📘",
                ImportType.LOCAL,
                UUID.randomUUID(),
                ClassificationLevel.SECRET,
                owner,
                null,
                owner));
    Document doc =
        new Document(
            UUID.randomUUID(),
            kb.id(),
            "进度夹具.docx",
            "local",
            "制度",
            // ★ 责任部门改成 org_id（UUID，组织树外键）—— 见 `07 §十一` / Issue #37。
            //   领域构造器直调、不校验存在性，但库里的外键会 —— 必须给一个真的部门 id。
            ROOT_ORG_ID,
            ClassificationLevel.INTERNAL,
            owner);
    doc.startParse(0);
    doc.recordParseSuccess("# 第一章\n\n正文。", 0);
    doc.recordSegmentSuccess(segmentCount, 1000);
    doc.startEmbedding();
    return documents.save(doc);
  }

  private Document reload(UUID id) {
    return documents.findById(id).orElseThrow();
  }
}
