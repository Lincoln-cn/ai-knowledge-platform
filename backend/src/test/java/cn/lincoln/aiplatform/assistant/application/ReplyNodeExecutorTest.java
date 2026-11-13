package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 判据（{@code #326} / {@code #335}）：回复节点真的产出答复、真的报错，而不是"注册成功但永远空转"。 */
@DisplayName("判据 · 回复节点执行器：自定义内容能落地、配错的形态与整格缺失都明确失败")
class ReplyNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

  private final ReplyNodeExecutor executor = new ReplyNodeExecutor();

  private static Map<String, Object> config(Object... kv) {
    Map<String, Object> config = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      config.put((String) kv[i], kv[i + 1]);
    }
    return config;
  }

  private static WorkflowNodeContext context(Map<String, Object> config, String input) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-reply", "REPLY_NODE", input, new FlowState(), config, null);
  }

  @Test
  @DisplayName("自定义内容真的写进共享状态（数据从配置流到 state，不是「接口被调用过」）")
  void writesConfiguredContent() {
    WorkflowNodeContext ctx =
        context(
            config(ReplyNodeExecutor.CONFIG_REPLY_TYPE, "content", "content", "已收到，我们会联系您。"),
            "随便问点什么");

    executor.execute(ctx);

    assertThat(ctx.state().read(ReplyNodeExecutor.REPLY_KEY)).isEqualTo("已收到，我们会联系您。");
  }

  @Test
  @DisplayName("旧画布名 reply_type 仍然认（老草稿不该因改名跑不动）")
  void stillAcceptsLegacyReplyType() {
    WorkflowNodeContext ctx =
        context(config(ReplyNodeExecutor.LEGACY_REPLY_TYPE, "content", "content", "旧草稿的答复"), "q");

    executor.execute(ctx);

    assertThat(ctx.state().read(ReplyNodeExecutor.REPLY_KEY)).isEqualTo("旧草稿的答复");
  }

  @Test
  @DisplayName("内容为空必须失败，不许把空串当答复写进状态")
  void rejectsBlankContent() {
    WorkflowNodeContext ctx = context(config("reply_type", "content", "content", "   "), "q");

    assertThatThrownBy(() -> executor.execute(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("回复内容为空");
    assertThat(ctx.state().read(ReplyNodeExecutor.REPLY_KEY)).isNull();
  }

  @Test
  @DisplayName("按上游字段回复的形态必须明确失败，不许假装跑通")
  void rejectsFieldReferenceForm() {
    WorkflowNodeContext ctx =
        context(config("reply_type", "reference", "fields", "['ai_chat_answer','answer']"), "q");

    assertThatThrownBy(() -> executor.execute(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只支持自定义内容");
  }

  @Test
  @DisplayName("整格配置缺失必须失败，不猜默认值")
  void rejectsMissingConfig() {
    assertThatThrownBy(() -> executor.execute(context(Map.of(), "q")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有配置");
    assertThatThrownBy(() -> executor.execute(context(null, "q")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有配置");
  }

  /**
   * ★ 判据在 {@code #335} 被订正过，理由要留在这里：这条原本写的是"reply_type 缺失必须失败，不按 content 兜底"。
   * 那条口径针对的是<strong>有多个合法值时替用户选一个</strong>——那确实是在猜。但回复形态今天只有一个合法值 （{@code content}），缺失与写 {@code
   * content} 之间没有歧义，所以"按 content 跑"不是猜，是唯一解。
   *
   * <p>真正该失败的是<strong>配错</strong>（{@code reference} 这种还没实现的形态），那条判据保留在 {@link
   * #rejectsFieldReferenceForm()} 里。
   */
  @Test
  @DisplayName("没写回复形态时按唯一合法的 content 跑（缺失 ≠ 配错）")
  void missingReplyTypeMeansContent() {
    WorkflowNodeContext ctx = context(config("content", "有内容且没说形态"), "q");

    executor.execute(ctx);

    assertThat(ctx.state().read(ReplyNodeExecutor.REPLY_KEY)).isEqualTo("有内容且没说形态");
  }
}
