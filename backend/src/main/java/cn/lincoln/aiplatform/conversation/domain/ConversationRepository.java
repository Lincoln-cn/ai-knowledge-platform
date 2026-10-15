package cn.lincoln.aiplatform.conversation.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <h3>★★ 三个写入口，各管一类字段（2026-10-16 · {@code #108} 之后）</h3>
 *
 * <table>
 *   <tr><th>入口</th><th>管什么</th><th>形状</th></tr>
 *   <tr><td>{@link #save}</td><td>建档 / 改名 / 软删这类<strong>整实体</strong>的写</td>
 *       <td>"读 → 改 → 更新"（调用方手上本来就有一个实体）</td></tr>
 *   <tr><td>{@link #raiseClassification}</td><td>★ 会话累计密级（单调水位）</td>
 *       <td>★ <strong>一条 UPDATE，不读实体</strong></td></tr>
 *   <tr><td>{@link #recordMessage}</td><td>消息计数（{@code +1}）与最后发言时间（水位）</td>
 *       <td>★ <strong>一条 UPDATE，不读实体</strong></td></tr>
 * </table>
 *
 * <p>★ 后两个单列入口<strong>都不接收实体</strong>，而这是刻意的：{@code #108} 的根因就是"读 → 改 → 更新"
 * 在<strong>同事务之外</strong>并发时会丢更新（两个事务各自读到同一个现值、各自提交 ⇒ 后提交的那一次用陈旧现值覆盖）。 签名里没有"把一个会话实体交回来写"这一格 ⇒
 * <strong>"把陈旧实体写回去"这条路径根本不存在</strong>， 不靠"调用方记得用加锁的读"这种纪律。
 *
 * <h3>★★ 会话密级为什么走库侧一条 UPDATE（{@code #108} 的择一与取舍）</h3>
 *
 * <p>三个选项中选的是 <strong>(a) 库侧单调</strong>，理由与另外两个的代价都写在这里（免得后人当成随手选的）：
 *
 * <ul>
 *   <li><strong>(a) 本选择</strong>：{@link #raiseClassification} 是一条 {@code update cv_conversation set
 *       max_classification = ?, updated_at = ? where id = ? and max_classification in (严格低于它的那些档)}
 *       —— 写侧 O(1)、<strong>无重试、不持锁</strong>， 且"单调"是<strong>语句本身的性质</strong>（并发下后到的那条 UPDATE
 *       会在行锁上重评估它的 {@code where}，见 {@code ConversationClassificationWriteTest} 的两条并发用例）；
 *   <li><strong>(b) 乐观锁（{@code @Version} + 重试）不可选，不是取舍问题</strong>：{@code cv_conversation}
 *       <strong>没有</strong> version 列（{@code V32}），加一列要动 {@code db/migration/**}，而那条路径在本批的 边界之外 ⇒
 *       今天物理上做不到；
 *   <li><strong>(c) 行锁（{@code select … for update} 再改）的代价不在往返，而在"要靠调用方记得"</strong>：
 *       它把"唯一写入口"保住了，但抬升仍然是"读 → 改 → 写"，<strong>谁忘了用那把加锁的读，谁就把这个 bug 原样搬回来</strong> ——
 *       而那正是本项目最防的形态（不报错、不留痕）。此外它要多一次往返，并把行锁持有到调用方的事务结束 （这条链路的落库事务里还夹着消息与引用快照）。
 * </ul>
 *
 * <p>★★ <strong>(a) 的代价与它的处置</strong>：口径（"当前值严格低于本轮 max 才抬升"）要在 SQL 里再写一遍。
 * 处置是把它压成<strong>一处</strong>：{@link Conversation#strictlyLowerThan} （次序仍<strong>只</strong>由 {@link
 * cn.lincoln.aiplatform.platform.access.ClassificationLevel} 的声明顺序给出，SQL
 * 里<strong>不出现任何密级次序字面量</strong>）， 并配一条"领域方法与那条 SQL 单调口径一致"的等价用例 （两侧各自抬升同一序列 ⇒ 终值与"是否真的抬升"都必须相同）。
 *
 * <p>★ <strong>为什么也不给"异步 / 后台重算"的入口</strong>（这是 {@code #27} 与 {@code 13 §2.2} 都点名的取舍）： 它只是一行同事务的
 * {@code UPDATE}，异步化只会制造"引用已落库、会话密级还没跟上"的窗口 —— 而那个窗口里的读数正好是"越权能看到"。
 *
 * <h3>★ 本批没有"可见性 / 数据范围"谓词，而且这与检索侧无关</h3>
 *
 * <p>会话的归属就是 {@link Conversation#userId}，它的可见性规则是"只有本人看得到自己的会话"—— 这条没有共享、没有组织维度， 所以这里按 {@code
 * userId} 收窄即可。<strong>将来加会话分享</strong>（{@code ConversationShare}，{@code 13 §2.2} 的"冻结可分享的最高密级"）时，
 * 它必须落在这里的一条谓词里，而不是散到各调用点。
 */
public interface ConversationRepository {

  /** 保存（新增或更新）。★ 会话密级的抬升<strong>不走这里</strong>（见 {@link #raiseClassification}）。 */
  Conversation save(Conversation conversation);

  /**
   * ★★ <strong>按本轮引用集合的最高密级单调抬升会话密级</strong> —— 库侧一条 UPDATE（同事务）。
   *
   * <p>语义三条与 {@link Conversation#raiseClassification} <strong>逐条相同</strong>（只升 / 不降 / 相等幂等），
   * 因为它们<strong>同取</strong> {@link Conversation#strictlyLowerThan} 那一句规则：
   *
   * <ul>
   *   <li>{@code refsMax} <strong>严格高于</strong>现值 ⇒ 抬到 {@code refsMax}；
   *   <li>低于或等于现值 ⇒ 一行都不改（<strong>静默降密这条路径在 SQL 的 {@code where} 里就被挡掉</strong>）；
   *   <li>{@code refsMax} 为 {@code null}（本轮没有引用）⇒ 什么都不做（空集合的 max 是单位元），<strong>不抛异常</strong>。
   * </ul>
   *
   * <p>★ <strong>不要写成字面的 {@code greatest(max_classification, ?)}</strong>（那是 {@code #108}
   * 正文里的示意写法）： 四个机器码的<strong>字典序与密级次序不一致</strong> —— PostgreSQL 实测 {@code greatest('CONFIDENTIAL',
   * 'SECRET') = 'SECRET'} ⇒ 照抄会<strong>静默降密</strong>。 本实现因此不比较字符串，而是把"严格更低的那几档"当参数传下去。
   *
   * @param id 会话 id
   * @param refsMax 本轮回答引用的 chunk 里最高的那一档（{@code null} = 本轮没有引用）
   * @return 是否<strong>真的抬升了</strong>（= 影响了一行）；会话不存在 / 现值不低 / 无引用 ⇒ {@code false}
   */
  boolean raiseClassification(UUID id, ClassificationLevel refsMax);

  /**
   * ★★ <strong>记一条消息落库</strong>（同事务）：{@code message_count + 1}，并把 {@code last_message_at}
   * 前移到<strong>该会话消息里最大的 {@code created_at}</strong>。
   *
   * <p>★ <strong>谁调</strong>：消息落库那一处（{@code ChatTurnWriter#prepare}）—— 每 {@code save} 一条消息就调一次； ★
   * <strong>必须与消息在同一个事务里</strong>，否则"消息已落库、计数没跟上"会变成一个不报错的窗口。
   *
   * <p>★ 两个字段的形状不同，所以写法不同（都在同一条语句里）：
   *
   * <ul>
   *   <li>{@code message_count} 是<strong>计数</strong> ⇒ <strong>增量</strong>（{@code +1}）。写成"重算 {@code
   *       count(*)}"在并发下读到的是<strong>语句快照</strong>（另一个事务的消息还没提交）⇒ 少记， 而增量在行锁上重评估 ⇒ 不漏；
   *   <li>{@code last_message_at} 是<strong>水位</strong>（= 消息时间的 max）⇒ 取 {@code greatest(现值, (select
   *       max(created_at) from cv_message where conversation_id = ?))}：
   *       时间<strong>取自消息表自己</strong>（于是它与 {@code max(cv_message.created_at)} 逐微秒相等，
   *       不会出现"两个时钟各写一个时间"的分叉），{@code greatest} 让它在并发下也单调。
   * </ul>
   *
   * @param id 会话 id
   * @return 是否真的记上了（= 影响了一行）；★ 会话不存在 ⇒ {@code false} —— 而那条路径在调用点上是<strong>异常形状</strong>
   *     （消息刚为这个会话落库过），调用方要把它当破洞处置，不要静默忽略
   */
  boolean recordMessage(UUID id);

  /**
   * 按 id 取。
   *
   * <p>★ <strong>不过滤软删</strong>：软删过的会话仍要能被读到 —— 审计与"会话密级为什么是这个值"的追溯都走这一条。 要不要拒绝它由调用方决定（本批没有调用方）。
   */
  Optional<Conversation> findById(UUID id);

  /** 按用户列出（未软删，最近发言在前；没有发言的会话排在最后）。 */
  List<Conversation> listByUser(UUID userId);
}
