package cn.lincoln.aiplatform.modelcatalog.domain;

import java.util.List;
import java.util.UUID;

/**
 * 向量化端口（modelcatalog 对外发布的接口之一，{@code 01 §二} 第 3 行）。
 *
 * <p>★ <strong>端口在我们这边</strong>：底层可能是 Spring AI 的 {@code EmbeddingModel}、也可能是原生适配器， 但业务模块只依赖这个接口 ——
 * 这是"若框架下不了某件事、就换原生适配器"（风险 R9）这条退路能成立的前提。 入库链（knowledge）只需要"给我向量"，不需要知道模型存在哪、密钥怎么解。
 *
 * <p>★ 契约里最关键的一条是<strong>顺序</strong>：返回的第 i 个向量必须对应入参的第 i 段文本。 向量与文本错配不会报错，只会让检索返回"别的内容"的邻居 ——
 * 所以实现必须保证它（见适配器里的归位与校验）。
 */
public interface EmbeddingPort {

  /**
   * 把一批文本向量化。
   *
   * @param modelId 已注册的向量模型 id（{@code kind=EMBEDDING}）
   * @param texts 待向量化的文本（顺序即结果顺序）
   * @return 与 {@code texts} 一一对应的向量
   * @throws cn.lincoln.aiplatform.platform.domain.BusinessException 模型不存在 / 不是向量模型（可读原因）
   * @throws RuntimeException 上游不可达或响应不合规（由适配器抛可读异常）
   */
  List<float[]> embed(UUID modelId, List<String> texts);
}
