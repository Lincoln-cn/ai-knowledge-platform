import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'knowledge',
    name: 'knowledge',
    component: () => import('./Index.vue'),
    meta: { title: '知识库', permission: PERM.knowledgeView }
  },
  {
    path: 'knowledge/hittest',
    name: 'knowledge-hittest',
    component: () => import('./hittest/Index.vue'),
    meta: { title: '命中测试', permission: PERM.hitTest }
  }
] satisfies RouteRecordRaw[]
