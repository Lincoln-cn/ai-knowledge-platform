import type { RouteRecordRaw } from 'vue-router'

/**
 * 403。挂在 shell 之下（带侧栏）—— 被拦下来的人需要一条能走回去的路，
 * 而侧栏正是「你现在还能去哪」。
 *
 * 本身不声明 `permission`：否则就会自拦自，403 页自己 403。
 */
export default [
  {
    path: '403',
    name: 'forbidden',
    component: () => import('./Index.vue'),
    meta: { title: '无权访问' }
  }
] satisfies RouteRecordRaw[]
