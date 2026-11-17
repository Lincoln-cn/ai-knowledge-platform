/**
 * 错误日志：把系统里**已经记下来的失败**聚到一处（`#354`）。
 *
 * <p>★ 口径是"读已有的事实，不新增写入路径"：回答失败读 `cv_message.fail_reason`，
 * 解析/向量化失败读 `kb_document.fail_reason`。再写一张"错误日志表"会让每条失败落两处，
 * 而两处必然漂移。
 *
 * <p>★ 它不含未捕获异常（500 的堆栈）—— 那要集中式日志采集，属 `#73` 的可观测那笔账。
 * 页面上写明了这一点，免得有人以为「这里没记录 ⇒ 系统没出错」。
 */
import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'error-logs',
    name: 'error-logs',
    component: () => import('./Index.vue'),
    meta: { title: '错误日志', permission: PERM.auditView }
  }
] satisfies RouteRecordRaw[]
