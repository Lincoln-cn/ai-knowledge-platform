package cn.lincoln.aiplatform.platform.retrieval;

/**
 * 引用来源的<strong>显示名</strong>里那几个"不是任何一行数据"的值 —— ★ 本批只有一条，但它必须只有一处。
 *
 * <h3>★★ 为什么这条常量放在 {@code platform.retrieval}，而不在其它两个候选处</h3>
 *
 * <p>会话附件的那条引用要一个 {@code kbName}，而它<strong>没有任何一行库数据</strong>：{@code kb_segment.kb_id} 为空 （{@code
 * 13 §11.2}），左连接下 {@code kb_knowledge_base.name} 是 {@code NULL}。三个候选落点各自的毛病：
 *
 * <ol>
 *   <li><strong>放在 {@code JdbcSegmentSearchQuery.load}（SQL → {@link SegmentSearchPort.Card}
 *       那一处）</strong>： 把契约那条 "必填"<strong>在数据层</strong>补成一个假值。于是 {@link
 *       SegmentSearchPort.Card#kbId()} 与 {@link SegmentSearchPort.Card#kbName()}
 *       不再同源（一个来自行、一个来自约定），而 {@code Card} 的 javadoc 明确写着两个分量都是"来自<strong>真实的</strong>行"。★
 *       更要命的是：它会让"这一行到底有没有库"这件事在 <strong>端口出口处就消失</strong>，下游再也分不出"会话附件"与"名字为『会话附件』的知识库"。
 *   <li><strong>放在 {@code conversation} 那一侧（{@code ChatService}）</strong>：{@code conversation}
 *       是消费方， 而这条值的语义面（"哪个域没有库行"）属于 {@code platform.retrieval} 的词汇表。同一个常量留在消费方， 下一个消费方（会话导出 /
 *       应用试跑）就会各自再写一份字面量。
 *   <li><strong>放在这里</strong>：它就是 {@link SegmentSearchPort.Card} 那条 {@code NULL}
 *       的<strong>解码表</strong>， 与产出 {@code NULL} 的那一处（{@code knowledge} 侧的实现）和消费的那一处（{@code
 *       conversation} 侧）处在同一条 SPI 的两端 —— 两边都能只 import 这一个名字，谁都不必自己写字面量。
 * </ol>
 *
 * <h3>★ 为什么是"约定常量"而不是"拿会话标题顶上"</h3>
 *
 * <p>会话标题是<strong>用户可改的一行数据</strong>：改标题就会让引用卡片的来源标签跟着变，而"这条引用来自哪一类来源" 是一件不该随标题漂移的事。★
 * 前端已经把标签渲染成{@code 来源：<kbName>}（{@code RefPanel.vue}）——
 * <strong>标签的形状归前端、值归后端</strong>，所以后端给出的必须是这个稳定的字。
 */
public final class SourceLabels {

  /**
   * ★★ 会话临时附件的 {@code kbName}（产品口径 2026-10-15 已裁定）：**逐字**是「会话附件」。
   *
   * <p>左连接下它的 {@code kb_name} 是 {@code NULL}，而契约的 {@code SourceRef.kbName} <strong>必填</strong> ⇒
   * 不给这条约定，会话域的引用会被"逐条跳过"<strong>整批丢掉</strong>（有 WARN、界面上一条都没有 ——
   * 那正是本项目头号病"静默失效"的形状：看起来像"这个会话没有相关附件"）。
   *
   * <p>★ 只有 {@code kb_id} 为空的卡片才该拿到它：给<strong>所有</strong>引用都补一个非空库名会让
   * "库名为空"这条数据坏味<strong>消失</strong>（而它今天靠"逐条跳过 + 计数 WARN"暴露）。判据落在何处由 {@code ChatService#retrieve}
   * 决定，本类只持有这个字。
   *
   * <p>★ 这个<strong>字面量</strong>在 {@code src/main/java} 里只有这一处，由 {@code
   * SourceLabelsTest#theLabelLiteralLivesInExactlyOneMainSourceFile()} 钉着（第二处 = 两处真相，
   * 而两处真相互相分叉时不会报错）。★ 反向也成立：谁把它改成别的字，装配点的行为断言会红。
   */
  public static final String CONVERSATION_ATTACHMENT = "会话附件";

  private SourceLabels() {}
}
