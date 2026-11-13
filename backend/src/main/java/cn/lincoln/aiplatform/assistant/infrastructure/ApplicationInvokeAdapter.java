package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.application.FlowState;
import cn.lincoln.aiplatform.assistant.application.LinearWorkflowExecutor;
import cn.lincoln.aiplatform.assistant.application.NodeTypeCatalog;
import cn.lincoln.aiplatform.assistant.application.WorkflowCompiler;
import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersionRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.assistant.ApplicationInvokePort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ApplicationInvokePort} 的实现 —— 端口在 {@code platform}、实现在提供方模块。
 *
 * <p>★ 权限走 {@link AccessScopePort#canSee}，与 {@code AppOpeningAdapter} 同一处判定： 调用方看不见的应用，
 * 这里返回的是<strong>空</strong>（"不存在"与"看不见"不可分）。少了这一步，"把别人的应用挂到我的流程里当函数用" 就成了一条绕过可见性的数据通道 —— 而且它读起来完全正常。
 *
 * <p>★ 递归上限用 {@link ThreadLocal} 记层级，不用"图里查一圈"：A 调 B、B 又调 A 是两步循环， 但三层之后每加一层都只是在拖时间。上限 {@value
 * #MAX_DEPTH} 层，超了抛 {@link IllegalStateException} 而不是返回空 ——
 * 这是<strong>配置错误</strong>（多半是循环引用），静默返回空会让父流程"看起来跑完了"。
 *
 * <p>★ 子流程的最终输出与「试用」同一口径（{@code WorkflowDebugService.debug} 也是先读 {@code reply}）：
 * 两边口径不同的话，同一次执行在试用里看到 A、被别的应用调用时拿到 B。
 */
@Component
public class ApplicationInvokeAdapter implements ApplicationInvokePort {

  /** 允许的最深调用层级。三层足够（父 → 子 → 孙），再深基本是环。 */
  private static final int MAX_DEPTH = 3;

  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  private static final String REPLY_KEY = "reply";
  private static final String ANSWER_KEY = "answer";

  private final AssistantRepository assistants;
  private final AccessScopePort accessScope;
  private final WorkflowVersionRepository versions;
  private final NodeTypeCatalog catalog;
  private final LinearWorkflowExecutor executor;
  private final ObjectMapper json;

  public ApplicationInvokeAdapter(
      AssistantRepository assistants,
      AccessScopePort accessScope,
      WorkflowVersionRepository versions,
      NodeTypeCatalog catalog,
      LinearWorkflowExecutor executor,
      ObjectMapper json) {
    this.assistants = assistants;
    this.accessScope = accessScope;
    this.versions = versions;
    this.catalog = catalog;
    this.executor = executor;
    this.json = json;
  }

  @Override
  public Optional<Invocation> invoke(UUID appId, String versionKey, String input) {
    if (appId == null) {
      return Optional.empty();
    }
    // ① 应用存在且看得见 —— 两条失败合成一个空，调用方无从区分（与 AppOpeningAdapter 同一条口径）
    Optional<Assistant> target = assistants.findById(appId).filter(accessScope::canSee);
    if (target.isEmpty()) {
      return Optional.empty();
    }
    // ② 只认已发布版本：空白 = 最新一版
    Optional<WorkflowVersion> published = publishedVersion(appId, versionKey);
    if (published.isEmpty()) {
      return Optional.empty();
    }

    // ③ 递归深度
    int depth = DEPTH.get();
    if (depth >= MAX_DEPTH) {
      throw new IllegalStateException(
          "应用调用已经嵌套 " + depth + " 层，不能再往下调（多半是 A 调 B、B 又调 A 这样的循环引用）：应用=" + appId);
    }

    WorkflowVersion row = published.get();
    WorkflowCompiler.CompileResult compiled;
    try {
      WorkflowCompiler.Graph graph = json.readValue(row.graph(), WorkflowCompiler.Graph.class);
      compiled = new WorkflowCompiler(catalog).compile(graph);
    } catch (RuntimeException broken) {
      throw new IllegalStateException(
          "被调应用的已发布版本读不出来（版本 " + row.version() + "）：应用=" + appId, broken);
    }
    if (!compiled.ok()) {
      throw new IllegalStateException(
          "被调应用的已发布版本编译不过（版本 " + row.version() + "）：" + compiled.errors());
    }

    DEPTH.set(depth + 1);
    try {
      FlowState state =
          executor.executeAndReturn(UUID.randomUUID(), compiled.plan(), input == null ? "" : input);
      String output = state.read(REPLY_KEY);
      if (output == null) {
        output = state.read(ANSWER_KEY);
      }
      return Optional.of(new Invocation(output == null ? "" : output));
    } finally {
      DEPTH.set(depth);
    }
  }

  /**
   * 取被调应用的那一版：空白取最新，整数按版本号，其余按版本主键。
   *
   * <p>★ 三条路都要按 {@code appId} 收窄 —— 主键那条读的是全局行，只认 id 不认应用， "别人的版本"就能被挂进来（与 {@code
   * WorkflowVersionQueryService.require} 同一条纪律）。
   */
  private Optional<WorkflowVersion> publishedVersion(UUID appId, String versionKey) {
    if (versionKey == null || versionKey.isBlank()) {
      return versions.history(appId).stream().findFirst();
    }
    String key = versionKey.strip();
    try {
      int number = Integer.parseInt(key);
      return versions.findVersion(appId, number);
    } catch (NumberFormatException notNumber) {
      try {
        UUID id = UUID.fromString(key);
        return versions.findById(id).filter(row -> appId.equals(row.assistantId()));
      } catch (IllegalArgumentException notUuid) {
        return Optional.empty();
      }
    }
  }
}
