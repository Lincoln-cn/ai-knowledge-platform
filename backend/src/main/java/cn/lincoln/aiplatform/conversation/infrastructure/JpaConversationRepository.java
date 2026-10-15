package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data 的会话仓储。
 *
 * <p>★ 纯 Spring Data 接口，不同时继承 domain 的 {@code ConversationRepository} —— 双继承会让 {@code Pageable} /
 * {@code Specification} 这些框架类型漏进领域层（与 {@code JpaKnowledgeBaseRepository} / {@code
 * JpaQaPairRepository} 同一条判断）。
 *
 * <p>★ 两个"水位/计数"的写入口是 {@link Modifying 批量 UPDATE}，与 {@code
 * JpaRefreshTokenRepository#revokeAllActiveByUserId} / {@code JpaOrgRepository#rewritePathPrefix}
 * 同一条判断：<strong>"读出来逐个改"会引入窗口</strong> （那边是"改到一半"，这里是"并发丢更新"）。
 */
public interface JpaConversationRepository extends JpaRepository<Conversation, UUID> {

  /**
   * 某个人的会话（未软删，最近发言在前；还没发过言的排在最后）。
   *
   * <p>★ {@code deletedAtIsNull} 与 {@code V32} 的 {@code idx_conv_user}（{@code where deleted_at is
   * null}） 是同一个口径：partial 索引只有查询也带这个条件才会被用到。
   */
  List<Conversation> findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDescCreatedAtDesc(
      UUID userId);

  /**
   * ★★ <strong>单调抬升会话密级</strong>（{@code #108} 的择一 (a)）：一条 UPDATE，<strong>不读实体</strong>。
   *
   * <p>★★ 为什么 {@code where} 是"现值 ∈ {@code lower}"而不是字面的 {@code greatest(max_classification,
   * ?)}（{@code #108} 正文里的示意写法）： 那四个机器码的<strong>字典序与密级次序不一致</strong>（PostgreSQL 实测 {@code
   * greatest('CONFIDENTIAL', 'SECRET') = 'SECRET'}）⇒ 照抄会<strong>静默降密</strong>。 而"严格更低的那几档"由 {@link
   * Conversation#strictlyLowerThan} 给出 ⇒ 这条 SQL 里 <strong>不出现任何密级次序字面量</strong>（次序只有 {@link
   * ClassificationLevel} 的声明顺序一处权威）。
   *
   * <p>★ <strong>并发下为什么不会丢更新</strong>：两条 UPDATE 撞在同一行上时，后到的那条会等行锁， 拿到锁之后 PostgreSQL
   * 会<strong>用最新已提交的行重评估它的 {@code where}</strong>（{@code READ COMMITTED}） ⇒
   * 低的那一档改不动、高的那一档总能落地，终值恒为两者的 max。★ 这与"读 → 改 → 更新"的差别正是：
   * 后者比较的是<strong>事务开始时读到的那个现值</strong>，而不是"轮到它写时的现值"。
   *
   * <p>★ {@code clearAutomatically}：批量 UPDATE 绕过持久化上下文 ⇒ 不清的话，同一个事务里随后读这个会话
   * 会拿到<strong>内存里那份旧的</strong>（读对了、值错了）。{@code flushAutomatically}：先落库再改， 免得"消息刚 insert、这条 UPDATE
   * 先跑"这种次序在同一个事务里出现。
   *
   * <p>★ {@code lower} 为空（{@code refsMax} 为 {@code PUBLIC}）时<strong>不要调到这里</strong>： 空集合渲染成 {@code
   * in ()} 是非法 SQL。适配器在调用前就短路返回（那不是第二条规则，而是同一条规则在空集合上的结论）。
   *
   * @param id 会话 id
   * @param level 本轮引用集合的最高密级
   * @param lower 严格低于 {@code level} 的那几档（{@link Conversation#strictlyLowerThan}）
   * @return 影响的行数（1 = 真的抬升了；0 = 现值不低 / 会话不存在）
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update Conversation c set c.maxClassification = :level, c.updatedAt = current_timestamp"
          + " where c.id = :id and c.maxClassification in :lower")
  int raiseClassification(
      @Param("id") UUID id,
      @Param("level") ClassificationLevel level,
      @Param("lower") List<ClassificationLevel> lower);

  /**
   * ★★ <strong>记一条消息落库</strong>：{@code message_count + 1}，并把 {@code last_message_at} 前移到该会话消息里最大的
   * {@code created_at}（{@code #108} 的计数器写入口）。
   *
   * <p>★★ 为什么用原生 SQL 而不是 JPQL：{@code last_message_at} 的取值要<strong>读消息表</strong> （{@code select
   * max(created_at) from cv_message …}），而 HQL 的批量 UPDATE 的 {@code set} 里 <strong>不允许子查询</strong>。★
   * 它也因此比"调用方传一个 {@code Instant.now()}"更准： 两个时钟各写一个时间的话，{@code last_message_at} 与 {@code
   * max(cv_message.created_at)} 会差那么几百微秒 —— 那点差在断言里看得出来、在界面上看不出来（正是最难查的形状）。
   *
   * <p>★ 两半的写法不同，因为它们是两种东西：
   *
   * <ul>
   *   <li>{@code message_count} 是<strong>计数</strong> ⇒ {@code + 1}（原子增量）。写成 {@code (select count(*)
   *       …)} 会在并发下读到<strong>语句快照</strong>（另一个事务的消息还没提交）⇒ 少记；
   *   <li>{@code last_message_at} 是<strong>水位</strong>（max）⇒ {@code greatest(现值, 消息表里的 max)}。 ★
   *       PostgreSQL 的 {@code greatest} <strong>忽略 NULL</strong>（{@code greatest(null, x) = x}），
   *       所以"还没有消息"（NULL）不需要额外的 {@code coalesce}。
   * </ul>
   *
   * <p>★ {@code flushAutomatically = true} 是<strong>必须的</strong>（不是照抄前例）：消息是 JPA 刚 {@code save} 的，
   * 而 Hibernate 的自动 flush <strong>不覆盖原生查询</strong> ⇒ 不显式 flush，子查询就读不到刚插入的那条消息 （{@code
   * last_message_at} 会停在上一轮）。
   *
   * @param id 会话 id
   * @return 影响的行数（1 = 记上了；0 = 会话不存在 —— 那是异常形状，调用方要处置）
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "update cv_conversation c set message_count = c.message_count + 1,"
              + " last_message_at = greatest(c.last_message_at,"
              + " (select max(m.created_at) from cv_message m where m.conversation_id = c.id)),"
              + " updated_at = now() where c.id = :id",
      nativeQuery = true)
  int recordMessage(@Param("id") UUID id);
}
