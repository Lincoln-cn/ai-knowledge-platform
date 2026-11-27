import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 模型能力三态，决定界面开关如何呈现（思考模式） */
export type ThinkingMode = 'NONE' | 'TOGGLEABLE' | 'ALWAYS_ON'
/** 工具调用能力三态。MVP 只声明不接口（V1.0 才可用） */
export type ToolsMode = 'UNSUPPORTED' | 'SUPPORTED' | 'PARALLEL'
export type ModelKind = 'CHAT' | 'EMBEDDING' | 'TTS' | 'STT' | 'IMAGE'
export type ModelStatus = 'NOT_LOADED' | 'LOADING' | 'LOADED' | 'LOAD_FAILED'
export type Supplier = 'LOCAL_INFERENCE' | 'EXTERNAL_API'

/**
 * 一条可调参数声明（对照后端 `ModelParamSchema.Param`，表列 `ai_model.param_schema`）。
 *
 * <p>`type` 的取值与后端一致：`number` / `integer` / `boolean` / `enum` / `string`；
 * `temperature` / `top_p` / `max_tokens` 三个官方键会被映射成上游请求体的同名字段，其余键原样透传。
 */
export interface ModelParamDecl {
  key: string
  label?: string
  type: 'number' | 'integer' | 'boolean' | 'enum' | 'string'
  default?: unknown
  min?: number | null
  max?: number | null
  options?: string[] | null
  help?: string
}

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
   * 可调参数声明（0.9.8，`#371` 第 135 条）：这个模型**支持**哪些采样参数。
   * 空数组/空串 = 没有暴露可调参数；表单要显示「这个模型没有暴露可调参数」而不是渲染一个空表单。
   */
  paramSchema?: string
  /** 绑定的服务商；null/缺省 = 旧式直连 / 默认上游 */
  providerId?: string | null
  providerName?: string
  providerKind?: import('./providers').ProviderKind
  egress?: import('./providers').ProviderEgress
  /** 旧式直连（没有 providerId）：界面要标出来，提示绑定服务商 */
  legacyDirect?: boolean
  /**
   * 这条模型**实际会打到的**上游地址（服务商地址或旧式直连地址）。
   * ★ 它是"证明这一次确实在量对的机器"的唯一凭据：`baseModel` 只是名字，名字不说明请求发到哪
   * （`#223` 的陷阱②：名字看着对、请求却打到 Ollama）。界面也用它回答"这个模型到底连的是谁"。
   */
  apiBase?: string
  /**
   * 加载失败的可读排查原因。
   * 仅 `LOAD_FAILED` 时有值，是能照着排查的那句话（不是「加载失败」四个字）；
   * 其余状态省略本字段。冻结契约只加可选字段。
   */
  loadError?: string
  /**
   * 引用面数量（`#300`）：有多少处东西指向这条模型（应用 + 知识库 + 文档 + 平台默认键）。
   * ★ 后端在列表里就给出这个数 —— 界面靠它在**点删除之前**标出"这条删不得"，
   *   而不是让用户点一次、吃一个 409 才知道。
   */
  referenceCount?: number
}

/** 引用面里的一条具名引用（应用 / 知识库）。 */
export interface ModelReferenceItem {
  id: string
  name: string
}

/**
 * 模型的引用面（`#300`）：删掉它之前会弄坏什么。
 *
 * ★ 形状由后端 `ModelReference` 决定；五类引用分别落在：
 * `as_assistant.model_id` / `kb_knowledge_base.embedding_model_id` / 这些库下的文档 /
 * `cv_chat_settings.default_model_id` / 服务商侧的 `ai_model.provider_id`（模型侧恒为 0）。
 */
export interface ModelReference {
  apps: ModelReferenceItem[]
  knowledgeBases: ModelReferenceItem[]
  /** 这些知识库下**未删**的文档数（文档跟着库用同一个向量模型，没有自己的列） */
  documents: number
  /** 平台默认键的可读名，如 `cv_chat_settings.default_model_id` */
  defaultKeys: string[]
  /** 服务商侧：绑定在它下面的模型数（模型侧恒为 0） */
  models: number
  modelNames: string[]
}

