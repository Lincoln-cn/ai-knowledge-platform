package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把"节点配置里选的那个模型主键"解析成"这次调用要用的上游连接"。
 *
 * <p>★ 四个媒体适配器（图像理解 / 图像生成 / 语音合成 / 语音识别）的这段逻辑完全一样， 所以只写一份：模型不存在、已停用、种类不对，三条判断漏在任何一个适配器里，症状都是
 * "这个节点莫名调不通"，而要翻四个文件才知道差在哪一条。
 *
 * <p>★ 它<strong>不</strong>抛异常表示"不可用"，只返回 {@code empty}：三种情况（不存在 / 停用 / 种类不对）对上层是同一件事 ——
 * 这台机器上现在调不了。要区分它们该去模型管理页看， 不该在流程运行日志里猜。
 *
 * <p>★ 密钥解密与出网边界都走 {@link ModelUpstreamResolver#resolve} —— 那是全项目唯一的 上游解析入口。在这里自己读 {@code
 * model.apiKey()} 会绕过 {@code #72} 的密级闸门。
 */
@Component
class MultimodalTargets {

  /** 一次媒体调用需要的上游参数。 */
  record Target(String baseModel, String apiBase, String apiKey, AuthStyle authStyle) {}

  private final ModelRepository models;
  private final ModelUpstreamResolver upstream;

  MultimodalTargets(ModelRepository models, ModelUpstreamResolver upstream) {
    this.models = models;
    this.upstream = upstream;
  }

  /**
   * @param modelId 节点配置里的模型主键；null ⇒ 空（"没选模型"与"模型坏了"是两句话，由执行器分别说）
   * @param expected 期望的模型种类
   */
  Optional<Target> resolve(UUID modelId, ModelKind expected) {
    if (modelId == null) {
      return Optional.empty();
    }
    return models
        .findById(modelId)
        .filter(model -> model.enabled() && model.kind() == expected)
        .map(
            model -> {
              ResolvedUpstream resolved = upstream.resolve(model);
              return new Target(
                  model.baseModel(), resolved.apiBase(), resolved.apiKey(), resolved.authStyle());
            });
  }

  /** 给执行器用的"模型为什么调不了"提示（不含模型内容，只有种类名）。 */
  static String unavailableHint(String nodeName, ModelKind expected) {
    return "「" + nodeName + "」需要 " + expected + " 种类的模型：请确认它已加载、已启用，且种类没选错";
  }

  /** 供测试与日志用：把一个模型显示成 {@code 名字(kind)}，避免把整行打出来。 */
  static String describe(Model model) {
    return model == null ? "（无）" : model.name() + "(" + model.kind() + ")";
  }
}
