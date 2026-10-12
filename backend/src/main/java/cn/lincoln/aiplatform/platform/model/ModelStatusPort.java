package cn.lincoln.aiplatform.platform.model;

import java.util.Optional;
import java.util.UUID;

/**
 * 模型状态查询端口（跨模块 SPI）—— 由 {@code modelcatalog} 实现（它拥有 {@code mc_model}）， {@code assistant} 消费（第 15 周
 * · 0.4.3 的发布校验 AS-I2）。
 *
 * <h3>为什么不能复用 {@link ChatPort}</h3>
 *
 * <p>{@link ChatPort#pickChatModel()} 回答的是"<strong>给我一个能用的</strong>"（挑一个已加载的），
 * 而发布校验要回答的是"<strong>我绑定的那一个 现在能用吗</strong>" —— 前者按目录顺序挑，后者必须按 id 精确回答。 把两者塞进一个方法，就得给 {@code
 * pickChatModel} 加一个 "可选地按 id 挑、找不到也返回空"的变形，那个方法的语义会立刻变得说不清。
 *
 * <p>★ 它返回的是一条<strong>快照</strong>而不是实体：消费方（发布校验、导出包）要的只是"名字 + 加载好没有"， 而把 {@code modelcatalog} 的实体交给
 * {@code assistant} 会让两个模块的持久化形状从此绑在一起。
 */
public interface ModelStatusPort {

  /**
   * 按 id 查模型的可用状态。
   *
   * @return 模型不存在或已软删 ⇒ <strong>empty</strong>（★ 不是异常：发布校验要把"模型没了"与"模型没加载"分开说）
   */
  Optional<ModelSnapshot> find(UUID modelId);

  /**
   * 一条模型快照。
   *
   * @param id 模型 id
   * @param name 显示名（发布校验的失败原因与导出包的依赖清单都要用它，否则只能说"某个模型"）
   * @param loaded 是否处于"已加载成功"（AS-I2 的判据；{@code 加载中} / {@code 加载失败} / {@code 未加载} 都是 false）
   */
  record ModelSnapshot(UUID id, String name, boolean loaded) {}
}
