package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 Spring Data 的会话仓储适配成 domain 的 {@link ConversationRepository}。
 *
 * <p>★ 存在的理由与其它适配器相同：<strong>框架类型不许进领域层</strong>。
 *
 * <p>★ {@link #save} 仍然是薄薄的一次 {@code save}（建档 / 改名），而两个"水位/计数"的写入口 （{@link #raiseClassification} /
 * {@link #recordMessage}）<strong>不经过实体</strong>：它们把 {@link Conversation}
 * 给出的<strong>那一句规则</strong>翻译成 {@link JpaConversationRepository} 上的一条 UPDATE。 ★
 * 这不是"顺手把密级也算一下"——那种歧义的病灶是"谁说了算"，而这里说的人仍然只有领域对象一个 （它给出集合、给出规则），适配器只是把它送进 SQL。
 */
@Component
public class ConversationRepositoryAdapter implements ConversationRepository {

  private final JpaConversationRepository jpa;

  public ConversationRepositoryAdapter(JpaConversationRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Conversation save(Conversation conversation) {
    return jpa.save(conversation);
  }

  /**
   * ★★ 单调抬升会话密级（{@code #108} 的择一 (a)）—— 一条 UPDATE，<strong>不读实体</strong>。
   *
   * <p>★ 两个早退都是<strong>规则本身</strong>的结论，不是第二份口径：
   *
   * <ul>
   *   <li>{@code refsMax == null}（本轮没有引用）⇒ 空集合的 max 是单位元 ⇒ 不变（与 {@link
   *       Conversation#raiseClassification} 同一条）；
   *   <li>{@code lower} 为空（{@code refsMax} 是 {@code PUBLIC}）⇒ 没有任何现值能严格低于它 ⇒ 不可能抬升。 ★ 顺带避开 {@code
   *       in ()} 这种非法 SQL（空集合渲染进 {@code in} 子句是坏形状）。
   * </ul>
   *
   * <p>★ <strong>事务</strong>：{@code @Transactional}（默认 REQUIRED）—— ① 批量 UPDATE 必须在事务里执行； ② REQUIRED
   * 意味着调用方已有事务时<strong>加入它</strong>（{@code 13 §2.2} 要求"与引用集合落库同一个事务"）， 而不是另起一个把那条口径拆开。
   */
  @Override
  @Transactional
  public boolean raiseClassification(UUID id, ClassificationLevel refsMax) {
    if (id == null || refsMax == null) {
      return false;
    }
    List<ClassificationLevel> lower = Conversation.strictlyLowerThan(refsMax);
    if (lower.isEmpty()) {
      return false;
    }
    return jpa.raiseClassification(id, refsMax, lower) > 0;
  }

  /**
   * ★★ 记一条消息落库（{@code message_count + 1} + {@code last_message_at} 前移）。
   *
   * <p>★ 返回值是"<strong>真的记上了</strong>"：会话那一行不在 ⇒ {@code false}。调用点把它当破洞 （见 {@code
   * ChatTurnWriter#prepare}），不静默忽略 —— "消息已落库、计数没跟上"正是 {@code #44} 记的那种 不报错的形状。
   */
  @Override
  @Transactional
  public boolean recordMessage(UUID id) {
    if (id == null) {
      return false;
    }
    return jpa.recordMessage(id) > 0;
  }

  @Override
  public Optional<Conversation> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public List<Conversation> listByUser(UUID userId) {
    return jpa.findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDescCreatedAtDesc(userId);
  }

  /**
   * ★★ 最近一条 {@code (归属人, 应用)} 的会话（{@code 13 §5.2.1}）。
   *
   * <p>★ <strong>复用既有那条查询、在内存里按 {@code assistantId} 收窄</strong>，而不是新加一条 JPQL： 那条 {@code
   * find…OrderByLastMessageAtDescCreatedAtDesc} 的排序与 {@code V32} 的 partial 索引 （{@code where
   * deleted_at is null}）是配套的；另写一条只差一个 {@code where} 的查询，等于让 "最近一条"的排序口径多出第二处实现 ——
   * 而它漂了的症状是"公开页读到的是更旧那条对话"（不报错）。
   *
   * <p>★ {@code assistantId} 为空这一档**单独处理**：{@code null} 在 JPQL 里是 `is null` 而不是 `= null`，
   * 直接用等号会静默匹配不到任何行（通用问答那一档的会话会"每条都新建"）。
   */
  @Override
  public Optional<Conversation> findLatestByUserAndAssistant(UUID userId, UUID assistantId) {
    if (userId == null) {
      return Optional.empty();
    }
    return jpa.findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDescCreatedAtDesc(userId).stream()
        .filter(found -> Objects.equals(found.assistantId(), assistantId))
        .findFirst();
  }
}
