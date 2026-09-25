import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'knowledge',
    name: 'knowledge',
    component: () => import('./Index.vue'),
    // 数据范围维度（07 §五）：知识库只在「有自己的库（own）」与「看全平台（all）」
    // 两种范围下有页面形态。纯消费者（shared）没有自建能力、也没有自己的库，
    // 所以这一项对他**不列** —— 而不是列出来再少几个按钮（那正是「整个工地」）。
    meta: { title: '知识库', permission: PERM.knowledgeView, scopeForms: ['own', 'all'] }
  },
  {
    path: 'knowledge/hittest',
    name: 'knowledge-hittest',
    component: () => import('./hittest/Index.vue'),
    // 命中测试同理：它是治理/建设工具，消费者范围下没有形态
    meta: { title: '命中测试', permission: PERM.hitTest, scopeForms: ['own', 'all'] }
  }
] satisfies RouteRecordRaw[]
