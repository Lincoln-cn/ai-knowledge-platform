import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/**
 * 提示词契约（契约缺口的只加不改最小扩展）。
 *
 * 旧系统把提示词类型 / 提示词配置做成 7 份副本（markedSystem × 3 目录），
 * 冻结的 `assistants.ts` 只留了 `promptIds`，没有实体本身的 CRUD。
 * 这里补齐两个实体的最小契约，不改任何已有文件。
 */

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 提示词类型（字段命名沿用了被替换的旧契约） */
export interface PromptType {
  id: string
  typeName: string
  icon: string
  promptCount: number
}

/**
 * 提示词的两种用途。
 *
 * - `ROLE` 角色指令：给**模型**的身份设定。在应用配置里选用后写进应用的 `role_prompt`。
 * - `QUESTION` 提问模板：给**终端用户**的问法示例。出现在对话页的空态与上弹抽屉里，正文就是那句问题。
 *
 * ★ 两者共用一张表（字段几乎重合），差别只在受益对象 —— 见后端 `PromptKind` 的说明。
 */
export type PromptKind = 'ROLE' | 'QUESTION'

/** 提示词配置（字段命名沿用了被替换的旧契约） */
export interface PromptConfig {
  id: string
  kind: PromptKind
  /** 提问模板推荐给哪个应用；null = 通用。★ 只是候选池标注，运行期不读 */
  appId: string | null
  /** 名称（角色指令叫"角色名称"，提问模板叫"模板名"） */
  roleName: string
  /** 正文：角色指令是给模型的指令，提问模板就是用户会看到的那句问题 */
  callWord: string
  typeId: string
  typeName: string
  description: string
  icon: string
  /** 被多少个应用引用 */
  usageCount: number
  createdAt: string
}

export interface PromptTypeQuery extends PageQuery {}

export interface PromptConfigQuery extends PageQuery {
  typeId?: string
  /**
   * 只看某一类。★ 不传 = 全部；调用方要"只看角色指令"必须显式传 `'ROLE'` ——
   * 给它一个默认值，提问模板就会悄悄混进应用配置的选用列表。
   */
  kind?: PromptKind
  appId?: string
}

export interface UpsertPromptTypeBody {
  typeName: string
  icon: string
}

