import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'audit',
    name: 'audit',
    component: () => import('./Index.vue'),
    meta: { title: '审计记录', permission: PERM.systemManage }
  }
] satisfies RouteRecordRaw[]
