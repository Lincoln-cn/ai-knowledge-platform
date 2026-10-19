package cn.lincoln.aiplatform.platform.assistant;

import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 应用绑定的<strong>只读端口</strong>（跨模块 SPI）—— 接口放 {@code platform}、实现放 {@code assistant}。
 *
 * <h3>★★ 它为什么必须存在（而不是让调用方直接读应用仓储）</h3>
 *
 * <p>{@code assistant} 是 {@code CLOSED} 模块：{@code assistant.domain} 不在它的暴露面上， 所以"别的模块直接依赖 {@code
 * Assistant}/{@code AssistantRepository}"这件事会被 {@code ModuleBoundaryTest}（Spring Modulith 的 {@code
 * verify()}）判红 —— 这正是本端口诞生的那一处红： {@code Module 'conversation' depends on non-exposed type
 * assistant.domain.AssistantRepository}。
 *
 * <p>★ 处置<strong>不是</strong>把 {@code assistant.domain} 开成 {@code @NamedInterface}（那是"改闸门去迁就实现"，
 * 而且会把应用实体的<strong>全部</strong>字段与写入口一起摊开给所有模块）， 而是本项目既定的那条口径（与 {@code AccessScopePort} / {@code
 * OrgDirectoryPort} / {@code SegmentSearchPort} 同族）：
 *
 * <table>
 *   <tr><th>选择</th><th>结果</th></tr>
 *   <tr><td>接口放 {@code platform}</td><td>所有模块已依赖 platform ⇒ {@code 01 §三.2} 的依赖矩阵<strong>零改动</strong></td></tr>
 *   <tr><td>实现放 {@code assistant}</td><td>"哪些应用、绑了什么"只有一个真相源，落在拥有 {@code as_assistant} 的那个模块里</td></tr>
 * </table>
 *
 * <h3>★★ 为什么"可见性判定"在实现里，而不留给调用方</h3>
 *
 * <p>返回空有<strong>两个</strong>原因：应用不存在，或当前身份看不见它。两者刻意<strong>合并成一个出口</strong>：
 *
 * <ul>
 *   <li>调用方据此一律返 <strong>404 而不是 403</strong>（{@code 07 §3.3} 纪律 2）—— 403 等于告诉对方 "这个 id 是存在的"，
 *       那是一次免费的探测；
 *   <li>★ 更要紧的是<strong>形状上的 fail-closed</strong>：若端口只给"绑定事实"、把可见性留给调用方， 那么"忘掉那一次 {@code
 *       canSee}"就是一个不会报错的越权（用户用上了别人看不见的应用、连带用上了它绑的知识库）。 判定在实现里（{@link
 *       cn.lincoln.aiplatform.platform.access.AccessScopePort#canSee} 那一处） ⇒
 *       调用方<strong>没有机会</strong>忘记。
 * </ul>
 *
 * <p>★ 端口<strong>只读且只带问答那一轮真正要用的最小事实</strong>：模型 / 知识库 / 多轮 / 角色设定 / 检索参数。 应用的名称、发布状态、调用计数都不在这里 ——
 * 那些是 {@code assistant} 自己的用例面 （{@code AssistantService}）， 别的模块不该"认识一个应用的全部"。★ 角色设定是唯一的例外：它直接进入
 * prompt，是"这一轮怎么答"的一部分， 不能让 conversation 再读一次应用实体（那会绕开模块边界，也会让两条提示词来源分叉）。
 */
public interface AppBindingPort {

  /**
   * 一个应用在"问答那一轮"要用到的四个事实。
   *
   * @param appId 应用 id（调用方要把它写进会话，{@code cv_conversation.assistant_id}）
   * @param modelId 应用绑定的对话模型；★ <strong>可以是 {@code null}</strong> —— {@code WORKFLOW} 类型没有应用级模型
   *     （模型在各节点里，库里 {@code chk_as_model_by_type} 兜着这一条）。调用方见到 {@code null} 时的正确动作是"另挑一个可用的
   *     对话模型"，不是"没有模型"（见 {@code ChatPort#pickChatModel}）
   * @param kbIds 应用绑定的知识库（★ 顺序不承诺语义；空 = 没绑 —— 那是通用问答，不是"绑了一个空库"）
   * @param rolePrompt 应用的角色设定；★ 空串 = 应用没有设定（**不回落**到平台通用提示词）
   * @param retrieval 应用级检索参数（{@code #185}）。★ 它此前不在这份事实里，于是配置页的阈值 / TopN / 无引用策略 全是死控件；问答链路只能读平台设置
   */
  record AppBinding(
      UUID appId,
      UUID modelId,
      List<UUID> kbIds,
      boolean multiTurn,
      String rolePrompt,
      RetrievalParams retrieval) {}

  /**
   * 取"当前身份可见的"那个应用的绑定。
   *
   * @param appId 应用 id；{@code null} ⇒ 空（通用问答没有应用）
   * @return 绑定；★ <strong>空 = 不存在或看不见</strong>（两者同一个出口，调用方一律 404，不要试图分辨）
   */
  Optional<AppBinding> findVisibleById(UUID appId);
}
