/**
 * 问答对（需求 `03 §六 FR-03-12`）—— **知识库侧**的契约。
 *
 * ## ★ 为什么在知识库里，不在应用里
 *
 * 口径见设计册 `10 §7.4.1`：问答对是**知识材料**（问题 ↔ 答案 ↔ 出处分段），与文档 / 分段同级；
 * 应用消费的是**检索结果**，不是问答对本身。
 *
 * ★ 应用配置里的 `quickQuestions`（开场白快捷问）**不是**问答对：
 * 它只是界面上几个可点的句子，不参与检索、不与分段关联。
 *
 * ## ★★ 它已经接进检索了（0.9.9，`#78` 第二段）
 *
 * 这里曾经写着「本期检索侧一行不接」—— **那句话已经过期**。现在问答对是检索的一路：
 * 问句与标定问法分词后比对，命中即参与。命中之后怎么用它，由 `directAnswer` 决定：
 *
 * - `false`（默认）= **召回型**：作为一路候选参与 RRF 融合，由生成模型写答案 ——
 *   问答对只是"这条更该被引用"的证据；
 * - `true` = **直答型**：命中即**直接把 `answer` 当作回答返回**，不融合、不调模型。
 *
 * ★ 为什么默认是 `false`：直答跳过生成，一旦判错（问句相似但并非同一个问题），
 * 用户会拿到一个语气笃定、内容不对的答案，**而且没有任何环节能纠正它**。
 *
 * ## ★ 与 `FR-03-12-4` 的偏离（已登记在设计册）
 *
 * 需求写的是"为问题关联**若干**分段"，而 `kb_qa_pair` 是**一段一行**。
 * ⇒ 本期把"若干分段"实现为**同一问题写多行**；"一个问题一个答案、挂多段出处"本期做不到。
 */
import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/** 问答对密级（与 `13 §6` 的密级口径一致，且不得高于所属知识库的上限）。 */
export type QaPairClassification = 'PUBLIC' | 'INTERNAL' | 'SECRET' | 'CONFIDENTIAL'

/**
 * 来源：这条问答对**凭什么可信、被质疑时怎么追溯**。
 *
 * ★ 从 web 手输的一律是 `MANUAL`；`CONVERSATION` / `FEEDBACK` 由各自的沉淀链写入。
 */
export type QaPairOrigin = 'CONVERSATION' | 'FEEDBACK' | 'MANUAL' | 'DOCUMENT'

export interface QaPair {
  id: string
  /** 归属：所属知识库 */
  kbId: string
  question: string
  answer: string
  /** 出处分段（可空：人工导入的问答对可以没有出处） */
  segmentId: string | null
  /**
   * 命中之后是否**直接作答**。
   *
   * `false` = 召回型（参与融合，生成模型写答案）；`true` = 直答型（命中即原样返回，不调模型）。
   * ★ 默认 `false`：不勾这个框，问答对就只是个更好的候选。
   */
  directAnswer: boolean
  classification: QaPairClassification
  origin: QaPairOrigin
  /** 命中计数。★ **仍恒为 0**：检索这一期只读它、不写它（写回要动对话主链路的写路径） */
  hitCount: number
  createdAt: string
  updatedAt: string
}

/** 列出某知识库的问答对（`FR-03-12-7`） */
export const listQaPairs = (kbId: string, query: PageQuery = {}) =>
  request<PageResult<QaPair>>({
    method: 'GET',
    url: '/knowledge-bases/:kbId/qa-pairs',
    path: { kbId },
    query
  })

/** 新建一条（`FR-03-12-1` 单条形态）。`origin` 由服务端固定为 `MANUAL`，前端不传 */
export const createQaPair = (
  kbId: string,
  body: {
    question: string
    answer: string
    classification: QaPairClassification
    segmentId?: string | null
    /** 命中后是否直接作答（缺省 false） */
    directAnswer?: boolean
  }
) =>
  request<QaPair>({
    method: 'POST',
    url: '/knowledge-bases/:kbId/qa-pairs',
    path: { kbId },
    body
  })

/**
 * 多行批量新建（`FR-03-12-1` 多行形态）：一行一个问题。
 *
 * ★ 语义是**一批要么全进、要么一条都不进** —— 半批成功会让调用方无法判断
 * "重试会不会造出重复行"，而问答对没有业务唯一键。
 */
export const batchCreateQaPairs = (
  kbId: string,
  items: Array<{
    question: string
    answer: string
    classification: QaPairClassification
    segmentId?: string | null
    /** 命中后是否直接作答（缺省 false） */
    directAnswer?: boolean
  }>
) =>
  request<QaPair[]>({
    method: 'POST',
    url: '/knowledge-bases/:kbId/qa-pairs/batch',
    path: { kbId },
    body: { items }
  })

/** 行内改名（`FR-03-12-2`）。★ 只改问题：答案 / 出处 / 密级各有各的入口与校验 */
export const renameQaPair = (id: string, question: string) =>
  request<QaPair>({
    method: 'PATCH',
    url: '/qa-pairs/:id/question',
    path: { id },
    body: { question }
  })

/**
 * 切换「命中后是否直接作答」。
 *
 * ★ 与改名分成两个入口：改名的后果是"换个说法"，切直答的后果是"命中即跳过生成"。
 * 合成一个 `PATCH` 会出现"只想改个错字，顺手把这条设成了直答"。
 */
export const switchQaPairDirectAnswer = (id: string, directAnswer: boolean) =>
  request<QaPair>({
    method: 'PATCH',
    url: '/qa-pairs/:id/direct-answer',
    path: { id },
    body: { directAnswer }
  })

/** 删除（`FR-03-12-3`）—— 软删，幂等 */
export const deleteQaPair = (id: string) =>
  request<void>({ method: 'DELETE', url: '/qa-pairs/:id', path: { id } })
