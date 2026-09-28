package cn.lincoln.aiplatform.modelcatalog.application;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * 模型视图（契约 {@code ModelInfo}）。
 *
 * <p>★ 它<strong>不含</strong> {@code apiKey} —— 契约里没有这个字段， 而"视图对象带着 API key"是那种"只要有人顺手 {@code return
 * model} 就泄露了"的形状。 与 {@code UserView} 不含 {@code passwordHash} 同一条纪律。 显式列出每个字段，等于把"哪些能出网"变成一份可阅读的清单。
 *
 * @param contextWindow 对话模型：上下文窗口；向量模型为 null
 * @param dimension 向量模型：输出维度；对话模型为 null。维度与向量表绑定，改则需全量重灌
 * @param loadError 加载失败的可读排查原因。★ <strong>仅 {@code LOAD_FAILED} 时有值</strong>， 其余状态为 null
 *     并省略字段（{@code NON_NULL} 标在组件上，与 {@code UserView.initialPassword} 同一写法 —— {@code status} 等字段为
 *     null 时契约要求字段在，不能整条 record 一起省掉）
 */
public record ModelView(
    UUID id,
    String name,
    ModelKind kind,
    Supplier supplier,
    String baseModel,
    ModelStatus status,
    Integer contextWindow,
    Integer dimension,
    ThinkingMode thinking,
    ToolsMode tools,
    boolean builtin,
    String remark,
    @JsonInclude(JsonInclude.Include.NON_NULL) String loadError) {

  /** 从聚合映射成视图。★ 不拷贝 apiKeyCipher / apiKeyIv —— 它们永远不出网。 */
  public static ModelView from(Model model) {
    return new ModelView(
        model.id(),
        model.name(),
        model.kind(),
        model.supplier(),
        model.baseModel(),
        model.status(),
        model.contextWindow(),
        model.dimension(),
        model.thinking(),
        model.tools(),
        model.builtin(),
        model.remark(),
        model.loadError());
  }
}
