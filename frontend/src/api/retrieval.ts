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

export interface HitTestResult {
  mode: RetrievalMode
  items: HitItem[]
  /** 本次检索耗时，用于界面展示与调参对比 */
  elapsedMs: number
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
