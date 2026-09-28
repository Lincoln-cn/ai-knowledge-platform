import { request } from '@/lib/http'
import type { RetrievalParams } from './retrieval'
import type { Grant, PageQuery, PageResult, ResourceVisibility } from './types'
import type { WorkflowGraph } from './workflow'

/* ────────────────────────── 契约类型 ────────────────────────── */

/**
 * 应用类型。**创建时选定，之后不可更改**（SRS 02 / FR-04）。
 *
 * 「简单」只需要提示词约束 + 知识库 + 检索参数，配置面在本文件；
 * 「编排」是画布式流程，配置面在 `api/workflow.ts`（流程定义格式在那里冻结）。
 * 两者共用 Assistant 的元信息（名称、图标、发布态、凭证），差异只在配置体。
 */
export type AssistantType = 'SIMPLE' | 'WORKFLOW'

export interface Assistant {
  id: string
  name: string
  icon: string
  description: string
  type: AssistantType
  /** 内置应用不可编辑对话设定，仅供问答 */
  builtin: boolean
  published: boolean
  /**
   * 对话模型。
   *
   * **编排应用没有这个字段** —— 它的模型在各 AI 节点里，可以一个流程用多个模型，
   * 应用壳上放一个"默认模型"会让人以为整条流程就用那一个（列表卡片上就是这个误导）。
   * 简单应用必填。
   */
  modelId?: string
  modelName?: string
  /** 编排应用的流程摘要；简单应用没有这个字段 */
  workflow?: WorkflowBrief
  kbIds: string[]
  /** 角色设定 */
  rolePrompt: string
  /** 使用的提示词模板 id 列表 */
  promptIds: string[]
  retrieval: RetrievalParams
  /** 是否开启多轮上下文 */
  multiTurn: boolean
  thinkingEnabled: boolean
  /** 开场白 */
  prologue: string
  /** 推荐问题 */
  quickQuestions: string[]
  callCount: number
  tokenCount: number
  satisfaction: number | null
  /** 归属与可见性（07 §3.1）。由后端写入，创建时 ownerId = 当前用户 / private */
  ownerId?: string
  /**
   * 归属人显示名。广场卡片要回答「这应用是谁给的」—— 只有 `ownerId` 这个 id
   * 显示不出来（#8），所以列表/详情响应补这一个可选字段。
   */
  ownerName?: string
  visibility?: ResourceVisibility
  grants?: Grant[]
  /** 分类/标签（市场筛选用）。可选 —— 老数据可以没有 */
  tags?: string[]
  /** 创建时间（市场「最新」排序用）。可选 */
  createdAt?: string
  /** 当前用户是否已收藏。收藏按用户存，故在响应装饰里下发 */
  favorited?: boolean
}

/**
 * 编排应用在**列表层**需要的摘要。
 *
 * 列表做不到"每张卡片再打一次 workflow 接口"（N+1），所以摘要随应用一起下发。
 * 它只够列表显示，**不是**流程真相源 —— 真相源永远是 `api/workflow.ts` 的草稿与版本。
 */
export interface WorkflowBrief {
  /** 画布节点数 */
  nodeCount: number
  /** 线上版本号；从未发布为 null */
  publishedVersion: number | null
}

/**
 * 广场的来源分段（§六）：官方 / 他人分享 / 我的。
 * 判据是 `builtin` + `ownerId`，可客户端分流；作为查询参数时由服务端在分页前过滤。
 */
export type AssistantOrigin = 'OFFICIAL' | 'SHARED' | 'MINE'

export interface AssistantQuery extends PageQuery {
  published?: boolean
  /** 按类型筛选；不传为全部 */
  type?: AssistantType
  /** 只看自己名下的（07 §六 阶段 1）。不传 = 按数据范围给全部可见的 */
  mine?: boolean
  /** 按来源分段筛选；不传为全部 */
  origin?: AssistantOrigin
  /** 排序：人气（调用次数）/ 最新（创建时间）/ 名称。不传 = 服务端默认序（2.4 补录换码） */
  sort?: 'POPULAR' | 'LATEST' | 'NAME'
  /** 只看已收藏 */
  favorite?: boolean
  /** 按标签筛选 */
  tag?: string
}

