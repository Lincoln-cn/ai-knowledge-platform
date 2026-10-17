package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.retrieval.application.MultiHopService;
import cn.lincoln.aiplatform.retrieval.domain.HopDecision;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 多跳判定的<strong>真实接线</strong>（第 14 周 · 0.4.2）：{@code MultiHopService.decide} 走 {@code
 * ChatPort.completeAs}。
 *
 * <h3>为什么与 {@link MultiHopTest} 分开</h3>
 *
 * <p>{@code MultiHopTest} 把 {@code decide} 整个替换掉，测的是"四个停止条件与埋点"；这一个类<strong>不替换它</strong>，
 * 用假端口喂"模型说了什么"，测的是<strong>从端口产出到判定规则</strong>那一段：
 *
 * <ul>
 *   <li>★ <strong>字段缺失 与 {@code false} 必须分得开</strong> —— 读成 {@code false} 会带着一个无意义的查询再检一轮（烧一次调用），
 *       读成 {@code true} 会让多跳永远不触发；两种都不报错，所以只认"确实给了 boolean"。
 *   <li>截断与空白归零这两条规则发生在<strong>领域侧</strong>（转换器不管这些）。
 *   <li>本层的底线（{@code 10 §3.4.6}）：任何失败都变成"判定不可用"，<strong>绝不抛给调用方</strong>。
 * </ul>
 *
 * <p>★ 官方转换器自己的行为（围栏 / 缺字段 / 坏 JSON）另由 {@code modelcatalog.StructuredOutputConverterTest} 钉住 ——
 * 那是"我们删掉自研解析"的依据，单独一个类才说得清。
 */
class MultiHopJudgeTest {

  private static final String QUESTION = "X 公司的违约金上限是多少";
  private static final UUID S1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final List<SegmentSearchPort.Card> HITS =
      List.of(
          new SegmentSearchPort.Card(
              S1,
              "X 公司合同（编号 A-102）",
              "本合同编号 A-102，违约金上限见附件三。",
              "合同",
              "法务库",
              UUID.randomUUID(),
              UUID.randomUUID(),
              ClassificationLevel.INTERNAL));

  private final RecordingChat chat = new RecordingChat();
  private final MultiHopService service = new MultiHopService(chat);

  @Test
  void returnsUsableDecisionWhenModelSaysSufficient() {
    chat.payload = new HopDecision.Payload(true, null);

    MultiHopService.Outcome outcome = decide();

    assertThat(outcome.usable()).isTrue();
    assertThat(outcome.decision().sufficient()).isTrue();
    assertThat(outcome.decision().hasNextQuery()).isFalse();
  }

  @Test
  void passesNextQueryThrough() {
    chat.payload = new HopDecision.Payload(false, "X 公司 违约金 附件三");

    MultiHopService.Outcome outcome = decide();

    assertThat(outcome.decision().hasNextQuery()).isTrue();
    assertThat(outcome.decision().nextQuery()).isEqualTo("X 公司 违约金 附件三");
  }

  /** 超长的下一轮查询 ⇒ 截断：判定的价值在"往哪个方向再问一次"，不该整次作废。 */
  @Test
  void truncatesOverlongNextQuery() {
    chat.payload = new HopDecision.Payload(false, "违".repeat(80));

    MultiHopService.Outcome outcome = decide();

    assertThat(outcome.usable()).isTrue();
    assertThat(outcome.decision().nextQuery()).hasSize(HopDecision.MAX_QUERY_LENGTH);
  }

  /** 空白下一轮查询 = "模型想不出查询了" ⇒ 归 null，调用方据此停（不硬编一条）。 */
  @Test
  void treatsBlankNextQueryAsNoIdea() {
    chat.payload = new HopDecision.Payload(false, "   ");

    MultiHopService.Outcome outcome = decide();

    assertThat(outcome.decision().nextQuery()).isNull();
    assertThat(outcome.decision().hasNextQuery()).isFalse();
  }

  /**
   * ★★ 金丝雀：模型漏掉 {@code sufficient} ⇒ 判定<strong>不可用</strong>。
   *
   * <p>把 {@code HopDecision.from} 里的 null 检查删掉，这条立刻变红 —— 而症状（"多跳带一个无意义的查询再检一轮"
   * 或"多跳永不触发"）在别的断言里都看不出来。这正是"包装类型而不是基本类型"的理由。
   */
  @Test
  void rejectsDecisionWithoutSufficientField() {
    chat.payload = new HopDecision.Payload(null, "再查一次");

    MultiHopService.Outcome outcome = decide();

    assertThat(outcome.usable()).isFalse();
    assertThat(outcome.decision().failure()).contains("sufficient");
  }

  @Test
  void rejectsNullPayload() {
    chat.payload = null;

    MultiHopService.Outcome outcome = decide();

    assertThat(outcome.usable()).isFalse();
  }

  @Test
  void isUnavailableWithoutChatModel() {
    chat.modelId = null;

    MultiHopService.Outcome outcome = decide();

    assertThat(outcome.usable()).isFalse();
    assertThat(chat.calls).as("没有可用模型就不该发起调用").isZero();
  }

  /**
   * ★ 本层的底线承诺（{@code 10 §3.4.6}）：适配器抛（不可达 / 超时 / 输出不符合 schema）⇒ 转成"判定不可用"。 调用方不需要写 try/catch ——
   * "多跳这一层坏掉，绝不能让一次问答失败"。
   */
  @Test
  void turnsAdapterFailureIntoUnavailableDecision() {
    chat.failure =
        new BusinessException(
            ErrorCode.UPSTREAM_UNAVAILABLE, "调用对话模型失败：连接被拒绝", "请确认对话模型服务可用（地址与密钥）");

    MultiHopService.Outcome outcome = assertDoesNotThrow(this::decide);

    assertThat(outcome.usable()).isFalse();
    assertThat(outcome.decision().failure()).contains("连接被拒绝");
  }

  /**
   * ★ 提示词里<strong>不许再出现 JSON 契约</strong>：格式说明由官方转换器追加（{@code 11 §2.7} 的"不要又写一层"）。
   *
   * <p>有人把手写的 {@code {"sufficient": false, ...}} 加回来，这条就红 —— 那正是"两处契约"的开端 （模型按哪一份排版、改 schema
   * 时漏改哪一处，都不会报错）。
   */
  @Test
  void promptCarriesNoJsonContract() {
    chat.payload = new HopDecision.Payload(true, null);

    decide();

    assertThat(chat.prompt).contains(QUESTION).contains("X 公司合同（编号 A-102）");
    assertThat(chat.prompt).doesNotContain("\"sufficient\"").doesNotContain("```");
  }

  private MultiHopService.Outcome decide() {
    return service.decide(QUESTION, HITS, 1_000);
  }

  /** 记录提示词、按预置产出回答的假端口（本仓风格：手写假实现，不引 Mockito）。 */
  private static final class RecordingChat implements ChatPort {

    HopDecision.Payload payload = new HopDecision.Payload(true, null);
    RuntimeException failure;
    UUID modelId = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    String prompt;
    int calls;

    @Override
    public Optional<UUID> pickChatModel() {
      return Optional.ofNullable(modelId);
    }

    @Override
    public String complete(UUID modelId, String prompt, Duration timeout) {
      throw new UnsupportedOperationException("多跳判定走结构化出口");
    }

    @Override
    public <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout) {
      calls++;
      this.prompt = prompt;
      if (failure != null) {
        throw failure;
      }
      return type.cast(payload);
    }
  }
}
