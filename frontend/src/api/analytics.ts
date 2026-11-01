import { degradeOnMissingEndpoint } from '@/lib/degrade'
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
  /** 首字延迟 P50；当前 cv_message 没有首 token 时间戳，无数据源时为 null */
  firstTokenLatencyP50: number | null
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

/**
 * ★ 这五条都在 `ContractDriftTest` 的 `KNOWN_MISSING` 里（后端还没写）。
 *
 * <h3>为什么在这里降级（`#160`）</h3>
 * <p>不降级时，`await` 它们拿到的是一条 404 包络（`接口不存在：api/v1/analytics/...`），
 * 于是变成一次**未处理 rejection ⇒ 页面级 PAGEERROR**；而用户看到的只是"这块空着"。
 * 结果"功能还没做"与"已做的功能被未实现端点拖坏"在读数上长得一模一样。
 *
 * <p>降级后：空数据 + 一条 WARN（同一个端点一次流程只念一次），页面照常渲染；
 * 后端补上端点之后这层自动不再触发。★ 只认「接口不存在」这一种错误，
 * 500 / 越权 / 网络不通照旧往上抛（见 `src/lib/degrade.ts`）。
 *
 * <p>★ 空数据用「零 / 空数组 / `null`」：可空字段（`satisfaction` 等）本来就约定
 * "无数据显示「—」而不是 0"，所以给 `null`；计数类给 0 —— 但"0 次提问"与"还没实现"
 * 仍要靠那条 WARN 区分，别把 0 当成"这个月真的没人问"。
 */

export const getOverview = (range: AnalyticsRange) =>
  degradeOnMissingEndpoint(
    request<OverviewMetrics>({ method: 'GET', url: '/analytics/overview', query: { ...range } }),
    { questionCount: 0, activeUserCount: 0, newUserCount: 0, tokenCount: 0, satisfaction: null },
    'GET /analytics/overview'
  )

export const getTrend = (range: AnalyticsRange) =>
  degradeOnMissingEndpoint(
    request<TrendPoint[]>({ method: 'GET', url: '/analytics/trend', query: { ...range } }),
    [],
    'GET /analytics/trend'
  )

export const listTopAssistants = (range: AnalyticsRange, limit = 10) =>
  degradeOnMissingEndpoint(
    request<AssistantRankItem[]>({
      method: 'GET',
      url: '/analytics/assistants',
      query: { ...range, limit }
    }),
    [],
    'GET /analytics/assistants'
  )

export const getQualityMetrics = (range: AnalyticsRange) =>
  degradeOnMissingEndpoint(
    request<QualityMetrics>({ method: 'GET', url: '/analytics/quality', query: { ...range } }),
    {
      avgRefCount: 0,
      firstTokenLatencyP50: null,
      artifactFailureRate: null,
      upCount: 0,
      downCount: 0,
      pendingFeedbackCount: 0
    },
    'GET /analytics/quality'
  )

/** 我的口径聚合（消费者工作台用）。与 `getOverview` 是两套口径，不可互换 */
export const getMySummary = (range: AnalyticsRange) =>
  degradeOnMissingEndpoint(
    request<MeSummary>({ method: 'GET', url: '/analytics/me/summary', query: { ...range } }),
    { myQuestionCount: 0, myConversationCount: 0, lastActiveAt: null, myUp: 0, myDown: 0 },
    'GET /analytics/me/summary'
  )
