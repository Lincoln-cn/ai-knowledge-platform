import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/**
 * 投诉待办（`#356`）：办理人的工作台 —— 只看还没办结的单，按反馈时限排。
 *
 * ★ 与「投诉举报」页的分工：那一页是全量台账（含已办结、可按关键词翻历史），
 * 这一页只回答"今天该先办哪张"。所以它固定只取 SUBMITTED / PROCESSING，
 * 并按 `dueAt` 升序（最该先办的排最前），超期的单独标红。
 */
export default [
  {
    path: 'complaint-inbox',
    name: 'complaint-inbox',
    component: () => import('./Index.vue'),
    meta: { title: '投诉待办', permission: PERM.complaintHandle }
  }
] satisfies RouteRecordRaw[]