/** 从供应商拉取到的可注册基础模型 */
export interface BaseModelOption {
  baseModel: string
  kind: ModelKind
  supplier: Supplier
  contextWindow: number | null
  dimension: number | null
  /**
   * 后端真调一次得出的健康状态（`#1.7`）。
   *
   * ★ 网关的模型清单里**没有**健康字段（JAiRouter 的 `/v1/models` 只有 id/object/created/owned_by/
   * service_type/adapter），而它最常见的故障恰恰是「清单有、调用全挂」（adapterResolved=false）
   * —— 只看清单会得到一个绿灯的坏模型。
   */
  health: ModelHealth
  /** 不健康时是上游的原话（别改写成「失败」两个字）；健康或未探测时为 null */
  healthDetail: string | null
}

/**
 * 基础模型的调用健康状态。
 *
 * ★ 与 `ModelInfo.status` 是两回事：后者是已注册模型在本平台的**上游加载生命周期**（LOADING/LOADED/
 * LOAD_FAILED），这里是「这个基础模型调不调得通」。
 * ★ `UNKNOWN` = 探不了（没有上游地址 / 探测超时 / 多模态不做调用探测）—— **"不知道"不等于"不健康"**，
 * 不许据此拦住用户。
 */
export type ModelHealth = 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'

/**
 * 本地 ONNX（进程内向量化）状态。
 *
 * ★ 与 `ModelInfo.status` 是两套状态：后者是上游加载生命周期，前者是进程内懒加载。
 * 模型管理页必须显示这一份，不能用 `NOT_LOADED` 冒充（#191）。
 */
export interface LocalOnnxStatus {
  enabled: boolean
  mode: 'local-onnx' | 'upstream' | string
  /** 本地实现服务的 baseModel 名（路由按它分派） */
  model: string
  modelPath: string
  tokenizerPath: string
  filesPresent: boolean
  ready: boolean
  /** ready 时为 null；否则是「未启用 / 文件缺失 / 失败原因 / 尚未预热」 */
  reason: string | null
}

/**
 * 重排（cross-encoder）模型的状态（#352）。
 *
 * ★ 它不是注册模型：`ai_model` 里没有这一行，`ModelKind` 里也没有 `RERANK`。
 * 它由 `app.retrieval.rerank.*` 七个键 + 两个本地文件决定，所以界面上只能"看见"，不能"改"
 * —— 换重排模型 = 换 onnx 文件 + 重启 + 重取 B3 读数。
 */
export interface RerankStatus {
  enabled: boolean
  /** 目录名，如 bge-reranker-base */
  model: string
  modelPath: string
  tokenizerPath: string
  /** 重排窗口；★ 窗口才是那个旋钮，不是模型（见 RerankProperties 类注释的四组对照） */
  topK: number
  /** 从文件名推断：fp32 / int8。★ int8 在窗口 10 上掉 6.67 点 B3 */
  weights: string
  filesPresent: boolean
  /** 可用时为 null；否则是「未启用 / 文件缺失」 */
  reason: string | null
  listwiseEnabled: boolean
  listwiseTopK: number
  forwardBatch: number
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
  /** 指定服务商（可选）：给了就用它的地址与密钥 */
  providerId?: string
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
  /** 指定服务商（可选）：给了就用它的地址与密钥 */
  providerId?: string
}

export interface ModelQuery extends PageQuery {
  kind?: ModelKind
  status?: ModelStatus
  /** 只列这个服务商下的模型（服务商页的「N 个模型」点进来时用，`#289` 打磨） */
  providerId?: string
}

