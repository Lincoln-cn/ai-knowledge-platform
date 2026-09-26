import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'market',
    name: 'market',
    component: () => import('./Index.vue'),
    // 登录即可进（07 §五：消费者唯一的内容入口）。不声明 scopeForms = 任何范围都有形态
    meta: { title: '应用市场', permission: PERM.assistantView }
  }
] satisfies RouteRecordRaw[]
