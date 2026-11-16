import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'guard-hits',
    name: 'guard-hits',
    component: () => import('./Index.vue'),
    meta: { title: '拦截记录', permission: PERM.guardManage }
  }
] satisfies RouteRecordRaw[]