export interface UpsertPromptConfigBody {
  kind?: PromptKind
  appId?: string | null
  roleName: string
  callWord: string
  typeId: string
  description: string
  icon: string
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listPromptTypes = (query: PromptTypeQuery = {}) =>
  request<PageResult<PromptType>>({ method: 'GET', url: '/prompt-types', query })

export const createPromptType = (body: UpsertPromptTypeBody) =>
  request<PromptType>({ method: 'POST', url: '/prompt-types', body })

export const updatePromptType = (id: string, body: Partial<UpsertPromptTypeBody>) =>
  request<PromptType>({ method: 'PUT', url: '/prompt-types/:id', path: { id }, body })

export const deletePromptType = (id: string) =>
  request<void>({ method: 'DELETE', url: '/prompt-types/:id', path: { id } })

export const listPromptConfigs = (query: PromptConfigQuery = {}) =>
  request<PageResult<PromptConfig>>({ method: 'GET', url: '/prompt-configs', query })

export const createPromptConfig = (body: UpsertPromptConfigBody) =>
  request<PromptConfig>({ method: 'POST', url: '/prompt-configs', body })

export const updatePromptConfig = (id: string, body: Partial<UpsertPromptConfigBody>) =>
  request<PromptConfig>({ method: 'PUT', url: '/prompt-configs/:id', path: { id }, body })

export const deletePromptConfig = (id: string) =>
  request<void>({ method: 'DELETE', url: '/prompt-configs/:id', path: { id } })

/* ────────────────────── 版本历史（ROADMAP §11.62） ────────────────────── */

/** 一版历史的摘要（列表用）。`versionNo` 越大越新。 */
export interface PromptConfigVersionSummary {
  versionNo: number
  savedAt: string
  savedBy: string | null
  /** 正文前 60 字 —— 列表里够辨认，看全文再按需取。 */
  preview: string
}

/** 某一版的全文；`callWord` 就是正文。 */
export interface PromptConfigVersionDetail {
  versionNo: number
  roleName: string
  callWord: string
  typeId: string
  description: string
  icon: string
  savedAt: string
  savedBy: string | null
}

export const listPromptConfigVersions = (id: string) =>
  request<PromptConfigVersionSummary[]>({
    method: 'GET',
    url: '/prompt-configs/:id/versions',
    path: { id }
  })

export const getPromptConfigVersion = (id: string, versionNo: number) =>
  request<PromptConfigVersionDetail>({
    method: 'GET',
    url: '/prompt-configs/:id/versions/:v',
    path: { id, v: versionNo }
  })

/**
 * 回滚到某一版。
 *
 * ★ 语义是"以那一版的内容再写一次"，服务端会生成**新的一版** —— 历史一条都不删。
 * 所以回滚之后版本数 +1，而不是回到当时的样子。
 */
export const restorePromptConfigVersion = (id: string, versionNo: number) =>
  request<PromptConfig>({
    method: 'POST',
    url: '/prompt-configs/:id/versions/:v/restore',
    path: { id, v: versionNo },
    body: {}
  })

/* ────────────────────── 提示词评测（ROADMAP §11.63） ────────────────────── */

/** 一套评测集。`itemCount` 是实时算的（题目随时可增删）。 */
export interface PromptEvalSet {
  id: string
  name: string
  createdAt: string
  itemCount: number
}

export interface PromptEvalItem {
  id: string
  ordinal: number
  question: string
}

export type EvalSide = 'SAVED' | 'DRAFT'

/** 三档里 `SAME` 是最常用的那个 —— 大多数改法本来就不改变答案。 */
export type EvalVerdict = 'BETTER' | 'SAME' | 'WORSE'

/** 一条答案。`verdict` / `note` 只在 DRAFT 侧有值（语义是"草稿相对基线"）。 */
export interface PromptEvalAnswer {
  id: string
  itemId: string
  ordinal: number
  question: string
  side: EvalSide
  answer: string
  elapsedMs: number | null
  passCount: number
  failCount: number
  verdict: EvalVerdict | null
  note: string | null
}

/**
 * 汇总。★ `winRate` 为 `null` 表示"还没评"，不是"胜率 0" —— 界面上必须分开显示。
 */
export interface PromptEvalSummary {
  winRate: number | null
  ratedItems: number
  totalItems: number
  better: number
  same: number
  worse: number
}

export interface PromptEvalRun {
  id: string
  setId: string
  baseVersionNo: number | null
  status: 'RUNNING' | 'DONE' | 'FAILED'
  itemTotal: number
  itemDone: number
  failReason: string | null
  startedAt: string
  finishedAt: string | null
  answers: PromptEvalAnswer[]
  summary: PromptEvalSummary
}

export const listPromptEvalSets = (configId: string) =>
  request<PromptEvalSet[]>({
    method: 'GET',
    url: '/prompt-configs/:id/eval-sets',
    path: { id: configId }
  })

export const createPromptEvalSet = (configId: string, name: string) =>
  request<{ id: string }>({
    method: 'POST',
    url: '/prompt-configs/:id/eval-sets',
    path: { id: configId },
    body: { name }
  })

export const deletePromptEvalSet = (setId: string) =>
  request<void>({ method: 'DELETE', url: '/prompt-eval-sets/:setId', path: { setId } })

export const listPromptEvalItems = (setId: string) =>
  request<PromptEvalItem[]>({
    method: 'GET',
    url: '/prompt-eval-sets/:setId/items',
    path: { setId }
  })

export const addPromptEvalItem = (setId: string, question: string) =>
  request<PromptEvalItem>({
    method: 'POST',
    url: '/prompt-eval-sets/:setId/items',
    path: { setId },
    body: { question }
  })

export const deletePromptEvalItem = (itemId: string) =>
  request<void>({ method: 'DELETE', url: '/prompt-eval-items/:itemId', path: { itemId } })

/**
 * 起一次评测。
 *
 * ★ 立刻返回 runId，真正跑在后台（30 题 = 60 次模型调用）。
 * `appId` 是执行环境：问题要经过真实检索才有意义，检索范围由应用决定。
 * `draftText` 是输入框里那版正文，服务端整段落库 —— 它可能永远不会被保存。
 */
export const startPromptEvalRun = (setId: string, appId: string, draftText: string) =>
  request<{ id: string }>({
    method: 'POST',
    url: '/prompt-eval-sets/:setId/runs',
    path: { setId },
    body: { appId, draftText }
  })

export const getPromptEvalRun = (runId: string) =>
  request<PromptEvalRun>({ method: 'GET', url: '/prompt-eval-runs/:runId', path: { runId } })

/** 评一档。评分写在"草稿相对基线"这一侧。 */
export const ratePromptEvalAnswer = (
  runId: string,
  itemId: string,
  verdict: EvalVerdict,
  note?: string
) =>
  request<void>({
    method: 'PUT',
    url: '/prompt-eval-runs/:runId/items/:itemId/rating',
    path: { runId, itemId },
    body: { verdict, note: note ?? null }
  })

export const clearPromptEvalRating = (runId: string, itemId: string) =>
  request<void>({
    method: 'DELETE',
    url: '/prompt-eval-runs/:runId/items/:itemId/rating',
    path: { runId, itemId }
  })
