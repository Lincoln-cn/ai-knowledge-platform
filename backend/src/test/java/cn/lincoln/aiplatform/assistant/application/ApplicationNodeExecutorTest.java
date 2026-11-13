package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.ApplicationInvokePort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 判据（{@code #333} 的 {@code APPLICATION_NODE}）：应用调用节点真的把节点输入交给被调应用、
 * 真的把它的输出写进流程变量，接不上/看不见/没发布时**说清楚是哪一种**。
 *
 * <p>★ 为什么"三种失败合成一句话"要单独一条测：端口刻意不区分「不存在 / 看不见 / 没发布」， 而执行器的文案必须把这三者都写出来 —— 只写"应用不存在"会让用户去查应用是不是被删了，
 * 只写"没发布"会让看不见的人一直点发布。
 */
@DisplayName("判据 · 应用调用节点：把输入交给被调应用、把输出写进流程变量")
class ApplicationNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final UUID APP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  /** 记下入参、回放预设结果的替身端口。 */
  private static final class RecordingPort implements ApplicationInvokePort {
    UUID appId;
    String versionKey;
    String input;
    int calls;

    /** 端口返回什么；null 表示"返回一个空输出"，用于造"端口违约"时要显式设。 */
    Invocation answer;

    boolean empty;
    boolean contractViolation;

    @Override
    public Optional<Invocation> invoke(UUID appId, String versionKey, String input) {
      this.appId = appId;
      this.versionKey = versionKey;
      this.input = input;
      this.calls++;
      if (contractViolation) {
        return null;
      }
      if (empty) {
        return Optional.empty();
      }
      return Optional.of(answer == null ? new Invocation("子应用的结论") : answer);
    }
  }

  @Test
  @DisplayName("把节点输入交给被调应用，把它的输出写进 app_output")
  void writesInvocationOutput() {
    RecordingPort port = new RecordingPort();
    FlowState state = new FlowState();
    new ApplicationNodeExecutor(providerOf(port))
        .execute(context(state, Map.of("applicationId", APP_ID.toString())));

    assertThat(port.appId).isEqualTo(APP_ID);
    assertThat(port.input).isEqualTo("合同违约金怎么算？");
    assertThat(state.read(ApplicationNodeExecutor.RESULT_KEY)).isEqualTo("子应用的结论");
  }

  @Test
  @DisplayName("版本留空取最新，填了就原样传下去")
  void passesVersionThrough() {
    RecordingPort port = new RecordingPort();
    new ApplicationNodeExecutor(providerOf(port))
        .execute(context(new FlowState(), Map.of("applicationId", APP_ID.toString())));
    assertThat(port.versionKey).isNull();

    RecordingPort withVersion = new RecordingPort();
    new ApplicationNodeExecutor(providerOf(withVersion))
        .execute(
            context(new FlowState(), Map.of("applicationId", APP_ID.toString(), "version", " 3 ")));
    assertThat(withVersion.versionKey).isEqualTo("3");
  }

  @Test
  @DisplayName("没选应用：明确说要在配置里给 applicationId")
  void rejectsMissingAppId() {
    FlowState state = new FlowState();
    assertThatThrownBy(
            () ->
                new ApplicationNodeExecutor(providerOf(new RecordingPort()))
                    .execute(context(state, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("applicationId");
    assertThat(state.asMap()).isEmpty();
  }

  @Test
  @DisplayName("applicationId 不是 UUID：报出来值本身，别让人去猜")
  void rejectsMalformedAppId() {
    assertThatThrownBy(
            () ->
                new ApplicationNodeExecutor(providerOf(new RecordingPort()))
                    .execute(context(new FlowState(), Map.of("applicationId", "不是-uuid"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是-uuid");
  }

  @Test
  @DisplayName("端口没有实现：说清是没装配，不是配置错了")
  void rejectsMissingPort() {
    assertThatThrownBy(
            () ->
                new ApplicationNodeExecutor(emptyProvider())
                    .execute(context(new FlowState(), Map.of("applicationId", APP_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ApplicationInvokePort");
  }

  @Test
  @DisplayName("空结果：文案同时提到「不存在 / 看不到 / 没发布」三种可能")
  void rejectsInvisibleOrUnpublished() {
    RecordingPort port = new RecordingPort();
    port.empty = true;
    assertThatThrownBy(
            () ->
                new ApplicationNodeExecutor(providerOf(port))
                    .execute(context(new FlowState(), Map.of("applicationId", APP_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不存在")
        .hasMessageContaining("看不到")
        .hasMessageContaining("还没有发布版本")
        .hasMessageContaining(APP_ID.toString());
  }

  @Test
  @DisplayName("端口违约返回 null：报出契约要求，而不是 NPE")
  void rejectsContractViolation() {
    RecordingPort port = new RecordingPort();
    port.contractViolation = true;
    assertThatThrownBy(
            () ->
                new ApplicationNodeExecutor(providerOf(port))
                    .execute(context(new FlowState(), Map.of("applicationId", APP_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Optional.empty()");
  }

  @Test
  @DisplayName("端口抛异常：包一层，把「哪个应用」带进错误记录")
  void wrapsPortFailureWithAppId() {
    ApplicationInvokePort broken =
        (appId, versionKey, input) -> {
          throw new IllegalStateException("应用调用已经嵌套 3 层");
        };
    assertThatThrownBy(
            () ->
                new ApplicationNodeExecutor(providerOf(broken))
                    .execute(context(new FlowState(), Map.of("applicationId", APP_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("应用调用节点跑不通")
        .hasMessageContaining(APP_ID.toString())
        .hasMessageContaining("嵌套 3 层");
  }

  private static WorkflowNodeContext context(FlowState state, Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-app", "APPLICATION_NODE", "合同违约金怎么算？", state, config);
  }

  private static ObjectProvider<ApplicationInvokePort> providerOf(ApplicationInvokePort port) {
    return new ObjectProvider<>() {
      @Override
      public ApplicationInvokePort getObject(Object... args) {
        return port;
      }

      @Override
      public ApplicationInvokePort getIfAvailable() {
        return port;
      }

      @Override
      public ApplicationInvokePort getIfUnique() {
        return port;
      }
    };
  }

  private static ObjectProvider<ApplicationInvokePort> emptyProvider() {
    return new ObjectProvider<>() {
      @Override
      public ApplicationInvokePort getObject(Object... args) {
        throw new IllegalStateException("没有 bean");
      }

      @Override
      public ApplicationInvokePort getIfAvailable() {
        return null;
      }

      @Override
      public ApplicationInvokePort getIfUnique() {
        return null;
      }
    };
  }
}
