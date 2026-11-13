package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.ApplicationInvokePort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 应用调用节点：把另一个编排应用的<strong>已发布版本</strong>当成这一步的"函数"跑一次。
 *
 * <p>★ 只读已发布版本（{@link ApplicationInvokePort} 的契约）：草稿允许不合规，把一张没通过发布校验的图 接进别人的流程，等于让"这条链的出口是谁"随时会变。
 *
 * <p>★ 输入默认取节点输入（{@link WorkflowNodeContext#input()}），不读流程变量的约定名：
 * 应用调用的语义是"把这段文本交给那个应用"，而"哪段文本"应该由连在它前面的节点决定 —— 读一个固定变量名会让"想喂另一段文本"必须先在中间插一个改写节点。
 *
 * <p>★ 输出只有一份 {@code app_output}：子流程内部的中间变量不进父流程（两边的图各自演进， 把子流程的键名暴露给父流程，等于让父流程跟着子流程的改版一起坏）。
 */
@Component
public class ApplicationNodeExecutor implements NodeExecutor {

  /** 流程变量：被调应用的最终输出。 */
  public static final String RESULT_KEY = WorkflowNodeKeys.Output.APP_OUTPUT;

  /** 配置：要调用的应用。与执行准入读的是同一个键（{@code NodeConfigReader}）。 */
  public static final String CONFIG_APP_ID = WorkflowNodeKeys.Config.APPLICATION_ID;

  /** 配置：版本号或版本主键，留空取最新已发布版本。 */
  public static final String CONFIG_VERSION = WorkflowNodeKeys.Config.VERSION;

  private final ObjectProvider<ApplicationInvokePort> invocations;

  public ApplicationNodeExecutor(ObjectProvider<ApplicationInvokePort> invocations) {
    this.invocations = invocations;
  }

  @Override
  public String type() {
    return "APPLICATION_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    String rawAppId = text(context.nodeConfig().get(CONFIG_APP_ID));
    if (rawAppId == null) {
      throw new IllegalStateException("应用调用节点没有选应用：在节点配置里给 applicationId");
    }
    UUID appId;
    try {
      appId = UUID.fromString(rawAppId);
    } catch (IllegalArgumentException notUuid) {
      throw new IllegalStateException("应用调用节点的 applicationId 不是合法 UUID：" + rawAppId);
    }

    ApplicationInvokePort port = invocations.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("应用调用能力还没装配：没有 ApplicationInvokePort 的实现");
    }

    String versionKey = text(context.nodeConfig().get(CONFIG_VERSION));
    Optional<ApplicationInvokePort.Invocation> result;
    try {
      result = port.invoke(appId, versionKey, context.input());
    } catch (IllegalStateException broken) {
      // 端口把"配置错了"（递归超限 / 版本编译不过）当异常抛，这里补一句"是哪一步" ——
      // 父流程的错误记录里只有节点 id，看不出被调应用是谁。
      throw new IllegalStateException(
          "应用调用节点跑不通（应用 "
              + appId
              + (versionKey == null ? "" : "，版本 " + versionKey)
              + "）："
              + broken.getMessage(),
          broken);
    }
    if (result == null) {
      throw new IllegalStateException("应用调用端口返回了 null，契约要求 Optional.empty()");
    }
    if (result.isEmpty()) {
      // ★ 三种原因合成一句话：应用不存在 / 我看不见它 / 它还没有发布版本 —— 端口刻意不区分
      //   （与 AppOpeningAdapter 同一条口径：把"看不见"说成"不存在"才是对的）。
      throw new IllegalStateException("被调应用不存在、当前账号看不到，或者还没有发布版本：" + appId);
    }

    context.state().write(RESULT_KEY, result.get().output(), KeyUpdate.REPLACE);
  }

  private static String text(Object raw) {
    if (raw == null) {
      return null;
    }
    String value = String.valueOf(raw).strip();
    return value.isEmpty() ? null : value;
  }
}
