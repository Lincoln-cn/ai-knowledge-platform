/**
 * 风险提示：四项只读体检（磁盘 / JVM 堆 / 慢回答 / 失败回答）（`#354`）。
 *
 * <p>★ 它不是告警系统。`#73` 记的"零指标零告警零配额"仍然开着：真正的告警要有时序库、阈值管理、
 * 通知渠道与静默期。这一页只回答"现在有什么值得看一眼"，**不推送、不落历史、不判 SLA**。
 * 写清这一点，是因为「看到有风险提示页 ⇒ 以为有人盯着」比没有这一页更危险。
 */
import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'risk',
    name: 'risk',
    component: () => import('./Index.vue'),
    meta: { title: '风险提示', permission: PERM.auditView }
  }
] satisfies RouteRecordRaw[]
