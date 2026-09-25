import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'admin',
    name: 'admin',
    component: () => import('./Index.vue'),
    meta: { title: '账号与权限', permission: PERM.systemManage }
  }
] satisfies RouteRecordRaw[]
