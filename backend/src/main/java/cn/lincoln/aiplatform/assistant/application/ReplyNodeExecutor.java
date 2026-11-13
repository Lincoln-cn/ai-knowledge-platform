package cn.lincoln.aiplatform.assistant.application;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code REPLY_NODE} 的执行器（{@code #326} 补的第三个真执行器）。
 *
 * <p>★ 职责：把节点配置里的回复内容写进共享 {@link FlowState} 的 {@value #REPLY_KEY} 键，作为这一轮的最终答复。
 *
 * <p>★ <strong>为什么零依赖</strong>：与 {@link StartNodeExecutor} 同因 —— {@code assistant} 的 {@code
 * allowedDependencies} 不含 {@code conversation}／{@code retrieval}。回复节点只读自己那一格配置，不需要跨模块服务，所以它可以做真的。
 *
 * <p>★ <strong>只认 {@code replyType = content}</strong>：另一种形态（按上游节点产出的字段回复，即 {@code fields}
 * 非空）的语义是"把上游某个 节点的输出原样透出"，而上游那个节点是 {@code AI_CHAT_NODE} —— 它今天还没有执行器。这里**宁可明确失败**，也不要做成"拿不到就回空串"：
 * 后者会让一条配置错的流程看起来跑通了，而用户收到的是空白答复。
 *
 * <p>★ <strong>内容为空一律失败</strong>：与上面同理。"回复节点什么都没配"和"回复节点回复了空内容"是两种错误，不能合并成一个读数。
 */
@Component
public class ReplyNodeExecutor implements NodeExecutor {

  /** 本轮答复在 {@link FlowState} 里的键名。下游（记录 / 展示）读它，不要再各自定义一份。 */
  public static final String REPLY_KEY = "reply";

  /** 回复形态的配置键：与前端契约 {@code ReplyNodeConfig.replyType} 同名（{@code #335}）。 */
  static final String CONFIG_REPLY_TYPE = "replyType";

  /** 旧画布形状的键，只作回退。 */
  static final String LEGACY_REPLY_TYPE = "reply_type";

  /** 只支持自定义内容这一种形态；另一种要等 {@code AI_CHAT_NODE} 落地。 */
  private static final String REPLY_TYPE_CONTENT = "content";

  @Override
  public String type() {
    return "REPLY_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext ctx) {
    Map<String, Object> config = ctx.nodeConfig();
    if (config == null || config.isEmpty()) {
      throw new IllegalStateException("回复节点没有配置：这条流程的回复内容还没填");
    }
    String replyType = text(config.get(CONFIG_REPLY_TYPE));
    if (replyType == null || replyType.isBlank()) {
      replyType = text(config.get(LEGACY_REPLY_TYPE));
    }
    if (replyType == null || replyType.isBlank()) {
      // ★ 缺失按 content（{@code #335}）：契约里这一项是可选的，且当前只有一种形态；
      //   早先这里会直接抛，于是「没写 replyType 的图」与「配错的图」得到同一个错，而前者的正解就是走 content。
      replyType = REPLY_TYPE_CONTENT;
    }
    if (!REPLY_TYPE_CONTENT.equals(replyType)) {
      throw new IllegalStateException(
          "回复节点当前只支持自定义内容（replyType=content），按上游字段回复要等 AI 对话节点落地：replyType=" + replyType);
    }
    String content = text(config.get("content"));
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("回复节点的回复内容为空");
    }
    ctx.state().write(REPLY_KEY, content, KeyUpdate.REPLACE);
  }

  /** 配置值是 {@code Object}（来自画布 JSON），统一收敛成 {@code String}；非字符串一律当没配。 */
  private static String text(Object raw) {
    return raw instanceof String s ? s : null;
  }
}
