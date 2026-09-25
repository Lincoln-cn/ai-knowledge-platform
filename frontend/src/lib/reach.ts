/**
 * 「这个路由当前登录角色能不能到」的**唯一判据**。
 *
 * 为什么要有这个文件：同一段判断此前在**三处各写了一遍** ——
 * 外壳顶栏、命令面板、工作台的入门入口。三份拷贝迟早漂移，而漂移的表现是
 * **同一个入口在 A 处被隐藏、在 B 处仍然可见**：用户看到通向 403 的入口，
 * 比"没有入口"更坏（这是 design.md 外壳那一节的判据）。
 * 实际踩到过：工作台的「有 N 条反馈待处理 —— 去处理」只判了 `count > 0`、
 * 没判权限，于是无 `feedback:handle` 的角色会看到一条通往 403 的链接。
 *
 * 判据**与路由守卫同源**：读 `route.meta.permission`，再问 `session.can()`。
 * 不在这里另存一份权限表 —— 否则菜单与守卫会漂移成「菜单看得见、点进去 403」。
 *
 * ⚠️ 为什么 `router` 要当参数传进来：本工程没有全局单例 ——
 * `bootstrap/router.ts` 的 `createAppRouter()` 造一份、`installRouter()` 装上去。
 * 在模块顶层 import 一个实例是不存在的。三处调用点手上都有 `router`。
 */
import type { Router } from 'vue-router'
import { useSessionStore } from '@/stores/session'

export function canReach(router: Router, path: string): boolean {
  const need = router.resolve(path).meta.permission
  return !need || useSessionStore().can(need)
}