export interface UpsertModelBody {
  name: string
  kind: ModelKind
  supplier: Supplier
  baseModel: string
  apiBase?: string
  apiKey?: string
  /** 绑定的服务商；不给 = 旧式直连 / 默认上游；显式 null = 解绑 */
  providerId?: string | null
  /** 向量维度必须显式下发 */
  dimension?: number | null
  thinking?: ThinkingMode
  tools?: ToolsMode
  remark?: string
  /** 可调参数声明（JSON 数组字符串）。只有对话模型可以声明 */
  paramSchema?: string
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listModels = (query: ModelQuery = {}) =>
  request<PageResult<ModelInfo>>({ method: 'GET', url: '/models', query })

/**
 * 单条模型（含 `paramSchema` 声明）。
 *
 * <p>★ 编排节点的「参数设置」弹窗只拿得到 modelId，却要按这条模型的声明渲染表单 ——
 * 翻列表再 find 一遍在模型多起来之后会变成「弹窗打开慢半拍」，
 * 而且列表接口的字段是分页视图，拿它当详情用迟早会缺字段（`#371` 第 135 条）。
 */
export const getModel = (id: string) =>
  request<ModelInfo>({ method: 'GET', url: '/models/:id', path: { id } })

/** 本地 ONNX（进程内）的真实状态 —— 模型管理页的 ONNX 卡片/状态条读它 */
export const getLocalOnnxStatus = () =>
  request<LocalOnnxStatus>({ method: 'GET', url: '/models/local-onnx' })

/** 预热本地 ONNX（初始化 ONNX session，不是问上游） */
export const warmUpLocalOnnx = () =>
  request<LocalOnnxStatus>({ method: 'POST', url: '/models/local-onnx/warmup' })

/** 重排模型状态（#352）：只读，没有对应的写接口 */
export const getRerankStatus = () =>
  request<RerankStatus>({ method: 'GET', url: '/models/rerank' })

/**
 * 实体识别（NER）模型的只读状态（#369）。
 *
 * ★ 与 `RerankStatus` 同一类：它不是 `ai_model` 里的一行 —— NER 是进程内模型，
 *   `ModelKind` 里也没有 `NER`。它由 `app.entity.recognizer` + 模型目录决定。
 *   `configured` 与 `impl` 分开给：配的是 hanlp 却在跑 onnx 这种事，只有两个值都摆出来才看得出来。
 */
export interface NerStatus {
  /** 实现名：`onnx-bert-ner` / `hanlp` */
  impl: string
  /** 配置原值（`app.entity.recognizer`） */
  configured: string
  /** 解析后的模型目录；hanlp 实现为空串 */
  modelDir: string
  modelFilePresent: boolean
  tokenizerPresent: boolean
  /** ★ 标签表从这里读，缺了必然抽不出实体（见后端 OnnxNerRecognizer 类注释里那次踩坑） */
  configFilePresent: boolean
  /** 当前进程是否已把模型载入内存（onnx 惰性加载，第一次抽取才读） */
  loaded: boolean
  supportedTypes: string[]
  /** 可用时为 null；否则是不可用原因（文件缺失 / 加载失败原文） */
  reason: string | null
}

export const getNerStatus = () =>
  request<NerStatus>({ method: 'GET', url: '/models/ner' })

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

/**
 * 删除模型（`#300`：先查后删）。
 *
 * ★ 有引用时后端返回 **409**，`ApiError.details` 里就是 {@link ModelReference}（哪些应用 / 哪些库 / 多少文档 /
 * 哪个平台默认键）—— 界面据此展示明细，而不是只念一句"删除失败"。
 *
 * @param id 模型 id
 * @param force 明知还有引用仍要删（后端会 WARN 留痕；**不会**静默改动引用它的行）
 */
export const deleteModel = (id: string, force = false) =>
  request<void>({
    method: 'DELETE',
    url: '/models/:id',
    path: { id },
    query: force ? { force: true } : undefined
  })

/** 读模型的引用面（`#300`）：删除按钮显示「N 处关联」、强制删除弹框列明细都靠它。 */
export const getModelReferences = (id: string) =>
  request<ModelReference>({ method: 'GET', url: '/models/:id/references', path: { id } })

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
  providerId?: string
}) =>
  request<ModelCapability>({
    method: 'POST',
    url: '/models/probe',
    body,
    skipAuthRetry: true
  })
