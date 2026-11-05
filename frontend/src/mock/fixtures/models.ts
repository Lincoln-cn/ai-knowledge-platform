import type { BaseModelOption, ModelInfo, ModelReference } from '@/api/models'

/**
 * 模型数据。
 *
 * 刻意构造出目标设计里的几条关键结论，不是随手编的：
 *  - 思考模式三态（NONE / TOGGLEABLE / ALWAYS_ON）→ 决定应用配置里的开关如何呈现
 *  - 工具调用三态（UNSUPPORTED / SUPPORTED / PARALLEL）→ MVP 只声明不接口（V1.0 才可用）
 *  - 四种加载状态都要有实例 → 状态机在界面上真的能走全
 *  - 一个 ALWAYS_ON 的模型 → 用于验证「结构化生成模型候选中不出现」
 *  - 向量模型带 dimension、对话模型带 contextWindow
 */
export const models: ModelInfo[] = [
  {
    id: 'm1',
    name: 'qwen3:4b-instruct',
    kind: 'CHAT',
    supplier: 'LOCAL_INFERENCE',
    baseModel: 'Qwen3-4B-Instruct',
    status: 'LOADED',
    contextWindow: 32768,
    dimension: null,
    thinking: 'NONE',
    tools: 'SUPPORTED',
    builtin: true,
    remark: '内置对话模型，轻量、响应快'
  },
  // ★ #301：m2 / m3 绑在 provider-jairouter 下 —— "有模型的服务商"在 mock 下要有一份数据，
  //   删除守卫（弹确认框 / force 也 409）才走得通。m2 自己被应用引用（见 modelReferences）
  //   ⇒ 删服务商时 force 也拦，正是 issue 判据 1 的「有下游引用时 force 仍 409」场景。
  {
    id: 'm2',
    providerId: 'provider-jairouter',
    name: 'qwen3:8b',
    kind: 'CHAT',
    supplier: 'LOCAL_INFERENCE',
    baseModel: 'Qwen3-8B',
    status: 'LOADED',
    contextWindow: 32768,
    dimension: null,
    thinking: 'TOGGLEABLE',
    tools: 'PARALLEL',
    builtin: false,
    remark: '可开思考模式，用于合同审查等需要推理的场景'
  },
  {
    id: 'm3',
    providerId: 'provider-jairouter',
    name: 'qwen3:30b-a3b',
    kind: 'CHAT',
    supplier: 'LOCAL_INFERENCE',
    baseModel: 'Qwen3-30B-A3B',
    status: 'LOADING',
    contextWindow: 131072,
    dimension: null,
    thinking: 'ALWAYS_ON',
    tools: 'PARALLEL',
    builtin: false,
    remark: '大模型，加载耗时长；思考模式关不掉'
  },
  {
    id: 'm4',
    name: 'qwen2:7b-instruct',
    kind: 'CHAT',
    supplier: 'LOCAL_INFERENCE',
    baseModel: 'Qwen2-7B-Instruct',
    status: 'LOAD_FAILED',
    contextWindow: 32768,
    dimension: null,
    thinking: 'NONE',
    tools: 'UNSUPPORTED',
    builtin: false,
    remark: '加载失败：显存不足，需先释放资源',
    // 仅 LOAD_FAILED 时有值 —— 可读的排查原因，界面不能只显示「加载失败」四个字
    loadError: '上游清单里没有这个基础模型：Qwen2-7B-Instruct。请确认模型已在上游部署，并检查 apiBase 是否指向了正确的服务'
  },
  {
    id: 'm5',
    name: 'mofanke/dmeta-embedding-zh',
    kind: 'EMBEDDING',
    supplier: 'LOCAL_INFERENCE',
    baseModel: 'dmeta-embedding-zh',
    status: 'LOADED',
    contextWindow: null,
    dimension: 768,
    thinking: 'NONE',
    tools: 'UNSUPPORTED',
    builtin: true,
    remark: '中文向量模型。维度与向量表绑定，更换需全量重灌'
  },
  {
    id: 'm6',
    name: 'bge-m3',
    kind: 'EMBEDDING',
    supplier: 'LOCAL_INFERENCE',
    baseModel: 'BGE-M3',
    status: 'NOT_LOADED',
    contextWindow: null,
    dimension: 1024,
    thinking: 'NONE',
    tools: 'UNSUPPORTED',
    builtin: false,
    remark: '多语言向量模型，维度与当前向量表不一致'
  }
]

