import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/**
 * 组织机构（`07 §十`）—— 树形维护，独立成一个菜单项。
 *
 * ★ 为什么独立成菜单而不是塞进「系统管理」：它是**数据权限的载体**（共享给部门、按部门筛文档），
 * 与"账号/角色/权限点"是两件事；而用户明确要求过"别再往系统管理堆 tab"。
 */
const routes: RouteRecordRaw[] = [
  {
    path: 'org',
    name: 'org',
    component: () => import('./Index.vue'),
    meta: { title: '组织机构', permission: PERM.orgManage }
  }
]

export default routes
