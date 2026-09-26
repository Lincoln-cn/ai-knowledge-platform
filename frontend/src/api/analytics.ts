import { request } from '@/lib/http'

/* ────────────────────────── 契约类型 ────────────────────────── */

export interface AnalyticsRange {
  /** 起始日期，YYYY-MM-DD */
  from: string
  to: string
}

export interface OverviewMetrics {
  questionCount: number
  activeUserCount: number
  newUserCount: number
  tokenCount: number
  /** 满意度百分比；无数据时为 null，界面显示「—」而不是 0 */
  satisfaction: number | null
}

export interface TrendPoint {
  day: string
  questions: number
  users: number
}

export interface AssistantRankItem {
  appId: string
  name: string
  callCount: number
  tokenCount: number
  satisfaction: number | null
}

/** 质量观测。首字延迟比总耗时更接近用户感知（03 §机制七） */
export interface QualityMetrics {
  avgRefCount: number
  firstTokenLatencyP50: number
  /** 结构化输出失败率。MVP 只埋点，V1.1 起考核 */
  artifactFailureRate: number | null
  upCount: number
  downCount: number
  /** 待处理反馈数，用于从看板跳到反馈处理 */
  pendingFeedbackCount: number
}

/**
 * 「我的」口径聚合（07 §五 · #18）。
 *
 * 消费者进工作台看到的是**我**的使用情况，不是"平台运营成什么样"。
 * 全平台活跃用户数与待处理反馈对他既是噪声也是越界。
 */
export interface MeSummary {
  /** 我的提问数 */
  myQuestionCount: number
  /** 我的对话数 */
  myConversationCount: number
  /** 我最近一次活跃时间；从未活跃为 null */
  lastActiveAt: string | null
  /** 我点过的赞 */
  myUp: number
  /** 我点过的踩 */
  myDown: number
}

/* ────────────────────────── 接口 ────────────────────────── */

export const getOverview = (range: AnalyticsRange) =>
  request<OverviewMetrics>({ method: 'GET', url: '/analytics/overview', query: { ...range } })

export const getTrend = (range: AnalyticsRange) =>
  request<TrendPoint[]>({ method: 'GET', url: '/analytics/trend', query: { ...range } })

export const listTopAssistants = (range: AnalyticsRange, limit = 10) =>
  request<AssistantRankItem[]>({
    method: 'GET',
    url: '/analytics/assistants',
    query: { ...range, limit }
  })

export const getQualityMetrics = (range: AnalyticsRange) =>
  request<QualityMetrics>({ method: 'GET', url: '/analytics/quality', query: { ...range } })

/** 我的口径聚合（消费者工作台用）。与 `getOverview` 是两套口径，不可互换 */
export const getMySummary = (range: AnalyticsRange) =>
  request<MeSummary>({ method: 'GET', url: '/analytics/me/summary', query: { ...range } })
