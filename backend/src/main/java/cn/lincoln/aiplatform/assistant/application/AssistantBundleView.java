package cn.lincoln.aiplatform.assistant.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 导出包（契约 {@code AssistantBundle}）—— 第 15 周判据③。
 *
 * <p>★ 它由两部分组成：<strong>配置体</strong>（重新创建这个应用所需的一切）与<strong>依赖清单</strong> （导入方环境里能不能满足）。 两者缺一不可 ——
 * 只给配置体，导入方会在导入后才发现"模型没加载、知识库不在"，而那时已经建了一半。
 *
 * <p>★ {@code dependencies} 的每一项都要能回答三件事：<strong>是什么 / 在不在 / 缺了会怎样</strong>。 第三条（{@link
 * Dependency#degrade}）是最容易被漏掉的：没有它，用户只能看到一个红点，不知道要不要继续导入。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantBundleView(
    String format,
    Instant exportedAt,
    boolean includesDocuments,
    Config config,
    List<Dependency> dependencies) {

  /**
   * 包里的配置体（契约 {@code AssistantBundleConfig}）。
   *
   * <p>⚠️ 比契约少一个 {@code workflow}：流程定义住在 {@code workflow} 模块，而它尚未落地。 回一个空图会让导入方以为"这条流程本来就是空的"——
   * 依赖清单里那条 {@code WORKFLOW} 项会如实说明后果。
   */
  public record Config(
      String name,
      String icon,
      String description,
      String type,
      UUID modelId,
      List<UUID> kbIds,
      String rolePrompt,
      List<UUID> promptIds,
      AssistantView.RetrievalView retrieval,
      boolean multiTurn,
      boolean thinkingEnabled,
      String prologue,
      List<String> quickQuestions) {}

  /**
   * 一条依赖。
   *
   * @param kind {@code MODEL} / {@code KNOWLEDGE_BASE} / {@code PROMPT} / {@code WORKFLOW}（契约
   *     {@code BundleDependencyKind}）
   * @param satisfied 导入方环境里是否满足（缺失项要显式确认"跳过引用导入"才允许导入）
   * @param degrade ★ 缺失时的降级后果 —— 导入方必须看到（字段不出现 = 没有后果）
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Dependency(
      String kind, String name, String detail, boolean satisfied, String degrade) {}
}
