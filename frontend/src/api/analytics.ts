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

/**
 * 运营指标（`#357` 前半）：**质量**与**用量**两块，全平台口径。
 *
 * <h3>为什么不复用 {@link QualityMetrics}</h3>
 * <p>`/quality` 那一条是"质量"的旧口径：首字延迟与结构化输出失败率**永远是 null**（没有数据源）。
 * 这条给的是能真算的那一组 —— 总耗时 p50/p95、无引用回答占比、活跃应用/用户数。
 *
 * <h3>★ 两个分母</h3>
 * <p>`noRefRate` 的分母是**成功回答数**（`generatedAnswerCount`），不是全部回答数：
 * 失败的回答本来就没有引用，算进去会把产品问题说成检索问题。
 * 分母为 0 时它是 `null`（"0/0"不是 0%，是没有样本），界面显示「—」。
 *
 * <p>★ B1/B2/B3 不在这里：那是离线评测读数（`docs/检索基线读数.md`），运行期接口不冒充它。
 */
export interface OpsMetrics {
  /** 提问数（USER 消息） */
  questionCount: number
  /** 回答数（含失败） */
  answerCount: number
  /** 成功回答数（status = GENERATED） */
  generatedAnswerCount: number
  failedAnswerCount: number
  tokenCount: number
  /** 平均总耗时；★ 这不是首字延迟 */
  avgDurationMs: number | null
  durationP50Ms: number | null
  durationP95Ms: number | null
  avgRefCount: number
  /** 无引用回答数（只数成功回答） */
  answersWithoutRefs: number
  /** 无引用占比（%）；分母为 0 时为 null */
  noRefRate: number | null
  activeAssistantCount: number
  activeUserCount: number
  upCount: number
  downCount: number
  satisfactionRate: number | null
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

/**
 * 运营指标（`#357` 前半）。
 *
 * ★ 不用 `degradeOnMissingEndpoint` 包一层：这条端点是本批新写的、后端已实现。
 * 它若 404，那是我们自己的 bug（契约与实现没对上），应当红而不是静默降级成 0。
 */
export const getOpsMetrics = (range: AnalyticsRange) =>
  request<OpsMetrics>({ method: 'GET', url: '/analytics/metrics', query: { ...range } })

/** 我的口径聚合（消费者工作台用）。与 `getOverview` 是两套口径，不可互换 */
export const getMySummary = (range: AnalyticsRange) =>
  degradeOnMissingEndpoint(
    request<MeSummary>({ method: 'GET', url: '/analytics/me/summary', query: { ...range } }),
    { myQuestionCount: 0, myConversationCount: 0, lastActiveAt: null, myUp: 0, myDown: 0 },
    'GET /analytics/me/summary'
  )
