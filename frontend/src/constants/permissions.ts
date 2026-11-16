/**
 * 权限点常量。
 *
 * 与后端保持一致：权限点是**代码常量**，启动时由后端同步到前端，
 * 不依赖有人记得去数据库配一条。
 * 命名规则 `<资源>:<操作>`（03:568），前端不用字符串字面量，一律引用这里。
 */
export const PERM = {
  knowledgeView: 'knowledge:view',
  knowledgeCreate: 'knowledge:create',
  knowledgeManage: 'knowledge:manage',
  documentUpload: 'knowledge:document:upload',
  segmentEdit: 'knowledge:segment:edit',
  /** 问答对的增删改（FR-03-12）。★ 不借用 segmentEdit：分段是自动切的、问答对是人工标的 */
  qaEdit: 'knowledge:qa:edit',
  hitTest: 'knowledge:hittest',

  assistantView: 'assistant:view',
  assistantEdit: 'assistant:edit',
  assistantPublish: 'assistant:publish',

  conversationView: 'conversation:view',
  conversationShare: 'conversation:share',
  conversationManage: 'conversation:manage',

  modelManage: 'model:manage',

  dashboardView: 'dashboard:view',
  feedbackHandle: 'feedback:handle',

  /**
   * 配置「对话设置」（默认模型 / 初始提示词 / 召回开关与阈值 / 流式超时）。
   *
   * ★ 与 `systemManage` 分开：后者是「整个系统管理」的开关，
   * 而「让人调召回阈值」比它窄得多 —— 复用会把权限切成两半（全给或全不给）。
   * ★ 默认只有 ADMIN 拥有，其它角色需在角色管理里显式勾选。
   */
  chatSettings: 'conversation:settings',
  systemManage: 'system:manage',

  /** 审计记录查看（#276）。★ 与 `systemManage` 分开：审计员能看全部留痕，但不该能改角色与字典。 */
  auditView: 'audit:view',

  // 管理菜单下的两块基础数据（07 §十 / 08 §十）
  orgManage: 'identity:org:manage',
  dictManage: 'platform:dict:manage',
  /** 办理投诉举报工单（#83 第十五条）。★ 与 `feedbackHandle` 分开：投诉举报是留痕举证工单，不是内容质量反馈。 */
  complaintHandle: 'complaint:handle',
  /** 合规登记（#83）：算法备案号/安全评估结论登记位 + 投诉举报反馈时限。★ 与 `systemManage` 分开。 */
  complianceManage: 'platform:compliance:manage',
  /**
   * 检索引擎运行参数（查询理解开关 / 重排窗口 / 前向批大小 / listwise）。
   *
   * ★ 与 `systemManage` 分开：改一次影响**所有人**的检索延迟与 B1/B3 口径（关重试省 1.4s、掉 1.1 点），
   * 与账号管理不是一类。
   */
  retrievalManage: 'platform:retrieval:manage',
  /**
   * 安全围栏（分类型的敏感词与敏感问题集）。
   *
   * ★ 与 `chatSettings` 分开：通用问答那条链路**可以整条关掉**，围栏必须常在 ——
   * 两者的爆炸半径不同（前者只影响一条链路，后者对所有链路的下一次请求生效），
   * 合用一个权限点会让「给了通用问答配置权」顺带得到「放宽安全策略」。
   * ★ 读也守它：规则表本身就是一份"可绕过的清单"，能读就等于知道该怎么说才不被拦。
   */
  guardManage: 'platform:guard:manage'
} as const

export type PermissionCode = (typeof PERM)[keyof typeof PERM]
