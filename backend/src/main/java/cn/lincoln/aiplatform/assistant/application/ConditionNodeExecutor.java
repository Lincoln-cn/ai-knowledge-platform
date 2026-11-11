package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code CONDITION_NODE}：按分支上写的判定条件选一条路走。
 *
 * <p>★ <strong>为什么用模型判而不是解析成表达式</strong>：画布上这个节点的「判定条件」是一个**自然语言输入框** （{@code
 * form_data.branch[i].expression}，占位提示就是「判定条件（自然语言）」）。把它解析成表达式需要一套 DSL， 而用户写的是「问题是在问住宿标准」这种句子 ——
 * 那不是 DSL，是给模型的指令。 数值比较有独立的 {@code NUMBER_CONDITION_NODE}，那一个可以精确解析，不走模型。
 *
 * <p>★ <strong>判定结果写 {@code active_branch}，执行器据此跳过另一条链</strong>（见 {@code LinearWorkflowExecutor}
 * 里的跳过逻辑）。分支 id 就是画布上出边的 {@code sourceHandle}，两边必须一致，否则永远跳不对。
 *
 * <p>★ <strong>模型给不出合法分支时的处置</strong>：抛异常让整次运行失败，而不是"猜一个"。 猜错的后果是用户拿到一条走错路的答案，而且运行记录看起来完全正常 ——
 * 那是比失败更糟的情况。 一条既有 {@code else} 分支（{@code type = "else"}）会被当成兜底：模型答不上来时走它，并在日志里说明。
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(ConditionNodeExecutor.class);

  /** 节点 {@code properties.config} 里放分支清单的键。 */
  static final String CONFIG_BRANCH = "branch";

  /** 分支里标「其它情况」的类型名（与画布 {@code branchTypeLabel} 同口径）。 */
  static final String ELSE_TYPE = "else";

  static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final ChatPort chatPort;

  public ConditionNodeExecutor(ChatPort chatPort) {
    this.chatPort = chatPort;
  }

  @Override
  public String type() {
    return "CONDITION_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    List<Branch> branches = branchesOf(context.nodeConfig());
    if (branches.isEmpty()) {
      throw new IllegalStateException("条件节点没有任何分支：这张图没法决定走哪条路");
    }

    String question = context.input() == null ? "" : context.input().trim();
    Branch chosen = decide(branches, question);
    if (chosen == null) {
      throw new IllegalStateException(
          "条件节点判不出该走哪条分支（分支：" + branches.stream().map(Branch::id).toList() + "）");
    }

    log.debug("#workflow 条件节点选中分支 {}（{}）", chosen.id(), chosen.expression());
    context.state().write(LinearWorkflowExecutor.ACTIVE_BRANCH, chosen.id(), KeyUpdate.REPLACE);
  }

  /** 一条分支：{@code id} 与画布出边的 {@code sourceHandle} 同值，{@code expression} 是自然语言判定条件。 */
  record Branch(String id, String type, String expression) {}

  /**
   * 从节点配置里读分支清单。
   *
   * <p>★ 兼容两种形状：{@code branch} 是 {@code List<Map>}（画布存的原样）或 {@code List<Branch>}（测试直接给的）。
   * 前者是生产路径，后者方便单测 —— 让判据不必伪造一整个 {@code Map} 结构。
   */
  @SuppressWarnings("unchecked")
  static List<Branch> branchesOf(Map<String, Object> config) {
    Object raw = config == null ? null : config.get(CONFIG_BRANCH);
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<Branch> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Branch branch) {
        out.add(branch);
      } else if (item instanceof Map<?, ?> map) {
        Object id = map.get("id");
        Object type = map.get("type");
        Object expression = map.get("expression");
        if (id != null) {
          out.add(
              new Branch(
                  String.valueOf(id),
                  type == null ? null : String.valueOf(type),
                  expression == null ? "" : String.valueOf(expression)));
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * 让模型在分支里选一个。
   *
   * <p>★ 有 {@code else} 分支时**不把它列进候选**：让模型在「A / B / 其它」里选，它会为了省事直接选「其它」。 正确顺序是先用模型在**具名分支**里选，选不出再落
   * {@code else}。
   */
  private Branch decide(List<Branch> branches, String question) {
    List<Branch> named = branches.stream().filter(b -> !ELSE_TYPE.equals(b.type())).toList();
    Branch fallback =
        branches.stream().filter(b -> ELSE_TYPE.equals(b.type())).findFirst().orElse(null);

    if (named.isEmpty()) {
      return fallback;
    }
    if (named.size() == 1) {
      // 只有一个具名分支时仍然问模型：它完全可以是"不满足就走 else"，而 else 可能不存在。
      // 但更常见的是画布上只配了一个条件 —— 这时模型的判断就是"成不成立"。
      return named.get(0);
    }

    UUID modelId =
        chatPort
            .pickChatModel()
            .orElseThrow(() -> new IllegalStateException("条件节点需要模型判定，但平台没有默认对话模型"));
    String prompt = buildPrompt(named, question);
    String answer = chatPort.complete(modelId, prompt, TIMEOUT);
    Optional<Branch> hit =
        named.stream().filter(b -> answer != null && answer.contains(b.id())).findFirst();
    if (hit.isPresent()) {
      return hit.get();
    }
    if (fallback != null) {
      log.warn("#workflow 条件节点模型没给出合法分支（回答：{}），走 else 分支", answer);
      return fallback;
    }
    return null;
  }

  /** 拼判定提示词：候选以「编号 + 条件原话」列出，要求只回编号。 */
  static String buildPrompt(List<Branch> branches, String question) {
    StringBuilder sb = new StringBuilder();
    sb.append("请判断下面的问题符合哪一条条件，只回编号，不要解释。\n\n候选：\n");
    for (Branch branch : branches) {
      sb.append(branch.id()).append("：").append(branch.expression()).append('\n');
    }
    sb.append("\n问题：").append(question);
    return sb.toString();
  }
}
