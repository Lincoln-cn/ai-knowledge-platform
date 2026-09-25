import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'feedback',
    name: 'feedback',
    component: () => import('./Index.vue'),
    meta: { title: '反馈处理', permission: PERM.feedbackHandle }
  }
] satisfies RouteRecordRaw[]
