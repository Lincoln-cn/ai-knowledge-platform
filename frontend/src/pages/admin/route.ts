import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'admin',
    name: 'admin',
    component: () => import('./Index.vue'),
    meta: { title: '用户角色', permission: PERM.systemManage }
  }
] satisfies RouteRecordRaw[]
