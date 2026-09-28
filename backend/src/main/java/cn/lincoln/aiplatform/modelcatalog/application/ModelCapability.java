package cn.lincoln.aiplatform.modelcatalog.application;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;

/**
 * 能力探测结果（契约 {@code ModelCapability}）。
 *
 * <p>★ 返回值的 {@code source} 标注是「探测」还是「手工声明」——界面必须区分展示（{@code ModelCreateForm} 用它切换标签颜色）。
 *
 * <p>★ 向量模型的 {@code thinking} 恒为 {@code NONE}、{@code tools} 恒为 {@code UNSUPPORTED}（向量模型没有对话能力），
 * 对话模型的 {@code dimension} 恒为 null。与 {@code Model} 的 {@code validateCapacity} 同一条规则。
 *
 * @param contextWindow 对话模型的上下文窗口；向量模型为 null
 * @param dimension 向量模型的输出维度（从 embeddings 实测向量长度得出）；对话模型为 null
 */
public record ModelCapability(
    ThinkingMode thinking,
    ToolsMode tools,
    Integer contextWindow,
    Integer dimension,
    ModelCapabilitySource source) {

  /**
   * 构造时钉住 kind 与容量字段的 null 语义 —— 与 {@code Model.validateCapacity} 同一条规则，
   * 但这里是"探测结果"的形状约束：对话模型不产出向量、向量模型没有对话窗口。
   */
  public static ModelCapability of(
      ModelKind kind,
      ThinkingMode thinking,
      ToolsMode tools,
      Integer contextWindow,
      Integer dimension,
      ModelCapabilitySource source) {
    if (kind == ModelKind.CHAT) {
      return new ModelCapability(
          thinking == null ? ThinkingMode.NONE : thinking,
          tools == null ? ToolsMode.UNSUPPORTED : tools,
          contextWindow,
          null,
          source);
    }
    return new ModelCapability(ThinkingMode.NONE, ToolsMode.UNSUPPORTED, null, dimension, source);
  }
}
