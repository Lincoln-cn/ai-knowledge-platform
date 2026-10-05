package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.application.EmbeddingProgressService;
import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import cn.lincoln.aiplatform.knowledge.domain.EmbeddingProgressed;
import cn.lincoln.aiplatform.knowledge.domain.ImportType;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 进度回写 + 事件（第 8 周 · 0.3.0 · 判据 ②④）。
 *
 * <p>★ 判据 ④ 的原话是「{@code EmbeddingProgressed} <strong>按事实发布</strong>」—— 两处都要盯：
 *
 * <ol>
 *   <li><strong>发布了</strong>：用 {@code @RecordApplicationEvents} 抓真实事件（不是"看日志里像发了"）
 *   <li><strong>带的是事实</strong>：事件里的数字必须等于<strong>落库之后</strong>的当前进度， 而不是调用方传进来的数 ——
 *       一次重投的旧事件不能把进度"讲回去"
 * </ol>
 *
 * <p>★ 进度必须<strong>落库</strong>：前端刷新后还能看到进度，靠的就是它（"刷新页面不重置"的唯一实现方式）。 所以下面每条断言都同时看「返回的对象」与「重新查库的结果」。
 *
 * <p>⚠️ 已知边界：事件目前<strong>没有消费者</strong>（本批只发不收），因此它不会落进 Modulith 的 {@code event_publication} 表 ——
 * 那张表是按"每个监听者一条"记录的。第一个真正的事件消费者 （进度观测/跨模块联动）落地时，这张表才有行；届时用同一套断言补一条"落表"的测试。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
@RecordApplicationEvents
class EmbeddingProgressServiceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired EmbeddingProgressService progress;
  @Autowired DocumentRepository documents;
  @Autowired KnowledgeBaseRepository knowledgeBases;
  @Autowired ApplicationEvents events;

  // ────────────────────────── 1 · 按事实发布 ──────────────────────────

  /** ★ 每批报一次 ⇒ 每次都发一个带真实数字的事件。 */
  @Test
  void 每批报进度都发布带真实数字的事件() {
    Document doc = persistedEmbedding(10);

    progress.report(doc.id(), 3);
    progress.report(doc.id(), 7);

    List<EmbeddingProgressed> published = events.stream(EmbeddingProgressed.class).toList();
    assertThat(published).hasSize(2);
    assertThat(published.get(0).documentId()).isEqualTo(doc.id());
    assertThat(published.get(0).processedSegments()).isEqualTo(3);
    assertThat(published.get(0).segmentCount()).isEqualTo(10);
    assertThat(published.get(0).completed()).isFalse();
    assertThat(published.get(1).processedSegments()).isEqualTo(7);
  }

  /** ★ 同一批重投 ⇒ 进度**不翻倍**，事件带的仍是当前事实（3，而不是 6）。 */
  @Test
  void 重投同一批进度不翻倍且事件仍是事实() {
    Document doc = persistedEmbedding(10);

    progress.report(doc.id(), 3);
    progress.report(doc.id(), 3);

    assertThat(reload(doc.id()).processedSegments()).as("覆盖写：重投同一批仍是 3（累加写法在这里会变成 6）").isEqualTo(3);
    List<EmbeddingProgressed> published = events.stream(EmbeddingProgressed.class).toList();
    assertThat(published).hasSize(2);
    assertThat(published.get(1).processedSegments())
        .as("第二个事件带的必须是落库之后的当前事实，而不是「再来 3 段」")
        .isEqualTo(3);
  }

  /** ★ 旧事件重投（回退值）⇒ 无害 no-op：进度不变，事件仍报当前事实（不许出现"进度倒退"的假事实）。 */
  @Test
  void 旧事件重投不改变进度也不报错() {
    Document doc = persistedEmbedding(10);

    progress.report(doc.id(), 7);
    progress.report(doc.id(), 4);

    assertThat(reload(doc.id()).processedSegments()).isEqualTo(7);
    assertThat(events.stream(EmbeddingProgressed.class).toList().get(1).processedSegments())
        .isEqualTo(7);
  }

  /** ★ 进度确实落库（刷新页面还能看到，靠的就是这一条）。 */
  @Test
  void 进度必须落库() {
    Document doc = persistedEmbedding(10);
    progress.report(doc.id(), 5);

    Document reloaded = reload(doc.id());
    assertThat(reloaded.processedSegments()).isEqualTo(5);
    assertThat(reloaded.segmentCount()).isEqualTo(10);
    assertThat(reloaded.status()).isEqualTo(DocumentStatus.EMBEDDING);
  }

  // ────────────────────────── 2 · 失败的调用不该发事件 ──────────────────────────

  /** ★ 越界被拒时**一个事件都不许发** —— 假事实比没事实更坏。 */
  @Test
  void 越界被拒时不发任何事件() {
    Document doc = persistedEmbedding(10);

    assertThatThrownBy(() -> progress.report(doc.id(), 11))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(events.stream(EmbeddingProgressed.class)).isEmpty();
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
            "采购部",
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
