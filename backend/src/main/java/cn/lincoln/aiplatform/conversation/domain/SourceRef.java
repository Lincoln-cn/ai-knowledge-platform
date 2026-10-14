package cn.lincoln.aiplatform.conversation.domain;

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
    String kbId) {

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
