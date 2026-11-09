import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/**
 * 模板中心。
 *
 * ★ 权限借 `assistant:view` / `assistant:edit` 而不是新开 `template:*`：
 *   这一版模板的唯一来源是「从一个应用存成快照」，谁有应用读权限就该能看模板，
 *   谁有应用写权限就该能存模板 / 从模板建应用。单开权限点会立刻造出
 *   「有 `template:edit` 却看不到任何应用」的空角色。
 */
export default [
  {
    path: 'templates',
    name: 'templates',
    component: () => import('./Index.vue'),
    meta: { title: '模板中心', permission: PERM.assistantView }
  }
] satisfies RouteRecordRaw[]