/** 可从供应商拉取的基础模型目录（原型里等同于 Ollama 的模型库） */
export const baseModels: BaseModelOption[] = [
  { baseModel: 'Qwen3-4B-Instruct', kind: 'CHAT', supplier: 'LOCAL_INFERENCE', contextWindow: 32768, dimension: null },
  { baseModel: 'Qwen3-8B', kind: 'CHAT', supplier: 'LOCAL_INFERENCE', contextWindow: 32768, dimension: null },
  { baseModel: 'Qwen3-14B', kind: 'CHAT', supplier: 'LOCAL_INFERENCE', contextWindow: 32768, dimension: null },
  { baseModel: 'Qwen3-30B-A3B', kind: 'CHAT', supplier: 'LOCAL_INFERENCE', contextWindow: 131072, dimension: null },
  { baseModel: 'Qwen2-7B-Instruct', kind: 'CHAT', supplier: 'LOCAL_INFERENCE', contextWindow: 32768, dimension: null },
  { baseModel: 'deepseek-r1:7b', kind: 'CHAT', supplier: 'LOCAL_INFERENCE', contextWindow: 65536, dimension: null },
  { baseModel: 'deepseek-r1:14b', kind: 'CHAT', supplier: 'LOCAL_INFERENCE', contextWindow: 65536, dimension: null },
  { baseModel: 'glm4:9b', kind: 'CHAT', supplier: 'LOCAL_INFERENCE', contextWindow: 131072, dimension: null },
  { baseModel: 'dmeta-embedding-zh', kind: 'EMBEDDING', supplier: 'LOCAL_INFERENCE', contextWindow: null, dimension: 768 },
  { baseModel: 'BGE-M3', kind: 'EMBEDDING', supplier: 'LOCAL_INFERENCE', contextWindow: null, dimension: 1024 },
  { baseModel: 'bge-large-zh-v1.5', kind: 'EMBEDDING', supplier: 'LOCAL_INFERENCE', contextWindow: null, dimension: 1024 },
  { baseModel: 'text-embedding-3-small', kind: 'EMBEDDING', supplier: 'EXTERNAL_API', contextWindow: null, dimension: 1536 }
]

/**
 * 指定 `apiBase` 时拉到的目录（外部 API 上游那套）。
 * ★ 与 `baseModels` 刻意**不重叠** —— mock 模式下要能看出「目录跟着上游走」，
 * 两套一样就分不清 apiBase 到底有没有被用上。
 */
export const externalBaseModels: BaseModelOption[] = [
  { baseModel: 'gpt-4o', kind: 'CHAT', supplier: 'EXTERNAL_API', contextWindow: 128000, dimension: null },
  { baseModel: 'gpt-4o-mini', kind: 'CHAT', supplier: 'EXTERNAL_API', contextWindow: 128000, dimension: null },
  { baseModel: 'claude-sonnet-4', kind: 'CHAT', supplier: 'EXTERNAL_API', contextWindow: 200000, dimension: null },
  { baseModel: 'text-embedding-3-large', kind: 'EMBEDDING', supplier: 'EXTERNAL_API', contextWindow: null, dimension: 3072 }
]

/**
 * 能力探测表：模拟「后端去问供应商」的结果。
 * 探测不到时返回 null，由用户在界面上手工声明 —— 界面必须区分标注来源。
 *
 * 刻意不给 `glm4:9b` 留条目：探测失败 → 手工声明这条路径必须在界面上能被走到，
 * 否则「探测失败不阻断」只是文档里的一句话。
 */
export const probedCapabilities: Record<string, { thinking: ModelInfo['thinking']; tools: ModelInfo['tools'] }> = {
  'Qwen3-4B-Instruct': { thinking: 'NONE', tools: 'SUPPORTED' },
  'Qwen3-8B': { thinking: 'TOGGLEABLE', tools: 'PARALLEL' },
  'Qwen3-14B': { thinking: 'TOGGLEABLE', tools: 'PARALLEL' },
  'Qwen3-30B-A3B': { thinking: 'ALWAYS_ON', tools: 'PARALLEL' },
  'deepseek-r1:7b': { thinking: 'ALWAYS_ON', tools: 'UNSUPPORTED' },
  'deepseek-r1:14b': { thinking: 'ALWAYS_ON', tools: 'UNSUPPORTED' },
  'Qwen2-7B-Instruct': { thinking: 'NONE', tools: 'UNSUPPORTED' }
}

/**
 * 模型的引用面（#301）：删模型前要查的"谁在用它"。
 *
 * ★ 形状与后端 `ModelReference` **同形**（`GET /models/:id/references` 与删除 409 的
 *   `data` 都是它）：apps / knowledgeBases / documents / defaultKeys / models / modelNames。
 *   mock 若自造一个简化的形状，「mock 与真栈同一个读数」就验不出来（#277 同族）。
 * ★ 登记表而不是"从别的 fixture 算"：mock 的应用/库 fixture 里并没有 model_id 指向这里，
 *   硬连起来只会造出两边都对不上的假关系；显式登记让"哪个模型有引用"成为可控的测试数据。
 * ★ 刻意覆盖两类：m2 被**应用**引用、m6 被**知识库 + 文档**引用 —— 删除守卫的两条分支
 *   （对话模型 / 向量模型）都能被走到；m3 / m4 无引用 ⇒ 可直接删的对照组。
 */
export const modelReferences: Record<string, ModelReference> = {
  m2: {
    apps: [{ id: 'as-contract-review', name: '合同审查助手' }],
    knowledgeBases: [],
    documents: 0,
    defaultKeys: [],
    models: 0,
    modelNames: []
  },
  m6: {
    apps: [],
    knowledgeBases: [{ id: 'kb-product-manual', name: '产品手册库' }],
    documents: 12,
    defaultKeys: [],
    models: 0,
    modelNames: []
  }
}

/** 空引用面：没有登记的模型 = 没有任何引用（与后端 `ModelReference.NONE` 同形）。 */
export const EMPTY_MODEL_REFERENCE: ModelReference = {
  apps: [],
  knowledgeBases: [],
  documents: 0,
  defaultKeys: [],
  models: 0,
  modelNames: []
}
