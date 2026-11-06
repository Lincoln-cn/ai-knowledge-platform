import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/** 合规设置（#83）：反馈时限公示值 + 算法备案/安全评估登记位。 */
export default [
  {
    path: 'compliance',
    name: 'compliance',
    component: () => import('./Index.vue'),
    meta: { title: '合规设置', permission: PERM.complianceManage }
  }
] satisfies RouteRecordRaw[]
