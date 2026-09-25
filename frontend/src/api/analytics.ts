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
