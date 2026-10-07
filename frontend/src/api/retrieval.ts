import { request } from '@/lib/http'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 三种检索模式。混合模式用 RRF 融合向量与全文结果 */
export type RetrievalMode = 'VECTOR' | 'FULLTEXT' | 'HYBRID'

/** 无引用时的策略（2.4 补录） */
export type NoRefStrategy = 'REFUSE' | 'FREE_ANSWER'

export interface RetrievalParams {
  mode: RetrievalMode
  /** 相似度阈值。先过滤后取 TopN —— 顺序反了会把噪声带进上下文 */
  threshold: number
  topN: number
  /** 无引用时的策略 */
  noRefStrategy: NoRefStrategy

  /* ↓ 第 12 周查询理解（10-RAG-检索策略设计 §8.1）。全部可选：
     不传即「后端按平台默认值处理」，老后端收到也不认识、忽略即可 */
  /** 改写 + 扩展一次产出的查询条数。1 = 只改写；0 = 不做。缺省 3 */
  queryCount?: number
  /** 是否允许把多意图问题拆成子问题。缺省 true */
  decomposeEnabled?: boolean
  /** 子问题上限。缺省 3 */
  maxSubQuestions?: number
}

export interface HitItem {
  rank: number
  segmentId: string
  title: string
  docName: string
  kbName: string
  score: number
  content: string
}

export interface HitTestRequest extends RetrievalParams {
  /** 限定检索的知识库；不传则用应用绑定的全部 */
  kbIds?: string[]
  question: string
}

/**
 * 一次检索里查询理解层的实际发生情况（10-RAG-检索策略设计 §6.3 / §7.1）。
 * 数值一律是**实测值**而不是配置值 —— 配置 3 条但解析失败退回 1 条时，
 * 界面要看到的是 1，否则这块展示就成了「把配置念一遍」。
 */
export interface HitTestTrace {
  /** M：实际拆出的子问题数 */
  subQuestionCount: number
  /** N：实际发出去的总查询数 */
  queryCount: number
  /** 总检索路数（受总路数预算截断后的值，§6.2 默认 6） */
  pathCount: number
  /** 降级档位 0..4；4 = 回退到原始问题单路，是永不低于的底线（§6.3） */
  degradeLevel: number
  degradeReason?: string
  /** 子问题数 / 查询数是否被上限截断过 */
  truncated: boolean
}

export interface HitTestResult {
  mode: RetrievalMode
  items: HitItem[]
  /** 本次检索耗时，用于界面展示与调参对比 */
  elapsedMs: number
  /** 可选：老后端与老 mock 不返回，界面必须照常工作 */
  trace?: HitTestTrace
}

/* ────────────────────────── 接口 ────────────────────────── */

/** 命中测试。一次请求返回单模式结果；对比模式由前端并发三次调用 */
export const hitTest = (body: HitTestRequest) =>
  request<HitTestResult>({ method: 'POST', url: '/retrieval/test', body })

/** 并发跑三种模式，用于「对比模式」并列展示 */
export const hitTestCompare = (body: Omit<HitTestRequest, 'mode'>) =>
  Promise.all(
    (['VECTOR', 'FULLTEXT', 'HYBRID'] as RetrievalMode[]).map((mode) =>
      hitTest({ ...body, mode })
    )
  )

export const getRetrievalParams = (kbId: string) =>
  request<RetrievalParams>({ method: 'GET', url: '/knowledge-bases/:kbId/retrieval', path: { kbId } })

export const updateRetrievalParams = (kbId: string, body: Partial<RetrievalParams>) =>
  request<RetrievalParams>({
    method: 'PUT',
    url: '/knowledge-bases/:kbId/retrieval',
    path: { kbId },
    body
  })
