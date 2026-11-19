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
   * 挑一个当前可用的对话模型（"这台上现在有哪个能用"）。
   *
   * <p>★ 与 {@link #find} 的语义差别是本方法存在的唯一理由：{@code find} 回答"我绑定的那一个现在能用吗"（按 id 精确），
   * 这个方法回答"给我一个能用的"（按目录顺序挑第一个已加载的对话模型）。
   *
   * <p>★ 为什么需要它：内置应用模板的 payload **刻意不带 {@code modelId}**（模板要能在任何环境里用）， 而"简单应用必须绑定对话模型"是创建时的硬校验 ⇒
   * 从模板建应用必然 400。补位只能在建应用这一步做， 且必须由当前环境回答"用哪个模型"，不能写进模板。
   *
   * <p>★ 默认返回空而不是要求每个实现都写一遍：这是<strong>给一条特定路径用的能力</strong>， 不是核心查询。生产的唯一实现 {@code
   * ModelStatusAdapter} 会覆盖它；测试里的 lambda 替身不该被它牵连 （{@link #find} 是唯一抽象方法时，本接口可以被写成
   * lambda，加第二个抽象方法会让全仓的 lambda 当场编译红）。
   *
   * @return 没有已加载的对话模型 ⇒ empty（调用方要把"没有可用模型"说成人话，而不是"内部错误"）
   */
  default Optional<ModelSnapshot> pickLoadedChatModel() {
    return Optional.empty();
  }

  /**
   * 一条模型快照。
   *
   * @param id 模型 id
   * @param name 显示名（发布校验的失败原因与导出包的依赖清单都要用它，否则只能说"某个模型"）
   * @param loaded 是否处于"已加载成功"（AS-I2 的判据；{@code 加载中} / {@code 加载失败} / {@code 未加载} 都是 false）
   */
  record ModelSnapshot(UUID id, String name, boolean loaded) {}
}
