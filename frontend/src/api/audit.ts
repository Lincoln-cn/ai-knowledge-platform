import { request } from '@/lib/http'
import type { PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 审计动作编码（`13 §七`，后端 `AuditAction` 的 `code()`）。 */
export type AuditActionCode =
  | 'document.delete'
  | 'document.reclassify'
  | 'document.relabel'
  | 'data.export'
  | 'grant.change'

/**
 * 一条审计记录。
 *
 * ★ `actorName` 是**写那一刻的快照**，不是现在再去查的名字 —— 所以它可能与用户管理页里
 * 现在显示的名字不同（改名、停用之后尤其明显）。这不是数据不一致，这正是审计要的：
 * 回答"当时是谁"而不是"现在这个人叫什么"。
 *
 * ★ `detail` 是 JSON 原文（字符串），不在前端解析成结构化对象：键名随动作而变
 * （`from`/`to` 属密级变更，`name`/`kbId` 属删除，`roleCode` 属授权变更），
 * 前端硬按某个动作的形状去读，遇到新动作就变成空白。
 */
export interface AuditRecord {
  id: string
  /** ISO-8601，UTC */
  at: string
  actorId: string | null
  actorName: string | null
  action: string
  resourceType: string
  resourceId: string
  /** 记录时的密级。★ 今天恒为 null（见 `13 §7.3` 第 2 条），不要据此做权限判断 */
  classificationLevel: string | null
  /** 记录时的 clearance。★ 今天恒为 null，同上 */
  clearanceAtAction: string | null
  /** JSON 原文 */
  detail: string | null
  result: string
}

export interface AuditLogQuery {
  action?: string
  resourceType?: string
  resourceId?: string
  actorId?: string
  /** 含 */
  from?: string
  /** 不含（半开区间：相邻两天的查询不会重复算边界那一秒） */
  to?: string
  page?: number
  size?: number
}

/* ────────────────────────── 端点 ────────────────────────── */

/**
 * 按条件查一页审计记录（时间倒序）。
 *
 * ★ 只读端点。审计**没有**写与删的接口（`13 §7.2` 第 3 条），所以这个文件里不会出现
 * `method: 'POST'` —— 谁的代码里出现"删审计"，那一定是新加的东西，要单独审。
 */
export const listAuditLogs = (query: AuditLogQuery = {}) =>
  request<PageResult<AuditRecord>>({ method: 'GET', url: '/audit-logs', query })

/** 一个资源的全部记录（"这份文档到底被谁改过"）—— 最常见的问法，单列一个端点。 */
export const listAuditLogsByResource = (resourceType: string, resourceId: string) =>
  request<AuditRecord[]>({
    method: 'GET',
    url: '/audit-logs/by-resource',
    query: { resourceType, resourceId },
  })
