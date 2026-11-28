package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.application.QaPairService;
import cn.lincoln.aiplatform.knowledge.application.QaPairService.ImportPair;
import cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.QaRecallPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 问答对召回（检索的第五路）—— 真 PG，纯 SQL 夹具。
 *
 * <h3>★ 断言全部打在"会不会静默出错"上</h3>
 *
 * <p>召回这条路错了不会报错，只会<strong>少答 / 答错 / 越权</strong>，所以五条断言各自钉一个方向：
 *
 * <ul>
 *   <li>相近问法<strong>能</strong>召回（否则这条路等于没接 —— 而"没接"与"接错了"在日志里一模一样）；
 *   <li>不相干问法<strong>不能</strong>召回（否则每问一句都能捞到一堆问答对，直答型会开始乱答）；
 *   <li>密级高的<strong>不</strong>召回（静默越权，{@code 13 §6}）；
 *   <li>别的库的<strong>不</strong>召回（域泄漏）；
 *   <li>{@code direct_answer} 标志<strong>原样带出</strong>（丢了它，直答型就退化成召回型 —— 用户拿到一段被模型改写过的「标准答案」）。
 * </ul>
 *
 * <h3>★ 为什么不用 HTTP / 不走 RetrievalService</h3>
 *
 * <p>这一批只做端口本身；{@code RetrievalService} 与 {@code ChatService} 接它是后面两步。把断言打在端口上，
 * 红的范围就精确等于"实现坏了"，而不是"某条装配没接上"。★ 同理<strong>不建向量、不要桩服务</strong>：这条路 完全在库里（{@code chinese_zh} 分词 +
 * GIN），于是它不会因为桩不可达而整类跳过。
 */
@SpringBootTest
@Testcontainers
class QaRecallTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID CURRENT_USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

  /** 标定问法。★ 与下面的召回问句刻意差一个词，用来验证"相近但不是同一句"。 */
  private static final String SEEDED_QUESTION = "财务资产部负责哪些预算事项";

  @Autowired JdbcTemplate jdbc;

  @Autowired QaPairService qaPairs;

  @Autowired QaRecallPort qaRecall;

  @Test
  void similarQuestionIsRecalled() {
    UUID kb = seedKb(ClassificationLevel.INTERNAL);
    UUID pairId = importQaPair(kb, SEEDED_QUESTION, "负责年度预算编制、执行跟踪与调整。");

    List<QaRecallPort.Match> hits =
        asClearance(ClassificationLevel.INTERNAL, () -> recall(List.of(kb), "财务资产部负责哪些预算"));

    assertThat(hits).as("★★ 相近问法必须能召回 —— 这一条红了说明第五路根本没接上（而「没接上」在日志里与「接错了」一模一样）").isNotEmpty();
    assertThat(hits.getFirst().id()).isEqualTo(pairId);
    assertThat(hits.getFirst().score()).as("同一件事的两种说法，F1 应当明显高于阈值").isGreaterThanOrEqualTo(0.6);
    assertThat(hits.getFirst().segmentId()).as("★ 手工标定的问答对没有出处 ⇒ 它进不了 RRF，只能进「答案参考」").isNull();
  }

  @Test
  void unrelatedQuestionYieldsNothing() {
    UUID kb = seedKb(ClassificationLevel.INTERNAL);
    importQaPair(kb, SEEDED_QUESTION, "负责年度预算编制、执行跟踪与调整。");

    assertThat(asClearance(ClassificationLevel.INTERNAL, () -> recall(List.of(kb), "年度体检安排在什么时候")))
        .as("★ 不相干的问句一条都不许召回 —— 否则直答型会对任何问题都给出一段「标准答案」")
        .isEmpty();
  }

  @Test
  void aboveClearanceIsNotRecalled() {
    UUID kb = seedKb(ClassificationLevel.CONFIDENTIAL);
    importQaPair(kb, SEEDED_QUESTION, "机密：预算科目附表中的调整口径。", ClassificationLevel.SECRET, false);

    assertThat(
            asClearance(ClassificationLevel.INTERNAL, () -> recall(List.of(kb), SEEDED_QUESTION)))
        .as("★★ 密级高于调用方上限的问答对不许被召回 —— 这是一次静默越权，且它长得与「没有命中」完全一样")
        .isEmpty();

    assertThat(asClearance(ClassificationLevel.SECRET, () -> recall(List.of(kb), SEEDED_QUESTION)))
        .as("★ 反证：同样的问句、同样的库，密级够的人必须能召回 —— 否则上面那条只是「什么都没接上」")
        .isNotEmpty();
  }

  @Test
  void otherKnowledgeBaseIsNotRecalled() {
    UUID kbA = seedKb(ClassificationLevel.INTERNAL);
    UUID kbB = seedKb(ClassificationLevel.INTERNAL);
    importQaPair(kbB, SEEDED_QUESTION, "B 库的答案。");

    assertThat(
            asClearance(ClassificationLevel.INTERNAL, () -> recall(List.of(kbA), SEEDED_QUESTION)))
        .as("★ 只传 A 库时不许召回 B 库的问答对（域泄漏；问答对只挂在库上，没有会话域那一支）")
        .isEmpty();
    assertThat(asClearance(ClassificationLevel.INTERNAL, () -> recall(List.of(), SEEDED_QUESTION)))
        .as("★ 空域不是「全部可见」 —— 那是 fail-open")
        .isEmpty();
  }

  @Test
  void directAnswerFlagIsCarriedThrough() {
    UUID kb = seedKb(ClassificationLevel.INTERNAL);
    importQaPair(kb, SEEDED_QUESTION, "负责年度预算编制、执行跟踪与调整。", ClassificationLevel.INTERNAL, true);

    List<QaRecallPort.Match> hits =
        asClearance(ClassificationLevel.INTERNAL, () -> recall(List.of(kb), SEEDED_QUESTION));

    assertThat(hits).isNotEmpty();
    assertThat(hits.getFirst().directAnswer())
        .as("★ 标志丢了 ⇒ 直答型退化成召回型：用户拿到一段被模型改写过的「标准答案」，而且没人会发现")
        .isTrue();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private List<QaRecallPort.Match> recall(List<UUID> kbIds, String question) {
    return qaRecall.recall(new QaRecallPort.Query(question, kbIds, 0.6, 10));
  }

  private <T> T asClearance(ClassificationLevel level, java.util.function.Supplier<T> body) {
    return SecurityContexts.asClearance(level, body::get);
  }

  private UUID importQaPair(UUID kb, String question, String answer) {
    return importQaPair(kb, question, answer, ClassificationLevel.INTERNAL, false);
  }

  private UUID importQaPair(
      UUID kb, String question, String answer, ClassificationLevel classification, boolean direct) {
    String id =
        SecurityContexts.asUser(
                CURRENT_USER,
                ClassificationLevel.CONFIDENTIAL,
                () ->
                    qaPairs.importPairs(
                        kb,
                        List.of(
                            new ImportPair(
                                question,
                                answer,
                                null,
                                direct,
                                classification,
                                QaPairOrigin.MANUAL,
                                null)),
                        CURRENT_USER))
            .getFirst()
            .id();
    return UUID.fromString(id);
  }

  private UUID seedKb(ClassificationLevel ceiling) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, ?, '制度规范', 'LOCAL', ?, ?, ?, 'PUBLIC', now(), now())",
        id,
        "问答对召回夹具库-" + id.toString().substring(0, 8),
        UUID.randomUUID(),
        ceiling.name(),
        CURRENT_USER);
    return id;
  }
}
