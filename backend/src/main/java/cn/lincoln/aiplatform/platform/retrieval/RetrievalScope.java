package cn.lincoln.aiplatform.platform.retrieval;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 本次检索的<strong>域</strong>（{@code 13 §11.3} 三支的绑定值）—— SPI 值对象，放 {@code platform}、由 {@code knowledge}
 * 侧解析出具体值。
 *
 * <h3>它是什么（三支，一支不多一支不少）</h3>
 *
 * <pre>
 *   域(s) = ( s.scope_kind = 'KB'           and s.scope_id in (:sharedKbIds) )      ← 共用库（可见性已判过）
 *        or ( s.scope_kind = 'KB'           and s.scope_id = :personalKbId )        ← 我的个人库（★ 隐式档）
 *        or ( s.scope_kind = 'CONVERSATION' and s.scope_id = :conversationId
 *                                           and s.owner_id = :ownerId )             ← 会话临时（★ 本人 + 本会话）
 * </pre>
 *
 * <p>★ 它是{@code 13 §11.3} 那段谓词的<strong>唯一绑定值载体</strong>：谓词的形状只有一处（{@code
 * JdbcSegmentSearchQuery}），值的形状只有这里一处 —— 两处都不许再各自拼一份。
 *
 * <h3>★ 为什么三支必须在一个对象里（而不是三个方法/三个参数）</h3>
 *
 * <p>分开传 = 调用方可以只给其中两支，而"漏了会话域"的症状是<strong>会话附件谁都搜不到</strong>（判据 1 会红）， "漏了
 * owner_id"的症状是<strong>别人的会话附件我搜得到</strong>（判据 2 会红）—— 两者都不报错。 合成一个对象之后，"少一支"必须显式写成一个 {@code
 * null}，而 {@code null} 在下面有校验。
 *
 * <h3>★ 为什么 personalKbId / conversationId 用 {@code null} 表达"这一档不开"</h3>
 *
 * <p>它们是<strong>开与不开</strong>两种状态（{@code 13 §11.4}：个人库默认不参与，给「加上我的资料」开关；命中测试 没有会话）。用 {@code null}
 * 而不是空列表，是因为 {@code scope_id in ()} 在 SQL 里是一次**语法错误** —— "没开"必须变成一条恒假的支，而不是一条会炸的 SQL。
 *
 * <p>★ <strong>公开面（{@code asSubject}）必须传 {@code includePersonal = false}</strong>：{@code 13 §11.2}
 * ③ 写明"公开面的域**只有**共用库 + 该会话的会话临时" —— 把个人库并进公开面，等于让访客按"作者的文档"检索， 那正是 {@code #31}
 * 点名的那个<strong>静默</strong>越权。
 *
 * @param sharedKbIds ★ 共用库那一支的库 id（**已经过可见性判定**，见 {@code SegmentSearchPort#visibleKbIds}）。 ★
 *     空列表是合法的（"这个会话没有绑定共用库，但有会话临时附件"），不是"没开"。
 * @param personalKbId 我的个人库 id（{@code kb_knowledge_base.kind = 'PERSONAL' and owner_id = :me}）；
 *     {@code null} = 隐式档关（{@code 13 §11.4} 的默认）
 * @param conversationId 本次会话 id；{@code null} = 不在会话域里检索（命中测试 / 管理面）
 * @param ownerId ★ 会话域的**传输者**判据（{@code owner_id = :ownerId}）。它由{@code 解析}那一侧从当前范围取，
 *     <strong>不由调用方随意传</strong> —— 传成别人就等于把别人的会话附件算进我的域
 */
public record RetrievalScope(
    List<UUID> sharedKbIds, UUID personalKbId, UUID conversationId, UUID ownerId) {

  /**
   * 规范化 + 两条不变量。
   *
   * <p>★★ {@code conversationId != null ⇒ ownerId != null}：第三支是 {@code … and owner_id = :ownerId}，
   * {@code ownerId} 为空 ⇒ 那一支<strong>恒假</strong> ⇒ 会话附件对所有人（包括传它的人）都检索不到。
   * 那是"少看见"那一类静默失效，所以它在**构造时**就报错，而不是等检索返空。
   */
  public RetrievalScope {
    sharedKbIds = sharedKbIds == null ? List.of() : List.copyOf(sharedKbIds);
    if (conversationId != null && ownerId == null) {
      throw new IllegalArgumentException(
          "会话域的检索必须带 ownerId（13 §11.3 第三支是 owner_id = :ownerId）："
              + "缺了它那一支恒假，会话附件谁都检索不到 —— 这是一次静默失效，不是一次空结果");
    }
  }

  /**
   * 只带共用库域（命中测试 / 应用绑定 / 公开面预览用）。
   *
   * <p>★ 它等价于"个人库隐式档关 + 不在任何会话里"，与 {@code 13 §11.4} 的默认一致。
   */
  public static RetrievalScope shared(Collection<UUID> kbIds) {
    return new RetrievalScope(kbIds == null ? List.of() : List.copyOf(kbIds), null, null, null);
  }

  /**
   * {@link #shared(Collection)} 的构造器形态 —— 给既有的 {@code new TextQuery(问题, kbIds, limit)}
   * 那一批调用点用（它们表达的就是"只有共用库域"）。
   */
  public RetrievalScope(Collection<UUID> kbIds) {
    this(kbIds == null ? List.of() : List.copyOf(kbIds), null, null, null);
  }

  /** 三个域全关 ⇒ 这次检索一条都不该返回（调用方据此走"没有可检索的范围"那条分支）。 */
  public boolean isEmpty() {
    return sharedKbIds.isEmpty() && personalKbId == null && conversationId == null;
  }

  /**
   * 向量模型查询要用的库 id：域里**有库行**的那部分（共用库 + 个人库）。
   *
   * <p>★ 会话域<strong>故意不在里面</strong>：它没有 {@code kb_knowledge_base} 行，向量模型继承**平台默认** （{@code 13
   * §11.2}）—— 那是另一条解析路径，不能靠 {@code embeddingModelByKb} 假装有。
   */
  public List<UUID> kbIdsWithModelRows() {
    LinkedHashSet<UUID> all = new LinkedHashSet<>(sharedKbIds);
    if (personalKbId != null) {
      all.add(personalKbId);
    }
    return List.copyOf(all);
  }

  /**
   * 换一组共用库（向量路按向量模型分组，每组各查各的表），其余两域照搬。
   *
   * <p>★ 为什么个人库要**只留在它自己那个组**里：个人库的向量模型就是它自己那一行声明的模型 （{@code 13 §11.2}：继承平台默认，但落在一个真实的库行上）⇒
   * 它天然只属于一个模型组。 若每个组都带上它，同一个分段会在多路里各出现一次，而 RRF 是"名次求和" ⇒ 它的分会被**重复累加**、 名次虚高 —— 那不是任何一条产品承诺。
   */
  public RetrievalScope withinShared(Collection<UUID> kbIds) {
    List<UUID> group = kbIds == null ? List.of() : List.copyOf(kbIds);
    UUID personal = personalKbId != null && group.contains(personalKbId) ? personalKbId : null;
    return new RetrievalScope(group, personal, conversationId, ownerId);
  }
}
