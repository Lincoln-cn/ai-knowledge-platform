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
 * ## ★★ 本期检索侧一行不接（`#78` 的裁定）
 *
 * 通过本文件写入的问答对**不会**被检索读到。★ 界面上必须把这条写出来，
 * 否则用户建完会以为"检索已经用上了"。
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
  classification: QaPairClassification
  origin: QaPairOrigin
  /** 命中计数。★ 本期恒为 0 —— 只有检索能写它，而检索侧这一期不接 */
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

/** 删除（`FR-03-12-3`）—— 软删，幂等 */
export const deleteQaPair = (id: string) =>
  request<void>({ method: 'DELETE', url: '/qa-pairs/:id', path: { id } })
