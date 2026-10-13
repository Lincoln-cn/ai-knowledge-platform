package cn.lincoln.aiplatform.conversation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <h3>★★ 本接口只有一个写入口，是刻意的</h3>
 *
 * <p>{@link #save} 就是<b>唯一</b>的写路径，而"会话累计密级"的写发生在它上面。{@code 13 §2.2} 与 {@code 02 §5.2} 把写入点定成
 * <strong>「本轮回答的引用集合落库」那一刻的同事务内</strong>：
 *
 * <pre>
 *   conversation.raiseClassification(maxOfThisRoundRefs);   // 领域层：只升不降
 *   conversationRepository.save(conversation);              // 与引用快照同事务
 * </pre>
 *
 * <p>★ <strong>为什么不给一个 {@code raiseClassification(id, level)} 的仓储方法</strong>：那会把"读-改-写"拆给库层，
 * 而"会不会降密"就取决于 SQL 里写的是 {@code max_classification = ?} 还是 {@code = greatest(max_classification, ?)}
 * —— 一句 SQL 的差别、症状是静默降密。 ★ <strong>为什么也不给"异步 / 后台重算"的入口</strong>（这是 {@code #27} 与 {@code 13 §2.2}
 * 都点名的取舍）： 它只是一行同事务的 {@code UPDATE}，异步化只会制造"引用已落库、会话密级还没跟上"的窗口 —— 而那个窗口里的读数正好是"越权能看到"。
 *
 * <h3>★ 本批没有"可见性 / 数据范围"谓词，而且这与检索侧无关</h3>
 *
 * <p>会话的归属就是 {@link Conversation#userId}，它的可见性规则是"只有本人看得到自己的会话"—— 这条没有共享、没有组织维度， 所以这里按 {@code
 * userId} 收窄即可。<strong>将来加会话分享</strong>（{@code ConversationShare}，{@code 13 §2.2} 的"冻结可分享的最高密级"）时，
 * 它必须落在这里的一条谓词里，而不是散到各调用点。
 */
public interface ConversationRepository {

  /** 保存（新增或更新）。★ 会话密级的抬升与引用快照的落库必须是<strong>同一个事务</strong>。 */
  Conversation save(Conversation conversation);

  /**
   * 按 id 取。
   *
   * <p>★ <strong>不过滤软删</strong>：软删过的会话仍要能被读到 —— 审计与"会话密级为什么是这个值"的追溯都走这一条。 要不要拒绝它由调用方决定（本批没有调用方）。
   */
  Optional<Conversation> findById(UUID id);

  /** 按用户列出（未软删，最近发言在前；没有发言的会话排在最后）。 */
  List<Conversation> listByUser(UUID userId);
}
