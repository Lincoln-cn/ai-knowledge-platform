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

  systemManage: 'system:manage'
} as const

export type PermissionCode = (typeof PERM)[keyof typeof PERM]
