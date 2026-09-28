package cn.lincoln.aiplatform.modelcatalog.application;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;

/**
 * 从供应商拉取到的可注册基础模型（契约 {@code BaseModelOption}）。
 *
 * <p>★ 它是「基础模型目录」（画布"新建模型"弹窗的卡片列表），不是已注册的模型资产。 同一基础模型可以被注册多份配置（不同 apiBase / key）。
 *
 * @param contextWindow 对话模型的上下文窗口；向量模型为 null
 * @param dimension 向量模型的输出维度；对话模型为 null
 */
public record BaseModelOption(
    String baseModel,
    ModelKind kind,
    Supplier supplier,
    Integer contextWindow,
    Integer dimension) {}
