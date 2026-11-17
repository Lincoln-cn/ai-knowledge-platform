import type { RouteRecordRaw } from 'vue-router'

/**
 * 我的投诉（`#356`）：当事人自己提交的那几张单，以及它们的过程。
 *
 * ★ 与「投诉举报」页的分工：那一页是**入口 + 全量台账**（有办理权限的人看到所有人的）；
 * 这一页只回答一个问题——"我提的那件事现在怎么样了"，所以固定 `mine=true`，
 * 不设 `meta.permission`（登录即可，自己的东西本来就该看得到）。
 */
export default [
  {
    path: 'my-complaints',
    name: 'my-complaints',
    component: () => import('./Index.vue'),
    meta: { title: '我的投诉' }
  }
] satisfies RouteRecordRaw[]
