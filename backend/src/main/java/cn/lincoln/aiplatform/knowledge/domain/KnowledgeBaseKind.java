package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 知识库的<strong>种类</strong>（{@code 13 §11.2} 的 {@code kb_knowledge_base.kind}）。
 *
 * <p>★ 这一列存在的唯一理由：把"三域"里的**个人库**与共用库分开，而又**不新增一套平行的表** —— 个人库复用库这条链（向量模型、ceiling、分段、向量表）。
 *
 * <table>
 *   <tr><th>取值</th><th>谁维护</th><th>出现在哪</th></tr>
 *   <tr><td>{@link #SHARED}</td><td>有 {@code knowledge:manage} 的人（创建者）</td>
 *       <td>知识库列表 / 绑定面 / 分享面（既有的三条链路）</td></tr>
 *   <tr><td>{@link #PERSONAL}</td><td>每人<strong>隐式</strong>一个（首次上传时惰性创建）</td>
 *       <td>★ <strong>只</strong>走「我的资料」这一个入口：不出现在列表 / 选择器 / 分享对话框里，
 *           检索时只能通过「加上我的资料」开关<strong>隐式</strong>进入（{@code 13 §11.2}）</td></tr>
 * </table>
 *
 * <p>★★ 为什么"个人库不能长成一张普通的库"：它一旦落成"就是一张库、只是 owner 是你"，就会顺着既有的 列表 / 绑定 / 分享三条链路<strong>自动</strong>长出去
 * —— 而那三条链路<strong>各自都不会报错</strong>。
 *
 * <p>★ 每人至多一行 {@code PERSONAL}（库层的 {@code uq_kb_personal_owner}）：{@code 13 §11.3} 第二支的绑定值是
 * 一个<strong>单值</strong>，一个人有两行时"哪一行是我的个人库"没有定义，而取错那一行不会报错， 只是"有些内容检索不到"。
 *
 * <p>★ 惰性创建与"不出现在列表里"这两件事**本批不做**（{@code 13 §11.6} 第 4 项：随第 17 周）—— 本类今天只被建库路径用到，且建出来的一律是 {@link
 * #SHARED}。
 */
public enum KnowledgeBaseKind {

  /** 共用知识库（默认；今天唯一存在的种类）。 */
  SHARED,

  /** 个人库（每人隐式一个；不出现在任何"知识库"列表里）。 */
  PERSONAL
}
