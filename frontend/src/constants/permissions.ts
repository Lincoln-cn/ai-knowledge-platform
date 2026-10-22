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

  // 管理菜单下的两块基础数据（07 §十 / 08 §十）
  orgManage: 'identity:org:manage',
  dictManage: 'platform:dict:manage'
} as const

export type PermissionCode = (typeof PERM)[keyof typeof PERM]
