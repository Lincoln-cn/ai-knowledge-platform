import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/**
 * 编排画布。挂在 `/assistants/:appId/workflow` —— 应用管理列表里
 * 编排型应用的「编排」与「配置」都跳这里（画布即它的配置面）。
 *
 * 用 `assistant:edit` 而不是 `assistant:view`：看得到应用不等于能改它的流程，
 * 而画布上的每一次拖动都是配置行为。
 */
export default [
  {
    path: 'assistants/:appId/workflow',
    name: 'assistant-workflow',
    component: () => import('./Index.vue'),
    meta: { title: '编排画布', permission: PERM.assistantEdit }
  }
] satisfies RouteRecordRaw[]
