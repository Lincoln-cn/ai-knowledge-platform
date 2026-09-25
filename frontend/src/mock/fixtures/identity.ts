import { PERM } from '@/constants/permissions'
import type { PermissionItem, Role, User } from '@/api/identity'

/** 权限点全量清单。新增功能时这里多一项，角色勾选界面自动可见 */
export const permissions: PermissionItem[] = [
  { code: PERM.knowledgeView, label: '查看知识库', module: '知识' },
  { code: PERM.knowledgeCreate, label: '创建知识库', module: '知识' },
  { code: PERM.knowledgeManage, label: '编辑/删除知识库', module: '知识' },
  { code: PERM.documentUpload, label: '上传与加工文档', module: '知识' },
  { code: PERM.segmentEdit, label: '治理分段', module: '知识' },
  { code: PERM.hitTest, label: '命中测试', module: '检索' },
  { code: PERM.assistantView, label: '查看应用', module: '应用' },
  { code: PERM.assistantEdit, label: '配置应用', module: '应用' },
  { code: PERM.assistantPublish, label: '发布应用', module: '应用' },
  { code: PERM.modelManage, label: '模型管理', module: '模型' },
  { code: PERM.dashboardView, label: '查看运营看板', module: '运营' },
  { code: PERM.feedbackHandle, label: '处理反馈', module: '运营' },
  { code: PERM.systemManage, label: '账号与权限', module: '系统' }
]

const all = permissions.map((p) => p.code)

export const roles: Role[] = [
  // 数据范围（07 §3.2）：ADMIN / KB_OPERATOR / APP_CONFIG 是全平台口径，USER 是「共享档」
  { id: 'r1', name: '管理员', code: 'ADMIN', memberCount: 1, permissions: all, dataScope: 'all' },
  {
    id: 'r2',
    name: '知识运营',
    code: 'KB_OPERATOR',
    memberCount: 3,
    dataScope: 'all',
    permissions: [
      PERM.knowledgeView,
      PERM.knowledgeCreate,
      PERM.knowledgeManage,
      PERM.documentUpload,
      PERM.segmentEdit,
      PERM.hitTest,
      PERM.assistantView,
      PERM.dashboardView,
      PERM.feedbackHandle
    ]
  },
  {
    id: 'r3',
    name: '应用配置',
    code: 'APP_CONFIG',
    memberCount: 2,
    dataScope: 'all',
    permissions: [
      PERM.knowledgeView,
      PERM.hitTest,
      PERM.assistantView,
      PERM.assistantEdit,
      PERM.assistantPublish,
      PERM.dashboardView
    ]
  },
  {
    id: 'r4',
    name: '普通用户',
    code: 'USER',
    memberCount: 68,
    // 只看得见「被共享给自己的」+ 公开的（07 §4.2 知识库/应用两行）
    dataScope: 'shared',
    permissions: [PERM.knowledgeView, PERM.assistantView]
  }
]

export const users: Array<User & { password: string }> = [
  {
    id: 'u1',
    username: 'admin',
    password: 'admin',
    displayName: '管理员',
    roleId: 'r1',
    roleName: '管理员',
    status: '正常',
    lastLoginAt: '2026-02-13 08:30'
  },
  {
    id: 'u2',
    username: 'zhangyun',
    password: '123456',
    displayName: '张云',
    roleId: 'r2',
    roleName: '知识运营',
    status: '正常',
    lastLoginAt: '2026-02-13 09:12'
  },
  {
    id: 'u3',
    username: 'liwei',
    password: '123456',
    displayName: '李伟',
    roleId: 'r3',
    roleName: '应用配置',
    status: '正常',
    lastLoginAt: '2026-02-12 17:44'
  },
  {
    id: 'u4',
    username: 'chenjun',
    password: '123456',
    displayName: '陈军',
    roleId: 'r4',
    roleName: '普通用户',
    status: '正常',
    lastLoginAt: '2026-02-13 10:05'
  }
]

/**
 * 令牌形如 `mock_access_u1` —— 与真实 JWT 一样「拿到令牌就能确定是谁」。
 *
 * 放在夹具层而不是各 handler 里：数据权限判定（`mock/policy.ts`）与登录态都依赖
 * "令牌 → 用户"这一条约定，两处各写一遍迟早会漂移。
 * 不使用模块级变量记登录态：那样一刷新页面登录态就丢了，与真实后端行为不一致。
 */
export const userFromToken = (token: string) =>
  token ? users.find((u) => u.id === token.replace(/^mock_access_/, '')) : undefined
