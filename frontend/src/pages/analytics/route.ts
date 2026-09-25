import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'analytics',
    name: 'analytics',
    component: () => import('./Index.vue'),
    meta: { title: '运营看板', permission: PERM.dashboardView }
  }
] satisfies RouteRecordRaw[]
