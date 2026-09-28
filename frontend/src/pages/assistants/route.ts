import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'assistants',
    name: 'assistants',
    component: () => import('./Index.vue'),
    // 数据范围维度（07 §五）：应用管理只在 own / all 下有形态（建设面）。
    // 消费者（shared）去 /market（应用市场）。
    meta: { title: '应用管理', permission: PERM.assistantView, scopeForms: ['OWN', 'ALL'] }
  },
  {
    // 提示词是配置资产（写应用时才用），不是终端用户内容 ⇒ 用 edit 而不是 view。
    // 否则「普通用户」能进提示词库改字，而这正是他要被挡在外面的事。
    path: 'assistants/prompts',
    name: 'prompts',
    component: () => import('./prompts/Index.vue'),
    meta: { title: '提示词', permission: PERM.assistantEdit }
  }
] satisfies RouteRecordRaw[]
