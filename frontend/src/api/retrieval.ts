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
  /**
   * 取回条数，**服务端硬上限 20**（`#231`）。
   *
   * ★ 写读数脚本时最容易踩的两处，症状都是「命中 0 条」而不是报错：
   *   · `topN > 20` ⇒ 后端返 **400**「TopN 应在 1～20 之间」，而 `body.data.items`
   *     在 400 时不存在 ⇒ 脚本读到空数组 ⇒ 把一次被拒的请求读成「检索完全不通」
   *     （B1 凭空归零）；
   *   · 命中项的文档名字段是 `HitItem.docName`，不是 `documentName` ⇒
   *     取错键得到空串，同样被读成「没命中」。
   * ⇒ 读数脚本必须先判状态码、再用 `items`，不许写 `or []` 把 400 吃掉。
   */
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
  /** ★ 就是这个键名（不是 `documentName`）。读数脚本取错键 ⇒ 空串 ⇒ 被读成「命中 0 条」（#231） */
  docName: string
  kbName: string
  score: number
  content: string
}

export interface HitTestRequest extends RetrievalParams {
  /** 限定检索的知识库；不传则用应用绑定的全部 */
  kbIds?: string[]
  question: string

  /* ↓ 第 14 周多跳（10-RAG-检索策略设计 §3.4 / §6.2 第 5 项）。★ 只挂在命中测试这一侧：
     它不落库、不是知识库检索参数（后端那两列还没有，见 KnowledgeBase.RetrievalParamsView） */
  /** 是否开启多跳迭代；缺省 false —— 每轮多花一次模型调用，默认把这个成本留给想付的人 */
  multiHopEnabled?: boolean
  /** 多跳轮数上限（含首轮），1～3；缺省 2（= 首轮 + 最多再检一轮） */
  maxHops?: number
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

  /* ↓ 第 14 周多跳（§3.4 / §7.1）。★ 全部可选：老后端不返回这一层，
     界面要按「没有它」处理，而不是把缺失读成 0（0 会被读成「多跳一轮都没跑」） */
  /** 是否触发了多跳判定（规则判定：最高分 < threshold 或 命中数 < 3） */
  hopTriggered?: boolean
  /** 实际跑掉的轮数（含首轮）：至少 1 */
  hopRounds?: number
  /** 为什么停下 —— 机器可判的前缀 + 人读的说明（例：SUFFICIENT：模型判定：已有命中足够回答） */
  hopStopReason?: string
  /** 最后一轮带来的新分段数：0 说明那一轮白跑（§7.1 要的「多跳的边际收益」） */
  hopNewSegments?: number
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
