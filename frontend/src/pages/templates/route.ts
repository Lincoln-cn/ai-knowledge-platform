import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/**
 * 应用模板。
 *
 * ★ 权限借 `assistant:edit` 而不是新开 `template:*`：这一版模板的唯一来源是
 *   「从一个应用存成快照」，而**用模板做的事只有一件 —— 建一个新应用**，
 *   那需要 `assistant:edit`。所以有写权限才进得来。
 *
 * ★ 2026-11-16 由 `assistant:view` 收紧到 `assistant:edit`（ROADMAP §11.52.9）：
 *   原来按"谁有应用读权限就该能看模板"给了 `assistant:view`，结果是**纯消费者
 *   （只有 `assistant:view`）在顶栏看得见这一项，进去却什么都做不了** ——
 *   "看得见但用不上"比"看不见"更糟，它让人以为是自己不会用。
 */
export default [
  {
    path: 'templates',
    name: 'templates',
    component: () => import('./Index.vue'),
    meta: { title: '应用模板', permission: PERM.assistantEdit }
  }
] satisfies RouteRecordRaw[]
