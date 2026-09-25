import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'models',
    name: 'models',
    component: () => import('./Index.vue'),
    meta: { title: '模型管理', permission: PERM.modelManage }
  }
] satisfies RouteRecordRaw[]
