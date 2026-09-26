import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/**
 * 反馈处理状态机：待处理 → 处理中 → 已处理 / 已忽略；
 * 已处理与已忽略都可重新打开（05 §四 增强 3 / MISS-01）。
 */
export type FeedbackStatus = '待处理' | '处理中' | '已处理' | '已忽略'

export interface FeedbackItem {
  id: string
  appId: string
  appName: string
  conversationId: string | null
  conversationTitle: string | null
  type: 'up' | 'down'
  question: string
  answer: string
  comment: string
  status: FeedbackStatus
  assignee: string | null
  resolution: string
  /** 处理为「已处理」时可勾选沉淀为知识库问题 */
  promotedToKbId: string | null
  promotedToKbName: string | null
  createdAt: string
  handledAt: string | null
  /** 提交者 id（07 §六 阶段 1 可选字段）。用于 `mine=true` 过滤「我提的反馈」 */
  requesterId?: string
}

export interface FeedbackStats {
  待处理: number
  处理中: number
  已处理: number
  已忽略: number
}

export interface FeedbackQuery extends PageQuery {
  status?: FeedbackStatus
  appId?: string
  /** 只看自己提交的（07 §六 阶段 1）。不传 = 按数据范围给全部可见的 */
  mine?: boolean
}

export interface HandleFeedbackBody {
  status: FeedbackStatus
  assignee: string | null
  resolution: string
  /** 勾选后沉淀为知识库问题（FR-03-12 问答对） */
  promoteToKbId?: string | null
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listFeedbacks = (query: FeedbackQuery = {}) =>
  request<PageResult<FeedbackItem>>({ method: 'GET', url: '/feedbacks', query })

export const getFeedback = (id: string) =>
  request<FeedbackItem>({ method: 'GET', url: '/feedbacks/:id', path: { id } })

export const getFeedbackStats = () =>
  request<FeedbackStats>({ method: 'GET', url: '/feedbacks/stats' })

/** 保存处理结果：状态流转、指派、处理说明、沉淀一并提交 */
export const handleFeedback = (id: string, body: HandleFeedbackBody) =>
  request<FeedbackItem>({ method: 'PATCH', url: '/feedbacks/:id', path: { id }, body })

/** 重新打开：已处理/已忽略 → 处理中，并清空处理时间 */
export const reopenFeedback = (id: string) =>
  request<FeedbackItem>({ method: 'POST', url: '/feedbacks/:id/reopen', path: { id } })
