import type { RouteRecordRaw } from 'vue-router'

/**
 * 投诉举报（#83 第十五条）。★ 不设 meta.permission：提交入口要便捷（登录即可），
 * 办理权（complaint:handle）由页面内按 session.can 控制按钮可见性。
 */
export default [
  {
    path: 'complaints',
    name: 'complaints',
    component: () => import('./Index.vue'),
    meta: { title: '投诉举报' }
  }
] satisfies RouteRecordRaw[]
