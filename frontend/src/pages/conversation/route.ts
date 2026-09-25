import type { RouteRecordRaw } from 'vue-router'

/**
 * 问答会话 —— 全平台唯一的会话页。
 * 通用问答与应用问答共用本页，差异只在顶部应用信息区是否展示。
 */
export default [
  {
    path: 'conversation',
    name: 'conversation',
    component: () => import('./Index.vue'),
    // 刻意**不声明** permission：问答是本平台的终端用户入口，
    // 四个角色里权限最少的「普通用户」也只有 knowledge:view + assistant:view，
    // 若给它挂任何一个权限点，就等于把他们挡在门外 —— 那是产品事故，不是门禁。
    meta: { title: '对话' }
  }
] satisfies RouteRecordRaw[]

/**
 * 沉浸布局路由：免登录公开页，**无侧栏、不挂在 shell 下**。
 *
 * `src/router/routes.ts` 当前只自动发现本文件的 default 导出（shell 子路由），
 * 无法在本文件内把自己挂到顶层 —— 需要验证方在 routes.ts 的顶层 children 外
 * 增加：`...immersiveRoutes`（见交付报告「需要验证方协助的事项」）。
 */
export const immersiveRoutes: RouteRecordRaw[] = [
  {
    path: '/public/:token',
    name: 'public-chat',
    component: () => import('./public/Index.vue'),
    meta: { title: '公开对话', public: true }
  }
]
