package cn.lincoln.aiplatform.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalBudgetProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 查询理解的降级（第 12 周 · 0.4.0，{@code 10 §3.1.3} / {@code §6.3}）—— 判据 ③ 就落在这一类里。
 *
 * <h3>为什么用**假端口**而不是桩服务</h3>
 *
 * <p>这一层要验的是"上游以各种姿势坏掉时，我退到哪一档" —— 那是<strong>分支</strong>，不是集成。 用真桩服务验它，就得构造"超时""返回非
 * JSON""没有对话模型"这些桩服务本身很难稳定复现的故障 （桩有故障注入，但那是另一条路：它验的是协议，不是这几条分支）。
 *
 * <p>★ 而这里最重要的一条是：<strong>这一层永远不抛异常</strong>。要是它把上游的失败抛出去， 那次问答就整个失败了 —— 而 {@code 10 §3.1.3}
 * 的硬纪律是"改写失败绝不能让问答失败"。 所以每个用例都断言"拿回了可用的形状 + 说清了为什么降级"。
 */
class QueryUnderstandingServiceTest {

  private static final String QUESTION = "差旅报销标准是多少";

  private static final String VALID_JSON =
      "{\"subQuestions\":[{\"question\":\"差旅报销标准\",\"queries\":[\"差旅费报销标准\",\"差旅补助标准\"]}]}";

  // ────────────────────────── 正常路径 ──────────────────────────

  @Test
  void usesModelOutputWhenItParses() {
    QueryUnderstandingService.Outcome outcome =
        service(ok(VALID_JSON)).understand(QUESTION, 3, true, 3, 5000);

    assertThat(outcome.degradeLevel()).isZero();
    assertThat(outcome.understanding().subQuestionCount()).isEqualTo(1);
    assertThat(outcome.understanding().totalQueries()).isEqualTo(2);
    assertThat(outcome.parseFailed()).isFalse();
  }

  // ────────────────────────── ★ 判据③：三类失败都退到原始问题单路 ──────────────────────────

  @Test
  void fallsBackToOriginalQuestionWhenUpstreamIsUnreachable() {
    ChatPort broken =
        new ChatPort() {
          @Override
          public Optional<UUID> pickChatModel() {
            return Optional.of(UUID.randomUUID());
          }

          @Override
          public String complete(UUID modelId, String prompt, Duration timeout) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "上游不可达", "请确认模型服务可用");
          }

