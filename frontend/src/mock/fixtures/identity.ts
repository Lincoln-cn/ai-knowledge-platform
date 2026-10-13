import { PERM } from '@/constants/permissions'
import type { PermissionItem, Role, User } from '@/api/identity'
import type { ClassificationLevel } from '@/api/types'

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
  { code: PERM.conversationView, label: '使用对话', module: '对话' },
  { code: PERM.conversationShare, label: '分享自己的对话', module: '对话' },
  // 现阶段无前端消费方：没有任何页面/接口用它做门禁，先定下点位便于后端对齐
  { code: PERM.conversationManage, label: '管理他人会话', module: '对话' },
  { code: PERM.modelManage, label: '模型管理', module: '模型' },
  { code: PERM.dashboardView, label: '查看运营看板', module: '运营' },
  { code: PERM.feedbackHandle, label: '处理反馈', module: '运营' },
  { code: PERM.systemManage, label: '账号与权限', module: '系统' },
  { code: PERM.orgManage, label: '组织机构', module: '系统' },
  { code: PERM.dictManage, label: '基础数据', module: '系统' }
]

const all = permissions.map((p) => p.code)

export const roles: Role[] = [
  // 数据范围（07 §3.2）：ADMIN / KB_OPERATOR / APP_CONFIG 是全平台口径，USER 是「共享档」
  // ★ clearance（`Role.maxClassification`，13 §六）：与 dataScope **正交** —— 前者管"能看多密"，
  //   后者管"能看谁建的"。有了它，分享冻结（13 §5.2）才有值可取：冻结值 = 分享者当时的 clearance。
  {
    id: 'r1',
    name: '管理员',
    code: 'ADMIN',
    memberCount: 1,
    permissions: all,
    dataScope: 'ALL',
    maxClassification: 'CONFIDENTIAL'
  },
  {
    id: 'r2',
    name: '知识运营',
    code: 'KB_OPERATOR',
    memberCount: 3,
    dataScope: 'ALL',
    maxClassification: 'SECRET',
    permissions: [
      PERM.knowledgeView,
      PERM.knowledgeCreate,
      PERM.knowledgeManage,
      PERM.documentUpload,
      PERM.segmentEdit,
      PERM.hitTest,
      PERM.assistantView,
      PERM.conversationView,
      PERM.conversationShare,
      PERM.dashboardView,
      PERM.feedbackHandle
    ]
  },
  {
    id: 'r3',
    name: '应用配置',
    code: 'APP_CONFIG',
    memberCount: 2,
    dataScope: 'ALL',
    maxClassification: 'INTERNAL',
    permissions: [
      PERM.knowledgeView,
      PERM.hitTest,
      PERM.assistantView,
      PERM.assistantEdit,
      PERM.assistantPublish,
      PERM.conversationView,
      PERM.conversationShare,
      PERM.dashboardView
    ]
  },
  {
    id: 'r4',
    name: '普通用户',
    code: 'USER',
    memberCount: 68,
    // 只看得见「被共享给自己的」+ 公开的（07 §4.2 知识库/应用两行）
    dataScope: 'SHARED',
    maxClassification: 'INTERNAL',
    permissions: [
      PERM.knowledgeView,
      PERM.assistantView,
      PERM.conversationView,
      PERM.conversationShare
    ]
  }
]

/**
 * 用户夹具。`mustChangePassword` 是登录契约的一等字段（`LoginResult`）：
 * 用系统默认初始口令创建的用户置 true，改过密码后置 false。
 * 现有四个账号都已改过，所以都是 false —— 否则回归脚本一登录就被弹去改密页。
 */
export const users: Array<User & { password: string; mustChangePassword: boolean }> = [
  {
    id: 'u1',
    username: 'admin',
    password: 'admin',
    displayName: '管理员',
    roleId: 'r1',
    roleName: '管理员',
    status: 'ACTIVE',
    lastLoginAt: '2026-02-13 08:30',
    mustChangePassword: false
  },
  {
    id: 'u2',
    username: 'zhangyun',
    password: '123456',
    displayName: '张云',
    roleId: 'r2',
    roleName: '知识运营',
    status: 'ACTIVE',
    lastLoginAt: '2026-02-13 09:12',
    mustChangePassword: false
  },
  {
    id: 'u3',
    username: 'liwei',
    password: '123456',
    displayName: '李伟',
    roleId: 'r3',
    roleName: '应用配置',
    status: 'ACTIVE',
    lastLoginAt: '2026-02-12 17:44',
    mustChangePassword: false
  },
  {
    id: 'u4',
    username: 'chenjun',
    password: '123456',
    displayName: '陈军',
    roleId: 'r4',
    roleName: '普通用户',
    status: 'ACTIVE',
    lastLoginAt: '2026-02-13 10:05',
    mustChangePassword: false
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

/**
 * 当前登录用户的密级上限（clearance）。**分享创建时冻结的就是这个值**（`13 §5.2` ①）。
 *
 * 为什么放在夹具层：真后端由 `Role.maxClassification` 随令牌下发，mock 侧必须**同源**推导，
 * 否则"分享冻结"在 mock 与真栈下会给出两个不同的值（`#41`/`#57` 那一族的分歧都是从这儿长出来的）。
 * 找不到用户/角色时给 **`INTERNAL`**（不是 `CONFIDENTIAL`）：缺省必须**收窄**，放开容易收回难。
 */
export const clearanceFromToken = (token: string): ClassificationLevel => {
  const user = userFromToken(token)
  const role = roles.find((r) => r.id === user?.roleId)
  return role?.maxClassification ?? 'INTERNAL'
}
