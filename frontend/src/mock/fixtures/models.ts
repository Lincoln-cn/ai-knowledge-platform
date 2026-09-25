import type { BaseModelOption, ModelInfo } from '@/api/models'

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
    kind: 'chat',
    supplier: '本地推理',
    baseModel: 'Qwen3-4B-Instruct',
    status: '已加载',
    contextWindow: 32768,
    dimension: null,
    thinking: 'NONE',
    tools: 'SUPPORTED',
    builtin: true,
    remark: '内置对话模型，轻量、响应快'
  },
  {
    id: 'm2',
    name: 'qwen3:8b',
    kind: 'chat',
    supplier: '本地推理',
    baseModel: 'Qwen3-8B',
    status: '已加载',
    contextWindow: 32768,
    dimension: null,
    thinking: 'TOGGLEABLE',
    tools: 'PARALLEL',
    builtin: false,
    remark: '可开思考模式，用于合同审查等需要推理的场景'
  },
  {
    id: 'm3',
    name: 'qwen3:30b-a3b',
    kind: 'chat',
    supplier: '本地推理',
    baseModel: 'Qwen3-30B-A3B',
    status: '加载中',
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
    kind: 'chat',
    supplier: '本地推理',
    baseModel: 'Qwen2-7B-Instruct',
    status: '加载失败',
    contextWindow: 32768,
    dimension: null,
    thinking: 'NONE',
    tools: 'UNSUPPORTED',
    builtin: false,
    remark: '加载失败：显存不足，需先释放资源'
  },
  {
    id: 'm5',
    name: 'mofanke/dmeta-embedding-zh',
    kind: 'embedding',
    supplier: '本地推理',
    baseModel: 'dmeta-embedding-zh',
    status: '已加载',
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
    kind: 'embedding',
    supplier: '本地推理',
    baseModel: 'BGE-M3',
    status: '未加载',
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
  { baseModel: 'Qwen3-4B-Instruct', kind: 'chat', supplier: '本地推理', contextWindow: 32768, dimension: null },
  { baseModel: 'Qwen3-8B', kind: 'chat', supplier: '本地推理', contextWindow: 32768, dimension: null },
  { baseModel: 'Qwen3-14B', kind: 'chat', supplier: '本地推理', contextWindow: 32768, dimension: null },
  { baseModel: 'Qwen3-30B-A3B', kind: 'chat', supplier: '本地推理', contextWindow: 131072, dimension: null },
  { baseModel: 'Qwen2-7B-Instruct', kind: 'chat', supplier: '本地推理', contextWindow: 32768, dimension: null },
  { baseModel: 'deepseek-r1:7b', kind: 'chat', supplier: '本地推理', contextWindow: 65536, dimension: null },
  { baseModel: 'deepseek-r1:14b', kind: 'chat', supplier: '本地推理', contextWindow: 65536, dimension: null },
  { baseModel: 'glm4:9b', kind: 'chat', supplier: '本地推理', contextWindow: 131072, dimension: null },
  { baseModel: 'dmeta-embedding-zh', kind: 'embedding', supplier: '本地推理', contextWindow: null, dimension: 768 },
  { baseModel: 'BGE-M3', kind: 'embedding', supplier: '本地推理', contextWindow: null, dimension: 1024 },
  { baseModel: 'bge-large-zh-v1.5', kind: 'embedding', supplier: '本地推理', contextWindow: null, dimension: 1024 },
  { baseModel: 'text-embedding-3-small', kind: 'embedding', supplier: '外部API', contextWindow: null, dimension: 1536 }
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