/**
 * 新建/更新应用。
 *
 * `type` 只在**创建**时需要：创建后不允许更改（改成编排或反之等于换了一个应用，
 * 应新建）。更新时后端应忽略该字段，传了也不生效。
 * 编排应用的对话配置不在本结构里，走 `api/workflow.ts` 的流程定义。
 */
export interface UpsertAssistantBody {
  name: string
  icon: string
  description: string
  type: AssistantType
  modelId: string
  kbIds: string[]
  rolePrompt: string
  promptIds: string[]
  retrieval: RetrievalParams
  multiTurn: boolean
  thinkingEnabled: boolean
  prologue: string
  quickQuestions: string[]
}

/** 发布前校验项。任一项不通过则不允许发布（AS-I2~I5） */
export interface PublishCheckItem {
  key: string
  label: string
  passed: boolean
  /** 不通过时的可读原因与修法 */
  detail: string | null
}

export interface PublishCheckResult {
  passed: boolean
  items: PublishCheckItem[]
}

/** 免登录访问凭证。撤销是终态，不可重新启用 */
export interface AccessCredential {
  id: string
  token: string
  enabled: boolean
  /** 有效期；null 表示长期有效 */
  expiresAt: string | null
  accessCount: number
  lastAccessAt: string | null
  revokedAt: string | null
  createdAt: string
}

/** 程序化调用的 API Key，与访问凭证是两套机制（06 FR-06-04） */
export interface ApiKeyItem {
  id: string
  key: string
  enabled: boolean
  createdAt: string
  lastUsedAt: string | null
}

/**
 * 导出包里的配置体。
 *
 * 比 `UpsertAssistantBody` 多一个可选 `workflow`：编排应用的流程定义随包走，
 * 否则导出再导入后画布是空的，得从零重画。简单应用没有这个字段。
 */
export interface AssistantBundleConfig extends UpsertAssistantBody {
  workflow?: WorkflowGraph
}

/** 导出包：配置 + 依赖清单，可选带文档内容 */
export interface AssistantBundle {
  format: string
  exportedAt: string
  includesDocuments: boolean
  config: AssistantBundleConfig
  dependencies: BundleDependency[]
}

/** 依赖种类（2.4 补录）。「WORKFLOW」是编排应用的画布定义；简单应用的清单里没有这一档 */
export type BundleDependencyKind = 'MODEL' | 'KNOWLEDGE_BASE' | 'PROMPT' | 'WORKFLOW'

export interface BundleDependency {
  /** 「WORKFLOW」是编排应用的画布定义；简单应用的清单里没有这一档 */
  kind: BundleDependencyKind
  name: string
  /** 描述，如「本地推理 · 已加载」「128 个文档 · 412 个分段」 */
  detail: string
  /** 在导入方环境是否存在 */
  satisfied: boolean
  /** 缺失时的降级后果，导入方必须看到 */
  degrade: string | null
}