          @Override
          public <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout) {
            throw new UnsupportedOperationException("查询理解走原文出口，不用结构化出口");
          }
        };

    QueryUnderstandingService.Outcome outcome =
        service(broken).understand(QUESTION, 3, true, 3, 5000);

    assertThat(outcome.degradeLevel()).as("D4：原始问题单路").isEqualTo(4);
    assertThat(outcome.degradeReason()).as("必须说清为什么（否则'这周答案变差了'没人能解释）").contains("不可达");
    assertThat(outcome.understanding().subQuestions().getFirst().queries())
        .containsExactly(QUESTION);
  }

  @Test
  void fallsBackWhenNoChatModelIsRegistered() {
    QueryUnderstandingService.Outcome outcome =
        service(new Fake(Optional.empty(), null)).understand(QUESTION, 3, true, 3, 5000);

    assertThat(outcome.degradeLevel()).isEqualTo(4);
    assertThat(outcome.degradeReason()).contains("没有可用的对话模型");
  }

  @Test
  void retriesOnceOnParseFailureThenDegrades() {
    Fake fake = new Fake(Optional.of(UUID.randomUUID()), "这不是 json");
    QueryUnderstandingService.Outcome outcome =
        fake.service().understand(QUESTION, 3, true, 3, 5000);

    assertThat(outcome.degradeLevel()).isEqualTo(4);
    assertThat(outcome.parseFailed()).as("解析失败要能单独计数（它是'模型能不能胜任这层'的实测数据）").isTrue();
    // ★ 只重试一次：重试是给"模型这次多说了一句话"的，不是给"模型不会做这件事"的
    assertThat(fake.calls.get()).as("解析失败 ⇒ 一共调 2 次（原一次 + 重试一次）").isEqualTo(2);
  }

  @Test
  void retryCanSucceed() {
    Fake fake = new Fake(Optional.of(UUID.randomUUID()), null);
    fake.responses.add("抱歉，我不能……");
    fake.responses.add(VALID_JSON);

    QueryUnderstandingService.Outcome outcome =
        fake.service().understand(QUESTION, 3, true, 3, 5000);

    assertThat(outcome.degradeLevel()).isZero();
    assertThat(fake.calls.get()).isEqualTo(2);
  }

  @Test
  void unreachableIsNotRetried() {
    Fake fake = new Fake(Optional.of(UUID.randomUUID()), null);
    fake.failure = new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "超时", "请稍后重试");

    QueryUnderstandingService.Outcome outcome =
        fake.service().understand(QUESTION, 3, true, 3, 5000);

    assertThat(outcome.degradeLevel()).isEqualTo(4);
    // ★ 超时**不**重试：再试一次只会把首字延迟再烧一遍（10 §3.1.3 把两类失败分成两行）
    assertThat(fake.calls.get()).as("上游失败 ⇒ 只调 1 次").isEqualTo(1);
  }

  // ────────────────────────── 配置与档位 ──────────────────────────

  @Test
  void queryCountZeroMeansDoNotCallTheModel() {
    Fake fake = new Fake(Optional.of(UUID.randomUUID()), VALID_JSON);
    QueryUnderstandingService.Outcome outcome =
        fake.service().understand(QUESTION, 0, true, 3, 5000);

    assertThat(fake.calls.get()).as("0 = 不做这一步，不该白花一次模型调用").isZero();
    // ★ 不是降级：报表里不许把"我们关了这功能"算成"模型总在坏"
    assertThat(outcome.degradeLevel()).isZero();
    assertThat(outcome.degradeReason()).contains("未开启");
    assertThat(outcome.understanding().subQuestions().getFirst().queries())
        .containsExactly(QUESTION);
  }

  @Test
  void singleQueryOutputIsReportedAsRewriteOnly() {
    // 模型只给了一条查询 ⇒ 改写做成了、扩展没做成（10 §6.3 的 D3）
    QueryUnderstandingService.Outcome outcome =
        service(ok("{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"只有一条\"]}]}"))
            .understand(QUESTION, 3, true, 3, 5000);

    assertThat(outcome.degradeLevel()).as("D3：只做成了改写").isEqualTo(3);
    assertThat(outcome.degradeReason()).contains("扩展未生效");
  }

  @Test
  void decompositionOffMergesSubQuestions() {
    QueryUnderstandingService.Outcome outcome =
        service(
                ok(
                    "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"a1\"]},"
                        + "{\"question\":\"B\",\"queries\":[\"b1\"]}]}"))
            .understand(QUESTION, 3, false, 3, 5000);

    assertThat(outcome.understanding().subQuestionCount()).isEqualTo(1);
    assertThat(outcome.understanding().totalQueries()).isEqualTo(2);
    assertThat(outcome.degradeReason()).contains("分解已关闭");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static QueryUnderstandingService service(ChatPort port) {
    return new QueryUnderstandingService(
        port, new RetrievalBudgetProperties(5000, 5000, 0.6, true, true));
  }

  private static ChatPort ok(String response) {
    return new Fake(Optional.of(UUID.randomUUID()), response);
  }

  /** 一个可控的假端口：能按次序回不同内容、能抛异常、并数调用次数。 */
  private static final class Fake implements ChatPort {

    private final Optional<UUID> model;
    private final List<String> responses = new java.util.ArrayList<>();
    private BusinessException failure;
    private final AtomicInteger calls = new AtomicInteger();

    Fake(Optional<UUID> model, String response) {
      this.model = model;
      if (response != null) {
        this.responses.add(response);
      }
    }

    QueryUnderstandingService service() {
      return new QueryUnderstandingService(
          this, new RetrievalBudgetProperties(5000, 5000, 0.6, true, true));
    }

    @Override
    public Optional<UUID> pickChatModel() {
      return model;
    }

    @Override
    public String complete(UUID modelId, String prompt, Duration timeout) {
      calls.incrementAndGet();
      if (failure != null) {
        throw failure;
      }
      // 用完一条就重复最后一条（重试时通常拿到的是同一种坏输出）
      return responses.size() == 1 ? responses.getFirst() : responses.removeFirst();
    }

    @Override
    public <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout) {
      throw new UnsupportedOperationException("查询理解走原文出口（按换行分隔），不用结构化出口");
    }
  }
}
