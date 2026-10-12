package cn.lincoln.aiplatform.assistant.domain;

/**
 * 应用类型（契约 {@code AssistantType}）—— ★ <strong>创建时选定，之后不可更改</strong>（SRS 02 / FR-04）。
 *
 * <p>为什么不可改：改成编排或反之不是"改一个字段"，而是把一个应用换成另一个应用 —— 配置体不同（简单应用在本模块、 编排应用在 {@code workflow}
 * 模块）、可用性判定不同、导出包里带不带流程定义也不同。 允许改会留下一批"类型是 A 而配置体是 B"的存量数据，且<strong>没有任何地方会报错</strong>。
 *
 * <p>取值是 SCREAMING_SNAKE_CASE（{@code 14 §2.1}），与库里 {@code chk_as_type} 的 check 逐字一致。
 */
public enum AssistantType {

  /** 简单应用：角色提示词 + 知识库 + 检索参数。★ 必须有对话模型。 */
  SIMPLE,

  /** 编排应用：画布式流程。★ 没有"应用级模型" —— 模型在各节点里，可以一个流程用多个模型。 */
  WORKFLOW
}
