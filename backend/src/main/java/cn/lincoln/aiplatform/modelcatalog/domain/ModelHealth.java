package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 基础模型在上游侧的**调用健康状态**（`#1.7`）。
 *
 * <p>★ 与 {@link ModelStatus} 是两回事，不许互相冒充：{@code ModelStatus} 是<strong>已注册模型在本平台的上游加载生命周期</strong>
 * （{@code LOADING / LOADED / LOAD_FAILED}，见 `ModelController` 的 `#191` 注释）；这里是"这个基础模型**能不能调用**"，
 * 判据只有一条 —— 真发一次请求看它回不回。
 *
 * <p>★ 为什么必须真调：网关的 {@code GET /v1/models} 只说明"清单里有这个名字"，而 JAiRouter 那套里 "实例健康但适配器解析不到"（{@code
 * adapterResolved=false}）是常态故障（它自己的 `#1004` 就是这么描述的） ⇒ 只看清单会得到一个"绿灯的坏模型"，然后所有问答都失败在这里。
 *
 * <p>★ 为什么不给多模态做探测：它们没有 {@code messages} 语义，拿 {@code TTS / STT / IMAGE} 去调 {@code
 * /chat/completions} 只会得到 400，然后被当成"模型不可用"（{@link ModelKind} 的 javadoc 已把这条写死）。
 *
 * @see ModelKind#CHAT
 */
public enum ModelHealth {
  /** 真调过一次，回来了。 */
  HEALTHY,
  /** 真调过一次，上游明确拒绝了（404 没有这个模型 / 400 参数不对 / 5xx）。**这种不许配置进模型管理。** */
  UNHEALTHY,
  /** 探不了：没有上游地址、探测超时、多模态不做调用探测。**"不知道"不等于"不健康"**，不据此拦人。 */
  UNKNOWN
}
