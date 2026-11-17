/**
 * 登录日志：谁在什么时候、从哪个地址、以什么结果进门（`#354`）。
 *
 * <p>★ 与「审计记录」的分工：审计记「对资源做了什么」，这里记「怎么进的门」。
 * 后者是排查暴力破解、异地登录、离职账号仍在试密码时第一眼要看的东西 —— 它以前根本不存在（连表都没有）。
 *
 * <p>权限用 `audit:view`：能看审计记录的人本来就该能看谁在试密码。
 */
import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'login-logs',
    name: 'login-logs',
    component: () => import('./Index.vue'),
    meta: { title: '登录日志', permission: PERM.auditView }
  }
] satisfies RouteRecordRaw[]
