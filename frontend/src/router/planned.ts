import type { RouteRecordRaw } from 'vue-router'

/**
 * 未迁移模块的路由声明。
 *
 * 每个模块目录下的 `route.ts` 是这个模块的**唯一**路由真相源：
 * 未迁移时用 `planned()` 指向占位页，迁移完成时把 component 换成自己的 Index.vue。
 * 因此不存在「忘了改集中路由表」这种漏，也不会出现总览里点开是死链。
 */
export function planned(path: string, title: string, source: string): RouteRecordRaw {
  return {
    path,
    name: path.replace(/\//g, '-'),
    component: () => import('@/pages/pending/Index.vue'),
    meta: { title, source, planned: true }
  }
}