export interface BundleCheckResult {
  format: string
  legacy: boolean
  dependencies: BundleDependency[]
  /** 存在缺失项时为 true；此时必须显式确认「跳过引用导入」才允许导入 */
  hasMissing: boolean
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listAssistants = (query: AssistantQuery = {}) =>
  request<PageResult<Assistant>>({ method: 'GET', url: '/assistants', query })

export const getAssistant = (id: string) =>
  request<Assistant>({ method: 'GET', url: '/assistants/:id', path: { id } })

export const createAssistant = (body: UpsertAssistantBody) =>
  request<Assistant>({ method: 'POST', url: '/assistants', body })

export const updateAssistant = (id: string, body: Partial<UpsertAssistantBody>) =>
  request<Assistant>({ method: 'PUT', url: '/assistants/:id', path: { id }, body })

export const deleteAssistant = (id: string) =>
  request<void>({ method: 'DELETE', url: '/assistants/:id', path: { id } })

/** 发布前校验。前端在点「发布」之前先调，把不通过项列给人看 */
export const checkAssistantBeforePublish = (id: string) =>
  request<PublishCheckResult>({ method: 'GET', url: '/assistants/:id/publish-check', path: { id } })

export const setAssistantPublished = (id: string, published: boolean) =>
  request<Assistant>({
    method: 'PATCH',
    url: '/assistants/:id/published',
    path: { id },
    body: { published }
  })

/** 调试问答不产生正式会话记录，故走独立接口 */
export const debugAssistant = (id: string, question: string) =>
  request<{ answer: string; refs: unknown[]; elapsedMs: number; tokens: number }>({
    method: 'POST',
    url: '/assistants/:id/debug',
    path: { id },
    body: { question }
  })

/* ── 分享写（07 §3.1 / §8.1；阶段 1 只落了可选响应字段，分享是写） ── */

/**
 * 改可见性：`private` 仅自己 · `shared` 指定主体 · `public` 登录用户里人人可见。
 * ★ `public` 不等于免登录 —— 免登录只有访问凭证那条链路。
 */
export const setAssistantVisibility = (id: string, visibility: ResourceVisibility) =>
  request<Assistant>({
    method: 'PATCH',
    url: '/assistants/:id/visibility',
    path: { id },
    body: { visibility }
  })

/** 覆盖式写入共享授权。主体形状见 `Grant`（MVP 只填 user / role 两种） */
export const setAssistantGrants = (id: string, grants: Grant[]) =>
  request<Assistant>({
    method: 'PUT',
    url: '/assistants/:id/grants',
    path: { id },
    body: { grants }
  })

/* ── 收藏（市场） ── */

/** 收藏。幂等：重复收藏不报错 */
export const favoriteAssistant = (id: string) =>
  request<Assistant>({ method: 'POST', url: '/assistants/:id/favorite', path: { id } })

/** 取消收藏。幂等 */
export const unfavoriteAssistant = (id: string) =>
  request<Assistant>({ method: 'DELETE', url: '/assistants/:id/favorite', path: { id } })

/** 取全部标签（市场筛选用）。静态段路由，排在 :id 之前 */
export const listAssistantTags = () =>
  request<string[]>({ method: 'GET', url: '/assistants/tags' })

/* ── 访问凭证 ── */

export const listCredentials = (appId: string) =>
  request<AccessCredential[]>({ method: 'GET', url: '/assistants/:appId/credentials', path: { appId } })

export const createCredential = (appId: string, body: { expiresAt: string | null }) =>
  request<AccessCredential>({
    method: 'POST',
    url: '/assistants/:appId/credentials',
    path: { appId },
    body
  })

export const setCredentialEnabled = (appId: string, id: string, enabled: boolean) =>
  request<AccessCredential>({
    method: 'PATCH',
    url: '/assistants/:appId/credentials/:id/enabled',
    path: { appId, id },
    body: { enabled }
  })

/** 撤销为终态：撤销后不可重新启用，只能重新生成 */
export const revokeCredential = (appId: string, id: string) =>
  request<AccessCredential>({
    method: 'POST',
    url: '/assistants/:appId/credentials/:id/revoke',
    path: { appId, id }
  })

/* ── API Key ── */

export const listApiKeys = (appId: string) =>
  request<ApiKeyItem[]>({ method: 'GET', url: '/assistants/:appId/api-keys', path: { appId } })

export const regenerateApiKey = (appId: string) =>
  request<ApiKeyItem>({ method: 'POST', url: '/assistants/:appId/api-keys', path: { appId } })

/** 单条启停。形状与 `setCredentialEnabled` 同构：停用后 Key 保留，可再启用 */
export const setApiKeyEnabled = (appId: string, id: string, enabled: boolean) =>
  request<ApiKeyItem>({
    method: 'PATCH',
    url: '/assistants/:appId/api-keys/:id/enabled',
    path: { appId, id },
    body: { enabled }
  })

/* ── 导入导出 ── */

export const exportAssistant = (id: string, options: { includeDocuments: boolean }) =>
  request<AssistantBundle>({
    method: 'GET',
    url: '/assistants/:id/export',
    path: { id },
    query: options
  })

export const checkBundleDependencies = (bundle: Pick<AssistantBundle, 'format' | 'dependencies'>) =>
  request<BundleCheckResult>({ method: 'POST', url: '/assistants/import/check', body: bundle })

export const importAssistant = (body: {
  bundle: AssistantBundle
  skipMissingReferences: boolean
}) => request<Assistant>({ method: 'POST', url: '/assistants/import', body })
