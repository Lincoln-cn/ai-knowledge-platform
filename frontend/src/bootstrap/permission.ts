import type { NavigationGuard } from 'vue-router'
import { useSessionStore } from '@/stores/session'

/**
 * 路由守卫：登录态 + 路由级权限 + 强制改密。
 *
 * **门禁做在路由层，不只在按钮层。** 没权限的页面是「进不去」，
 * 而不是「进得去但按钮藏起来了」—— 后者拦不住直接敲 URL 的人。
 *
 * `meta.permission` 是唯一声明点（见 router/routes.ts 的类型声明）。
 * 未声明权限的路由只要登录就能进。
 */
export const permissionGuard: NavigationGuard = async (to) => {
  const isPublic = to.meta.public === true

  // 登录态从 localStorage 读：守卫可能在任何 pinia 动作之前跑，
  // 这里只用它判断「有没有带凭证」，不用它判断「是谁」。
  const token = localStorage.getItem('access_token')

  if (!token && !isPublic) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (token && to.path === '/login') {
    return { path: '/' }
  }

  const session = useSessionStore()

  // 强制改密：改完前不放行进业务页（防绕过 —— 直敲 URL 也被拦回来）
  if (session.mustChangePassword && to.path !== '/change-password') {
    return { path: '/change-password' }
  }

  const required = to.meta.permission
  if (!required) return true

  // 硬刷新时 store 是空的，而权限点只在「当前用户」接口里 ——
  // 必须先把它取回来再判，否则「刷新一下就进不去」会被当成权限 bug。
  if (!session.user) {
    try {
      await session.loadProfile()
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // 取不回来就当没有（`can` 已经这么处理）—— 门禁失败要往「拒绝」倒，
  // 不能因为接口挂了就放行。
  if (!session.can(required)) {
    return { path: '/403', query: { from: to.fullPath } }
  }

  return true
}
