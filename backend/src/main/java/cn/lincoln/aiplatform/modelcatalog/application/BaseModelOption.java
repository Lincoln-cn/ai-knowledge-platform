package cn.lincoln.aiplatform.modelcatalog.application;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelHealth;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;

/**
 * 从供应商拉取到的可注册基础模型（契约 {@code BaseModelOption}）。
 *
 * <p>★ 它是「基础模型目录」（画布"新建模型"弹窗的卡片列表），不是已注册的模型资产。 同一基础模型可以被注册多份配置（不同 apiBase / key）。
 *
 * <p>★ {@code health} / {@code healthDetail} 是**探测出来的**，不是上游给的：网关的模型清单里没有健康字段 （JAiRouter 的 {@code
 * /v1/models} 只有 id / object / created / owned_by / service_type / adapter）， 而"清单里有"与"调得通"是两件事 ——
 * 详见 {@link ModelHealth}。调用方（界面）据此<strong>禁止</strong>把 {@link ModelHealth#UNHEALTHY} 的模型配置进模型管理。
 *
 * @param contextWindow 对话模型的上下文窗口；向量模型为 null
 * @param dimension 向量模型的输出维度；对话模型为 null
 * @param health 调用健康状态
 * @param healthDetail 探测细节（健康时可为空；不健康时是上游的原话，别改写成"失败"两个字）
 */
public record BaseModelOption(
    String baseModel,
    ModelKind kind,
    Supplier supplier,
    Integer contextWindow,
    Integer dimension,
    ModelHealth health,
    String healthDetail) {}
