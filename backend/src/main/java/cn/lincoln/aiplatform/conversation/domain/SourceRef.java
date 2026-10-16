package cn.lincoln.aiplatform.conversation.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;

/**
 * 一条引用来源（SSE 契约里的 {@code refs} 事件元素 · {@code 03 §1.2}）。
 *
 * <h3>★★ 这是"逐字对齐契约"的类型 —— 改它之前先读这一段</h3>
 *
 * <p>{@code frontend/src/api/stream-types.ts} 的 {@code SourceRef} 是<strong>冻结的权威</strong>（{@code 10
 * §8.3}： "SSE 一个字节都不用改"）。所以本 record 的<strong>分量名与 TS 接口的字段名逐字相同</strong>（{@code docName} 不是 {@code
 * documentName}、{@code kbName} 不是 {@code baseName}）。字段名一旦对不上，前端 {@code JSON.parse} 出来的对象就会有字段是
 * {@code undefined} —— 那是<strong>静默</strong>的：页面照样渲染，只是引用栏空着、深链跳不动，没有任何地方报错。 {@code
 * StreamContractCrossCheckTest} 会在两侧任一侧改字段名时变红。
 *
 * <p>★ <strong>契约里没有的字段不许自创</strong>：多一个字段并不会让前端报错，但它会让"契约里到底有什么"这件事变成两处口径。
 *
 * <h3>★★ 第 11 个分量「密级」为什么在生产端是<strong>必填</strong>（{@code #27}，2026-10-16 拍板）</h3>
 *
 * <p>它<strong>只服务一件事</strong>：会话累计密级 = {@code max(历史引用的 classification)}（{@code 13 §2.2}：
 * "一旦引用过机密，整个会话就是机密的"）。载体 = {@code cv_conversation.max_classification}，写入口 = {@code
 * ConversationRepository#raiseClassification}（只升不降，{@code #108}），而<strong>调用点</strong>就是这里 ——
 * 引用集合自带密级之后，"本轮引用集合的最高密级"才到得了落库那一刻。
 *
 * <p>★★ <strong>契约写成可选（{@code ?}），这里不兜底</strong> —— 与 {@code title} / {@code docName} / {@code
 * kbName} 是同一落法（契约允许缺、生产端不许缺）。理由是可核对的一条：兜底成 {@code PUBLIC} 会让会话水位<strong>算低</strong> ⇒
 * <strong>静默降密</strong>（会话从机密掉回公开，不报错、不留痕）。而它与会话域那条"{@code kbId} 缺失有语义"不同 ——
 * 密级的缺失<strong>没有任何语义</strong>：{@code kb_segment.classification} 是 {@code V22} 的非空列， 装配点的素材（{@code
 * SegmentSearchPort.Card}）没有"取不到"这一档。
 *
 * <p>★ 因此这里<strong>不做</strong>{@code blankToNull} 那种规范化（密级不是字符串）： {@code null} ⇒ 立刻抛，理由与 {@code
 * index < 1} 那条完全相同。
 *
 * <h3>★★ 一处「设计册写了、契约里没有」的差异（本批按契约实现，已登记待裁）</h3>
 *
 * <p>{@code 10 §8.2} 写着要给 {@code SourceRef} 加三个<strong>可选</strong>字段 —— {@code
 * subQuestion}（属于哪个子问题）、 {@code round}（第几轮检索）、{@code matchedQuery}（哪条查询词命中），并称它们是判据 4「可解释」的载体。
 * 但<strong>契约文件里没有这三个字段</strong>（{@code stream-types.ts:105-120} 只有 10 个字段；仓库里唯一的 {@code
 * subQuestion} 命中是 {@code api/retrieval.ts} 的另一个类型 {@code subQuestionCount}）。
 *
 * <p>⇒ 本批<strong>不加</strong>，两条理由：① 契约是"一个字节都不用改"的那个权威，{@code 10 §8.2} 是设计意图； ②
 * 后端单方面多出三个字段，前端不读也照常工作 ⇒ 它会安静地活很久 —— 既没有前端消费点，也没有漂移检测会红，
 * 而"契约里应该有它"这件事就没有读数了。<strong>这属于"契约要不要补"这个决定</strong>，本批把它写成问题而不是自己拍。
 *
 * <h3>字段口径（出处逐条）</h3>
 *
 * <ul>
 *   <li>{@code index} —— 正文里 {@code [N]} 的编号，<strong>从 1 连续递增</strong>。它由生成链路的上下文组装决定（{@code 03
 *       机制一}）， 本批只承载它、不生成它。
 *   <li>{@code rank} / {@code full} / {@code segmentId} / {@code documentId} / {@code kbId} ——
 *       契约里的可选字段，缺省 {@code null}。后三个是"跳转到分段管理"的深链载体（{@code #91} 记着"深链是静默 no-op"那条账）。
 * </ul>
 *
 * <p>★ 本 record <strong>不做</strong>"引用排序"或"编号重排"：{@code index} 是上游（检索与上下文组装）的口径，
 * 在这里再排一次就会多出第二个真相源。本批只保证"这一整批引用先于第一个 {@code delta}"（见 {@code StreamAssembler}）。
 *
 * @param index 正文里 {@code [N]} 的编号，从 1 连续递增
 * @param title 分段标题
 * @param docName 文档名
 * @param kbName 知识库名
 * @param score 检索得分
 * @param rank 排名（可空）
 * @param full 完整内容（可空）
 * @param segmentId 分段 id —— 深链用（可空）
 * @param documentId 所属文档 id —— 深链用（可空）
 * @param kbId 所属知识库 id —— 深链用（可空）
 * @param classification ★ 这条引用（分段）的密级 —— <strong>会话累计密级的唯一来源</strong>（{@code #27}）
 */
public record SourceRef(
    int index,
    String title,
    String docName,
    String kbName,
    double score,
    Integer rank,
    String full,
    String segmentId,
    String documentId,
    String kbId,
    ClassificationLevel classification) {

  /**
   * 规范化可选字段：契约里这些字段是"要么有要么没有"，不是"空串"。
   *
   * <p>★ 但<strong>必填字段不做这种兜底</strong>：{@code index < 1} 或必填串为空 ⇒ 立刻抛。理由与 {@code
   * Conversation#raiseClassification} 那条"非法输入要报错、状态没变不要报错"是同一条 —— 一条 {@code index = 0}
   * 的引用在正文里永远匹配不到 {@code [0]}，它会安静地渲染成一个点不动的引用。
   */
  public SourceRef {
    if (index < 1) {
      throw new IllegalArgumentException("引用编号 index 必须从 1 起（契约口径：正文里 [N] 从 1 连续递增），实际为 " + index);
    }
    if (classification == null) {
      throw new IllegalArgumentException(
          "引用缺少密级（classification）—— 它是会话累计密级的唯一来源，兜底成 PUBLIC 会让会话水位算低（静默降密）。"
              + "素材见 SegmentSearchPort.Card#classification（kb_segment.classification 是 V22 的非空列）");
    }
    title = requireText(title, "title");
    docName = requireText(docName, "docName");
    kbName = requireText(kbName, "kbName");
    full = blankToNull(full);
    segmentId = blankToNull(segmentId);
    documentId = blankToNull(documentId);
    kbId = blankToNull(kbId);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("引用的必填字段 " + field + " 不能为空");
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
