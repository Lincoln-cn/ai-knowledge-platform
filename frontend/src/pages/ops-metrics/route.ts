/**
 * 运营指标：**质量**与**用量**的可核对读数（`#357` 前半）。
 *
 * <p>★ 它与「运营看板」的分工：看板回答"今天用了多少、在涨还是在落"；这一页回答"答得好不好"。
 * 分开的理由是两边的**分母**不同 —— 无引用占比的分母是成功回答数，满意度是赞/（赞+踩），
 * 混在一页上人就会开始自己算比例，而算错的比例没人验证。
 *
 * <p>权限沿用 `dashboard:view`：能看看板的人才该看指标。
 */
import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'ops-metrics',
    name: 'ops-metrics',
    component: () => import('./Index.vue'),
    meta: { title: '运营指标', permission: PERM.dashboardView }
  }
] satisfies RouteRecordRaw[]
