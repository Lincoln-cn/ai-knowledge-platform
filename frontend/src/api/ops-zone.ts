import { request } from '@/lib/http'

/* ────────────────────────── 契约类型 ────────────────────────── */

/**
 * 「审计与风险」专区的三个只读契约（`#354`）。
 *
 * ★ 它们不共用 `/analytics` 前缀：运营看板回答「业务跑得怎么样」（`dashboard:view`），
 * 这里回答「系统有没有毛病」（`audit:view`）。两件事的读者与权限都不同，
 * 后端也是两个模块（`/login-logs` 在 identity、`/ops-zone/*` 在 analytics）。
 */

/** 登录结果。与后端 `LoginLogStore.Result` 一一对应。 */
export type LoginResult = 'SUCCESS' | 'BAD_CREDENTIALS' | 'LOCKED' | 'DISABLED' | 'CAPTCHA'

export interface LoginLogEntry {
  id: string
  /** 事件时刻（ISO） */
  at: string
  /** 用户填的名字。★ 账号不存在时也有值（那是排查「在枚举用户名」的唯一线索） */
  username: string
  /** 解析到的用户；账号不存在时为 null */
  userId: string | null
  result: LoginResult
  ip: string | null
  userAgent: string | null
}

export interface LoginLogCount {
  result: LoginResult
  count: number
}

export interface LoginLogPage {
  list: LoginLogEntry[]
  total: number
  /** 最近 N 天各结果的次数 */
  counts: LoginLogCount[]
}

export interface LoginLogQuery {
  days?: number
  result?: string
  keyword?: string
  page?: number
  size?: number
}

export const getLoginLogs = (query: LoginLogQuery = {}) =>
  request<LoginLogPage>({ method: 'GET', url: '/login-logs', query: { ...query } })

/** 体检等级。`UNKNOWN` = 读不出来 —— 它不等于 `CRITICAL`，但更不等于 `OK`。 */
export type RiskLevel = 'OK' | 'WARN' | 'CRITICAL' | 'UNKNOWN'

export interface RiskItem {
  key: string
  label: string
  level: RiskLevel
  /** 给人看的短值（「62.3%」） */
  value: string
  /** 算出来的明细 */
  detail: string
  /** 只在非 OK 时给 */
  hint: string | null
}

export interface RiskSnapshot {
  at: string
  items: RiskItem[]
}

export const getRiskSnapshot = () => request<RiskSnapshot>({ method: 'GET', url: '/ops-zone/risk' })

/** 失败来源：`CHAT` 回答失败 / `DOCUMENT` 解析或向量化失败。 */
export type ErrorSource = 'CHAT' | 'DOCUMENT'

export interface ErrorLogEntry {
  source: ErrorSource
  at: string
  title: string
  reason: string
  hint: string | null
  /** 跳转用的对象 id（会话 id 或文档 id） */
  ref: string
  rowId: string
}

export interface ErrorLogCount {
  source: ErrorSource
  count: number
}

export interface ErrorLogPage {
  list: ErrorLogEntry[]
  total: number
  bySource: ErrorLogCount[]
}

export interface ErrorLogQuery {
  days?: number
  source?: string
  page?: number
  size?: number
}

export const getErrorLogs = (query: ErrorLogQuery = {}) =>
  request<ErrorLogPage>({ method: 'GET', url: '/ops-zone/error-logs', query: { ...query } })
