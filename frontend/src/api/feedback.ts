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

/**
 * 批量办理请求（P2）。整批一个目标状态；`assigneeId` 传用户 id（与单条办理的
 * `assignee` 显示名不同 —— 批量走 id，避免同名歧义，也和用户目录主键对齐）。
 * `assigneeId` 省略 = 不动人；传 null = 清空处理人。
 * `resolution` 仅在目标为「已处理」时必填（与单条办理同一条规则）。
 */
export interface BatchHandleFeedbackBody {
  ids: string[]
  status: FeedbackStatus
  assigneeId?: string | null
  resolution?: string
}

/**
 * 批量办理回执：**部分失败必须逐条表达**。
 *
 * 为什么不能只回一个总数：批量是一次点下去管 N 条，其中几条没办成时，
 * 只报「成功 8 / 失败 2」用户不知道**哪两条**没成、**为什么**没成 ——
 * 只能整批重试（把已办成的再折腾一遍）或逐条翻列表猜。所以成功给回实体、
 * 失败给 id + 可读原因，与 ingestion 的 `UploadReceipt` / `BatchDeleteSegmentsResult`
 * 同一惯例。
 */
export interface BatchHandleFeedbackResult {
  /** 办成的（已按目标状态/指派落盘） */
  accepted: FeedbackItem[]
  /** 没办成的：id + 原因，缺一不可 */
  rejected: Array<{ id: string; reason: string }>
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

/**
 * 批量办理：一次改多条的状态 / 指派。
 * 静态段 `/feedbacks/batch`，路由表里必须排在 `/feedbacks/:id` 之前
 * （否则 `:id` 会把 `batch` 当成 id 吞掉 —— 与 `/assistants/tags` 同一教训）。
 */
export const batchHandleFeedbacks = (body: BatchHandleFeedbackBody) =>
  request<BatchHandleFeedbackResult>({ method: 'PATCH', url: '/feedbacks/batch', body })

/** 重新打开：已处理/已忽略 → 处理中，并清空处理时间 */
export const reopenFeedback = (id: string) =>
  request<FeedbackItem>({ method: 'POST', url: '/feedbacks/:id/reopen', path: { id } })
