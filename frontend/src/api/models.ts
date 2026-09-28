import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 模型能力三态，决定界面开关如何呈现（思考模式） */
export type ThinkingMode = 'NONE' | 'TOGGLEABLE' | 'ALWAYS_ON'
/** 工具调用能力三态。MVP 只声明不接口（V1.0 才可用） */
export type ToolsMode = 'UNSUPPORTED' | 'SUPPORTED' | 'PARALLEL'
export type ModelKind = 'CHAT' | 'EMBEDDING'
export type ModelStatus = 'NOT_LOADED' | 'LOADING' | 'LOADED' | 'LOAD_FAILED'
export type Supplier = 'LOCAL_INFERENCE' | 'EXTERNAL_API'

export interface ModelInfo {
  id: string
  name: string
  kind: ModelKind
  supplier: Supplier
  /** 基础模型名，同一基础模型可注册多份配置 */
  baseModel: string
  status: ModelStatus
  /** 对话模型：上下文窗口；向量模型为 null */
  contextWindow: number | null
  /** 向量模型：输出维度；对话模型为 null。维度与向量表绑定，改则需全量重灌 */
  dimension: number | null
  thinking: ThinkingMode
  tools: ToolsMode
  /** 是否内置（硬编码不可删） */
  builtin: boolean
  remark?: string
  /**
   * 加载失败的可读排查原因。
   * 仅 `LOAD_FAILED` 时有值，是能照着排查的那句话（不是「加载失败」四个字）；
   * 其余状态省略本字段。冻结契约只加可选字段。
   */
  loadError?: string
}

/** 从供应商拉取到的可注册基础模型 */
export interface BaseModelOption {
  baseModel: string
  kind: ModelKind
  supplier: Supplier
  contextWindow: number | null
  dimension: number | null
}

/** 能力来源（2.4 补录）：探测 / 手工声明 */
export type ModelCapabilitySource = 'PROBED' | 'MANUAL'

/** 能力探测结果。来源标注是「探测」还是「手工声明」，界面必须区分展示 */
export interface ModelCapability {
  thinking: ThinkingMode
  tools: ToolsMode
  contextWindow: number | null
  dimension: number | null
  source: ModelCapabilitySource
}

export interface ProbeBaseModelBody {
  baseModel: string
  supplier: Supplier
  kind: ModelKind
}

/**
 * 基础模型目录查询入参。
 *
 * ★ 用 POST body 而不是查询串：`apiKey` 进 URL 会进日志 / 代理 / 浏览器历史。
 * `apiBase` / `apiKey` 为空时走默认上游（现行为不回归）；给了就拉指定上游的目录。
 */
export interface BaseModelsQuery {
  supplier?: Supplier
  kind?: ModelKind
  /** 指定上游服务地址；为空时用默认上游 */
  apiBase?: string
  /** 上游 API key；只进 body，绝不进 URL */
  apiKey?: string
}

export interface ModelQuery extends PageQuery {
  kind?: ModelKind
  status?: ModelStatus
}

export interface UpsertModelBody {
  name: string
  kind: ModelKind
  supplier: Supplier
  baseModel: string
  apiBase?: string
  apiKey?: string
  /** 向量维度必须显式下发 */
  dimension?: number | null
  thinking?: ThinkingMode
  tools?: ToolsMode
  remark?: string
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listModels = (query: ModelQuery = {}) =>
  request<PageResult<ModelInfo>>({ method: 'GET', url: '/models', query })

/**
 * 基础模型目录。★ 契约由 GET 改成 POST（密钥不能进查询串）；返回形状不变（BaseModelOption[]）。
 * 上游不可达时返回空数组，可读提示在包络的 `hint` 字段 —— 用 `onHint` 收（成功包络也可能带 hint）。
 */
export const listBaseModels = (body: BaseModelsQuery = {}, onHint?: (hint?: string) => void) =>
  request<BaseModelOption[]>({ method: 'POST', url: '/models/base-models', body, onHint })

export const createModel = (body: UpsertModelBody) =>
  request<ModelInfo>({ method: 'POST', url: '/models', body })

export const updateModel = (id: string, body: Partial<UpsertModelBody>) =>
  request<ModelInfo>({ method: 'PUT', url: '/models/:id', path: { id }, body })

export const deleteModel = (id: string) =>
  request<void>({ method: 'DELETE', url: '/models/:id', path: { id } })

/** 触发加载。加载是异步的，返回时的 status 仍是「加载中」(`LOADING`) */
export const loadModel = (id: string) =>
  request<ModelInfo>({ method: 'POST', url: '/models/:id/load', path: { id } })

/** 能力探测：探测失败不阻断，可由用户手工声明 */
export const probeModelCapability = (id: string) =>
  request<ModelCapability>({ method: 'POST', url: '/models/:id/probe', path: { id } })

/**
 * 按基础模型探测能力。
 *
 * 新建模型时 id 还不存在，但能力必须在保存前就填好，
 * 所以探测必须能在「未保存」状态下发生 —— 这是从旧实现迁移时发现的缺口。
 */
export const probeBaseModelCapability = (body: {
  baseModel: string
  supplier: Supplier
  kind: ModelKind
}) =>
  request<ModelCapability>({
    method: 'POST',
    url: '/models/probe',
    body,
    skipAuthRetry: true
  })
