import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/**
 * 问答会话 —— 全平台唯一的会话页。
 * 通用问答与应用问答共用本页，差异只在顶部应用信息区是否展示。
 */
export default [
  {
    path: 'conversation',
    name: 'conversation',
    component: () => import('./Index.vue'),
    // 声明 `conversation:view`：问答是本平台的终端用户入口，
    // 四个角色都被授予了该点（见 mock/fixtures/identity.ts）——
    // 门禁存在但不会把任何角色挡在门外。
    meta: { title: '对话', permission: PERM.conversationView }
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
  },
  {
    // 会话分享的只读页（#187）：同一份记录，不是可继续提问的凭证。
    // 路由形状必须与后端 `ConversationShareView.PUBLIC_PATH_PREFIX = "/#/share/"` 一致。
    path: '/share/:token',
    name: 'public-share',
    component: () => import('./public/Share.vue'),
    meta: { title: '分享的对话', public: true }
  }
]
