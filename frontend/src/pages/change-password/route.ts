import type { RouteRecordRaw } from 'vue-router'

export default [
  {
    path: 'change-password',
    name: 'change-password',
    component: () => import('./Index.vue'),
    meta: { title: '修改密码' }
  }
] satisfies RouteRecordRaw[]
