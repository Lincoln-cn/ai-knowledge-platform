import { request } from '@/lib/http'
import type { RetrievalParams } from './retrieval'
import type { PageQuery, PageResult } from './types'

/**
 * 编排（工作流）契约 —— **流程定义格式在此冻结**。
 *
 * 后端执行引擎尚未开工，而配置界面必须先能落地，所以这份格式是双方的对齐点：
 * 前端按它存取画布，后端按它实现引擎与版本管理。**改这里 = 破坏性变更。**
 *
 * 与旧系统的差异（有意为之）：
 *  - 旧系统枚举 17 种节点、只实现 7 种，10 种是「有枚举、有图标、无实现」的幽灵类型，
 *    在界面上会误导使用者。这里**保留全部枚举**（现 18 种：原 17 种 + 拆分出的数值判断），
 *    但用 `catalogEnabled` / `executable` 两个标记分开说「能不能拖」与「能不能跑」，
 *    —— 不让界面暗示一个不存在的能力。
 *  - 旧系统的节点配置散在各自的 `config` 里、无类型约束。这里按节点类型做成
 *    判别联合（discriminated union），配置面板与后端都按类型收窄。
 *  - 判断能力拆成两个节点：`CONDITION_NODE`（语义判断，自然语言由模型判定）与
 *    `NUMBER_CONDITION_NODE`（数值判断，变量/操作符/值结构化判定）。
 */

/* ────────────────────────── 节点类型 ────────────────────────── */

/**
 * 类型名单不在这里手写：它由 `scripts/generate-node-types.mjs` 从
 * `backend/src/main/resources/workflow/node-types.json` + 画布模块目录生成（#337）。
 *
 * ★ 以前这里是两份手写的联合类型（已实现 9 / 规划中 9），加一个节点要同时改后端 json、这里、
 *   `labels.ts`、`defaults.ts`、`graph-format.ts`、`data.ts` 六处 —— 漏一处不会报错，只会静默不生效。
 */
export type {
  ImplementedNodeType,
  NodeConfigField,
  NodeVariableField,
  PlannedNodeType,
  WorkflowNodeTypeName,
} from './nodeTypes.generated';
export {
  IMPLEMENTED_NODE_TYPES,
  NODE_CONFIG_FIELDS,
  NODE_DEFAULT_DATA,
  NODE_OUTPUT_FIELDS,
  NODE_TYPE_DESCRIPTIONS,
  NODE_TYPE_LABELS,
  NODE_TYPE_ORDER,
  PLANNED_NODE_TYPES,
  WORKFLOW_NODE_TYPE_VALUES,
} from './nodeTypes.generated';

import type {
  ImplementedNodeType,
  NodeConfigField,
  PlannedNodeType
} from './nodeTypes.generated';

export type WorkflowNodeType = ImplementedNodeType | PlannedNodeType

/** 节点目录条目。界面据此渲染节点菜单与「规划中」标注 */
export interface NodeTypeMeta {
  type: WorkflowNodeType
  label: string
  /** 一句话说明这个节点做什么 */
  description: string
  /**
   * 面板分组（如「基础」「检索」「多模态」）。
   *
   * 18 个节点平铺成一列找不动，必须分组 —— 所以它属于目录数据，
   * 由后端下发而不是前端各写一份分组表（否则新增节点时两边要同时改）。
   */
  category: string
  /**
   * 画布上能不能拖这个节点。
   *
   * ★ 与 `executable` 是**两件事**（#331）：今天有 3 个类型停在中间态
   * （`BASE_NODE` / `QUESTION_NODE` / `NUMBER_CONDITION_NODE`）——
   * 能拖进来、点发布才报错。合并成一个布尔时用户要到最后一步才知道白做了。
   */
  catalogEnabled: boolean
  /** 发布/运行时会不会被接受（等价于后端执行器真能跑它）。 */
  executable: boolean
  /** 是否只允许出现一个（如开始节点） */
  singleton: boolean
  /** 是否允许作为流程入口 */
  canBeEntry: boolean
  /**
   * 后端目录声明的配置字段（#337）。规划中的节点是空表。
   *
   * ★ 面板的最终形态由它驱动；今天 9+1 个面板还是手写的，所以这两个字段暂时只是
   *   「已经可用、还没人用」的状态 —— 与 `#336` 里那些悬空字段同形，但方向相反：
   *   这个是有真源、缺消费者；那个是有消费者假象、缺真源。
   */
  configFields?: NodeConfigField[]
  /**
   * 这个节点写进流程变量的键（#337），变量目录用。
   *
   * ★ 形状跟着后端目录走（`[{ key, label }]`），不是 `string[]` —— 变量下拉要显示中文，
   *   而中文名属于目录数据。以前这里声明成 `string[]` 且零消费者，属于"声明得比真源窄"。
   */
  outputs?: { key: string; label: string }[]
}

/* ────────────────────────── 节点配置（按类型判别） ────────────────────────── */

/**
 * 变量引用语法：`{{节点id.字段名}}`，在文本类配置里用。
 * 例：指定回复节点写「您好 {{base-node.userName}}，以下是检索结果」。
 */
export type VariableExpression = string

export interface StartNodeConfig {
  /** 开场白 */
  prologue: string
  /** 推荐问题 */
  quickQuestions: string[]
}

/** 基本信息收集的字段类型（2.4 补录） */
export type UserInputFieldType = 'TEXT' | 'SINGLE_SELECT' | 'MULTI_SELECT' | 'DATE' | 'NUMBER'

export interface UserInputField {
  /** 字段名，供其它节点用 {{nodeId.field}} 引用 */
  field: string
  label: string
  type: UserInputFieldType
  required: boolean
  placeholder: string
  /** 单选/多选的选项 */
  options?: string[]
}

/**
 * 接口传参表的每一项（`#337` 补的契约名）。
 *
 * <p>以前这张表只有画布侧的名字（`api_input_field_list`，项内是 `variable` / `name` /
 * `is_required`），契约里没有对应字段 —— `graph-format.ts` 自己写着「契约缺」。没有契约名就删不掉
 * legacy 转换函数，所以先给它起名。
 */
export interface ApiFieldConfig {
  /** 参数名 */
  key: string
  label: string
  required: boolean
  defaultValue?: string
}

export interface BaseNodeConfig {
  /** 基本信息收集的输入字段 */
  fields: UserInputField[]
  /** 调外部接口时传过去的参数（`#337` 补的契约名，面板在「接口传参」表里编辑） */
  apiFields: ApiFieldConfig[]
  /** 是否允许上传附件 */
  allowFileUpload: boolean
  /** 允许的文件类型（空表示不限） */
  fileAccept: string[]
}

export interface AiChatNodeConfig {
  modelId: string
  rolePrompt: string
  /** 引用的提示词模板 */
  promptIds: string[]
  kbIds: string[]
  retrieval: RetrievalParams
  thinkingEnabled: boolean
  /** 是否开启多轮上下文（`dialogueNumber > 0` 的布尔投影） */
  multiTurn: boolean
  /**
   * 提示词正文（旧画布名 `prompt`）。
   *
   * 契约里以前只有 `promptIds`（模板 id），而面板编辑的是正文 —— 于是正文只存在于画布侧
   * （`#337` 第二步 B3 起补成契约字段）。
   */
  prompt?: string
  /** 带几轮历史（旧画布名 `dialogue_number`）；0 表示不带 */
  dialogueNumber?: number
  /** 是否作为结果输出（旧画布名 `is_result`） */
  asResult?: boolean
  /** 模型参数（旧画布名 `model_params_setting`），形状由模型提供方决定 */
  modelParams?: Record<string, unknown>
}

export interface SearchDatasetNodeConfig {
  kbIds: string[]
  retrieval: RetrievalParams
  /** 检索问题从上游哪些变量取（旧画布名 `question_reference_address`） */
  questionReferenceAddress?: string[]
}

export interface QuestionNodeConfig {
  /** 问题改写所用模型；为空表示沿用上游模型 */
  modelId: string | null
  /** 改写提示词 */
  rewritePrompt: string
  /**
   * 非人工场景下的默认答复（定时触发等直接往下走时用）。
   *
   * 键名必须与后端 `QuestionNodeExecutor.CONFIG_DEFAULT_ANSWER`（`defaultAnswer`）一致 ——
   * 早先后端读的是旧画布名 `default_answer`，而契约里根本没有这一项，于是"配了默认答复"从来没生效过（`#335`）。
   */
  defaultAnswer: string
  /** 角色设定（旧画布名 `system`） */
  system?: string
  /** 带几轮历史（旧画布名 `dialogue_number`） */
  dialogueNumber?: number
  /** 是否作为结果输出（旧画布名 `is_result`） */
  asResult?: boolean
  /** 模型参数（旧画布名 `model_params_setting`） */
  modelParams?: Record<string, unknown>
}

/**
 * 语义判断分支（`condition-node`）。
 *
 * 条件是**自然语言表述**，由模型判定命中与否 —— 不支持手写脚本/表达式（安全边界）。
 */
export interface ConditionBranch {
  /** 分支标识，出边用它做 sourceHandle */
  id: string
  label: string
  /** 条件表述（自然语言），由模型判定；不支持手写脚本表达式（安全边界） */
  expression: string
}

/**
 * 语义判断节点（`condition-node`）。
 *
 * 每个分支写一句自然语言条件，运行时由模型判定走哪条；必须指定兜底分支。
 * 若需要「变量 + 操作符 + 值」的结构化判定，请用 `number-condition-node`。
 */
export interface ConditionNodeConfig {
  branches: ConditionBranch[]
  /** 所有分支都不命中时的兜底分支 id */
  fallbackBranchId: string | null
}

/**
 * 数值判断的操作符。取值与画布组件 `compareList` 一一对应，不另造词。
 */
export type NumberConditionOperator =
  | 'IS_NULL'
  | 'IS_NOT_NULL'
  | 'CONTAIN'
  | 'NOT_CONTAIN'
  | 'EQ'
  | 'GE'
  | 'GT'
  | 'LE'
  | 'LT'
  | 'LEN_EQ'
  | 'LEN_GE'
  | 'LEN_GT'
  | 'LEN_LE'
  | 'LEN_LT'

/**
 * 数值判断的单个条件：变量 ⨯ 操作符 ⨯ 值。
 *
 * `variable` 是 `{{节点id.字段名}}` 的结构化形式 `[节点id, 字段名]`，
 * `null` 表示尚未选择；`value` 在 `IS_NULL` / `IS_NOT_NULL` 下可为 `null`。
 */
export interface NumberConditionItem {
  variable: [string, string] | null
  operator: NumberConditionOperator
  value: string | null
}

/** 条件组内逻辑（2.4 补录）：AND = 全部命中，OR = 任一命中 */
export type ConditionLogic = 'AND' | 'OR'

/** 数值判断分支：组内条件按 `logic` 组合（AND = 全部命中，OR = 任一命中） */
export interface NumberConditionBranch {
  /** 分支标识，出边用它做 sourceHandle */
  id: string
  label: string
  logic: ConditionLogic
  conditions: NumberConditionItem[]
}

/**
 * 数值判断节点（`NUMBER_CONDITION_NODE`）。
 *
 * 每个分支是一组「变量/操作符/值」条件，运行时按值比较判定，不经过模型。
 * 与 `CONDITION_NODE`（语义判断）互补：一个结构化比值，一个自然语言判定。
 */
export interface NumberConditionNodeConfig {
  branches: NumberConditionBranch[]
  /** 所有分支都不命中时的兜底分支 id */
  fallbackBranchId: string | null
}

export interface ReplyNodeConfig {
  /** 回复内容，支持 {{变量}} 引用 */
  content: string
  /**
   * 回复形态。当前只有「自定义内容」一种，所以是可选且固定为 `'content'`。
   *
   * 键名必须与后端 `ReplyNodeExecutor.CONFIG_REPLY_TYPE`（`replyType`）一致 ——
   * 早先只有转换层会补旧画布名 `reply_type`，**默认配置里没有这一项**，
   * 于是「直接给契约形状」的调用方一定会碰到"缺少回复形态"（`#335`）。
   */
  replyType?: 'content'
  /**
   * 这条回复是否作为流程的结果呈现给用户。
   *
   * 键名与表单节点的同名（表单那边叫 `asResult`），旧画布名是 `is_result`。
   * 以前画布上的开关直接读写 `is_result`、契约里没有这一项，于是这一格
   * 只存在于"画布内部"（`#337` 第二步 B1 起统一到契约名）。
   */
  asResult: boolean
}

/**
 * 文档抽取节点（`DOCUMENT_EXTRACT_NODE`，`#333`）。
 *
 * ★ 配置里给的是**文档 id 而不是文件**：执行时回库取已解析的正文。理由三条 ——
 * ① 「解析库里已有的文档」比「解析刚上传的附件」常见（后者是会话附件链的事）；
 * ② 不往流程状态里塞 base64（会撑爆 `FlowState`），也不收服务器路径（那等于凭空多一个文件系统概念，
 * 且前端传路径就能读任意文件）；
 * ③ id 是 UUID，权限将来可由知识库可见性判据兜住，传路径做不到。
 */
export interface DocumentExtractNodeConfig {
  /**
   * 界面回填用：上一次从哪个库选的文档。
   *
   * ★ 执行器**不读**它（后端只按 `docId` 回库取正文）。加它是因为节点面板重新打开时，
   * 没有库 id 就没法把文档下拉列出来 —— 那样每次编辑都得先重选一遍库。
   */
  kbId: string | null
  /** 要抽正文的文档 id（`kb_document.id`）；`null` 表示还没选 */
  docId: string | null
}

/** 按节点类型取配置类型。规划中的节点暂无配置定义 */
/**
 * 表单收集节点（人工介入）。
 *
 * ★ 今天后端 `FormNodeExecutor` **不读任何配置**：它只把入口输入原样写进 `form_data`，
 *   目录里这个节点的 `configFields` 就是空数组。下面三个键是画布侧的表单定义，
 *   负责面板编辑与持久化往返；把它们接进执行期（字段级映射到 `form_data`）是另一笔账。
 */
export interface FormNodeConfig {
  /** 表单字段定义 */
  formFields: FormFieldConfig[]
  /** 提交前的引导文案，`{{form}}` 会被替换成表单本身 */
  contentFormat: string
  /** 填完是否作为整条流程的最终结果 */
  asResult: boolean
}

/** 表单里的一个字段 */
export interface FormFieldConfig {
  /** 写进 `form_data` 的键 */
  key: string
  label: string
  type: 'text' | 'textarea' | 'number' | 'date' | 'select'
  required: boolean
  /** `type === 'select'` 时的候选值 */
  options?: string[]
}

/** 重排节点（`RERANKER_NODE`）的配置 —— 它只对上游检索到的候选重新排序，不自己去查库。 */
export interface RerankerNodeConfig {
  /** 只重排前几名，其余保持原序；默认 5（与 `RerankPort` 的离线读数一致） */
  topK: number
  /** 用来比对的问题；留空则用流程变量 `query` */
  question: string
}

/**
 * 应用调用节点的配置。
 *
 * ★ 被调应用必须是**已发布**的编排应用：草稿允许不合规（`PUT /workflow/draft` 明确允许），
 * 把它接进别人的流程等于让"这条链的出口是谁"随时会变。运行时会拒绝，面板据此提前提示。
 */
export interface ApplicationNodeConfig {
  /** 被调应用（编排类型） */
  applicationId: string | null
  /** 版本号或版本主键；留空 = 最新已发布版本 */
  version?: string
}

/**
 * 图像理解节点的配置。
 *
 * ★ 用的是**对话模型**：视觉输入是对话协议的扩展（`messages[].content` 传数组），
 * 不是另一种模型种类。选模型时按 `kind=CHAT` 过滤，别去找"视觉模型"。
 *
 * ★ `imageKey` 指的是一格**流程变量**，那格里放的是文件键（`store` 返回的），不是图片本身。
 */
export interface ImageUnderstandNodeConfig {
  /** 对话模型 */
  modelId: string | null
  /** 问它什么；留空问「请客观描述这张图片的内容。」 */
  question: string
  /** 读哪个流程变量拿到图片键；留空读 `image_key` */
  imageKey: string
}

/**
 * 语音合成节点的配置。
 *
 * ★ `voice` 是必填：各上游的可选值完全不同（`alloy` / `nova` / `zh-CN-XiaoxiaoNeural`…），
 * 没有能给所有上游都成立的默认值 —— 猜一个的结果是"大部分时候能跑、换了服务商就 400"。
 */
export interface TextToSpeechNodeConfig {
  /** TTS 模型 */
  modelId: string | null
  /** 音色，上游决定取值范围 */
  voice: string
  /** 读哪个流程变量拿到要念的文字；留空读 `answer` */
  sourceKey: string
}

/** 语音识别节点的配置。`audioKey` 留空读 `audio_key`（与语音合成节点的产出一致，串起来不用改）。 */
export interface SpeechToTextNodeConfig {
  /** STT 模型 */
  modelId: string | null
  /** 读哪个流程变量拿到音频键；留空读 `audio_key` */
  audioKey: string
  /** 传给上游的文件名；留空用 `audio.mp3`（上游靠扩展名判容器格式） */
  filename: string
}

/** 图像生成节点的配置。`promptKey` 留空读 `query` —— 提示词通常来自用户问题或上游的总结。 */
export interface ImageGenerateNodeConfig {
  /** IMAGE 模型 */
  modelId: string | null
  /** 读哪个流程变量拿到提示词；留空读 `query` */
  promptKey: string
  /** 如 `1024x1024`；留空由上游决定 */
  size: string
}

/** 一条函数参数：要么是写死的字面量，要么点名一个流程变量。 */
export interface FunctionArgConfig {
  /** 省略按 `'literal'` 解释 —— 与执行器一致，见 `FunctionInvocation.resolveArgs` */
  from?: 'literal' | 'variable'
  value: string
}

/**
 * 函数节点的配置。
 *
 * <p>★ 它调的是**平台内置函数**（`upper` / `concat` / `jsonpath` 这一批），**不执行用户写的代码** ——
 * 执行自定义脚本要等 0.9.0 的沙箱（`ROADMAP §11.27.3`，判据是「沙箱能起、超时能杀」）。
 * 面板上要把这句写出来：一个"看起来能跑代码、实际跑不了"的节点比没有更糟。
 */
export interface FunctionNodeConfig {
  functionName: string | null
  args: FunctionArgConfig[]
  /** 结果写进哪个流程变量；留空写 `function_result` */
  outputKey: string
}

export type NodeConfigOf<T extends WorkflowNodeType> =
  T extends 'START_NODE' ? StartNodeConfig
  : T extends 'BASE_NODE' ? BaseNodeConfig
  : T extends 'AI_CHAT_NODE' ? AiChatNodeConfig
  : T extends 'SEARCH_DATASET_NODE' ? SearchDatasetNodeConfig
  : T extends 'QUESTION_NODE' ? QuestionNodeConfig
  : T extends 'CONDITION_NODE' ? ConditionNodeConfig
  : T extends 'NUMBER_CONDITION_NODE' ? NumberConditionNodeConfig
  : T extends 'REPLY_NODE' ? ReplyNodeConfig
  : T extends 'DOCUMENT_EXTRACT_NODE' ? DocumentExtractNodeConfig
  : T extends 'RERANKER_NODE' ? RerankerNodeConfig
  : T extends 'APPLICATION_NODE' ? ApplicationNodeConfig
  : T extends 'IMAGE_UNDERSTAND_NODE' ? ImageUnderstandNodeConfig
  : T extends 'TEXT_TO_SPEECH_NODE' ? TextToSpeechNodeConfig
  : T extends 'SPEECH_TO_TEXT_NODE' ? SpeechToTextNodeConfig
  : T extends 'IMAGE_GENERATE_NODE' ? ImageGenerateNodeConfig
  : T extends 'FUNCTION_LIB_NODE' ? FunctionNodeConfig
  : T extends 'FUNCTION_NODE' ? FunctionNodeConfig
  : T extends 'FORM_NODE' ? FormNodeConfig
  : Record<string, never>

export interface WorkflowNode<T extends WorkflowNodeType = WorkflowNodeType> {
  id: string
  type: T
  /** 画布上显示的标题，可与类型标签不同 */
  title: string
  position: { x: number; y: number }
  config: NodeConfigOf<T>
}

export interface WorkflowEdge {
  id: string
  source: string
  target: string
  /** 判断节点（语义/数值）的出边必须带 sourceHandle，取值来自分支 id */
  sourceHandle?: string
  /** 连线上的标签，便于读图 */
  label?: string
}

/** 流程图定义 —— 这是冻结的格式本体 */
export interface WorkflowGraph {
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
}

/**
 * 流程记录：编排应用每次会话对应一条执行记录。
 * 与「会话消息」是两套东西：消息是给人看的，记录是给排查用的。
 */
export interface WorkflowRecord {
  id: string
  appId: string
  conversationId: string | null
  /** 触发本次运行的输入。没有它，记录就只能看出"跑过"，看不出"为什么这样跑" */
  input: string
  /**
   * 走过的节点顺序。
   *
   * `title` 与 `status` 都记**执行当时**的值，不是回查当前图 ——
   * 流程改版后回查会把历史记录的节点名换成新版的名字，那是在篡改历史。
   * `type` 用于渲染节点图标，`enteredAt` 用于还原时间轴。
   */
  nodeTrail: Array<{
    nodeId: string
    type: WorkflowNodeType
    title: string
    status: 'SUCCEEDED' | 'FAILED'
    enteredAt: string
    elapsedMs: number
    failReason?: string | null
    /**
     * 并行扇出时"这个节点属于哪条支路"（`#112`）。
     *
     * 没有它，并行支路里的同名节点分不清归属。V1.0 不做并行扇出 ⇒ 当前记录里恒为 `null`，
     * 但**字段位置先留**：等引擎真支持并行再补，历史记录就永远补不回来了（同 `graphVersion` 的理由）。
     */
    parentNodeId?: string | null
  }>
  /**
   * 本次执行用的是**哪一版图**（`#112`）。
   *
   * ★ 为什么必须显式记：图发布是**可回滚的多版本**（`04 FR-04-08`），而"哪几版在什么时间发布"
   * 不在执行记录里 —— 想靠 `startedAt` 反推要跨表比时间序，且并发发布会算错。
   * 没有这个字段，改版之后历史记录就再也回答不了"当时跑的是哪一版"，观测也无法按图版本切片（`09 §5.3.2 ④`）。
   *
   * ★ 为什么是可选：本条落地前的存量记录没有它，必填会让旧数据变成脏数据（契约只加不删、不轻易收紧）。
   */
  graphVersion?: number
  /** 最终答复的摘要（截断）。完整答复在会话里，记录只做排查线索 */
  finalOutputPreview: string
  status: 'SUCCEEDED' | 'FAILED'
  failReason: string | null
  /** 整条记录的总耗时。不让界面自己把 trail 加起来 —— 两者的口径会漂移 */
  elapsedMs: number
  startedAt: string
  /**
   * 这次是谁发起的（`#295`）：`MANUAL` = 有人点了「试用」，`CRON` = 定时触发到点自己跑的。
   *
   * ★ 为什么记录里必须有这一格：定时触发的产出**没有人在场**，界面不标出来，用户看到一条新记录
   * 会以为是别人点的；反过来，排查"它到底有没有按点跑"也只能靠这一格。
   *
   * ★ 为什么是可选：0.7.3 之前的存量记录没有这一列的值（迁移给的默认值虽是 `MANUAL`，
   * 但契约只加不删、不轻易收紧 —— 同 `graphVersion` 的理由）。
   */
  triggerSource?: 'MANUAL' | 'CRON'
}

export interface WorkflowRecordQuery extends PageQuery {
  status?: WorkflowRecord['status']
}

/* ────────────────────────── 校验 ────────────────────────── */

/**
 * 校验问题。**保存与发布是两回事**（SRS 04 FR-04-06/08）：
 * 草稿允许不合规（方便边画边存），发布前必须零错误。
 */
export interface WorkflowValidationIssue {
  level: 'ERROR' | 'WARNING'
  code: string
  message: string
  /** 可读的下一步 */
  hint: string | null
  /** 关联到具体节点，便于界面高亮定位 */
  nodeId: string | null
}

export interface WorkflowValidationResult {
  passed: boolean
  issues: WorkflowValidationIssue[]
}

/* ────────────────────────── 版本 ────────────────────────── */

export interface WorkflowVersion {
  id: string
  /** 递增版本号 */
  version: number
  publishedAt: string
  /** 发布人用户 id（uuid）。界面上不显示它，只放进 `title` —— 同名的人靠它才分得开 */
  publishedBy: string
  /**
   * 发布人**现在**的显示名，服务端读列表时现查。
   *
   * ★ 刻意不是发布那一刻的快照：人名会改（改完要显示新名）、会软删（删完不该还挂着一个不存在的人），
   * 所以版本行里只存 id。这一点与审计表相反 —— 审计要回答「那一刻是谁」，那边必须存快照。
   *
   * `null` = 现在解析不到（用户被删，或这次查询失败）⇒ 界面显示「（未知用户）」，
   * **不要**退回显示 `publishedBy` 那个 uuid。
   */
  publishedByName: string | null
  /** 发布说明 */
  note: string
  nodeCount: number
}

/** 编排应用调试：不产生正式会话记录 */
export interface DebugWorkflowBody {
  graph: WorkflowGraph
  /** 按节点顺序提供输入，缺省用节点默认值 */
  inputs: Record<string, string>
}

export interface DebugWorkflowResult {
  finalOutput: string
  nodeTrail: WorkflowRecord['nodeTrail']
  elapsedMs: number
}

/* ────────────────────────── 接口 ────────────────────────── */

/** 节点目录。界面据此渲染节点菜单，并标注「规划中」的不可拖拽 */
export const listNodeTypes = () =>
  request<NodeTypeMeta[]>({ method: 'GET', url: '/workflow/node-types' })

/**
 * 内置函数名清单（函数节点 / 函数库节点的面板用）。
 *
 * <p>★ 由后端下发而不是前端写一份：后端加一个函数时前端不用改，且这份清单与执行器查的是同一张表，
 * 面板上能选的一定跑得通。端口没装配时后端返回空表（不报错）—— 面板据此仍允许手填函数名。
 */
export const listWorkflowFunctions = () =>
  request<string[]>({ method: 'GET', url: '/workflow/functions' })

/** 取编排应用的流程草稿 */
export const getWorkflowDraft = (appId: string) =>
  request<{ graph: WorkflowGraph; updatedAt: string }>({
    method: 'GET',
    url: '/assistants/:appId/workflow/draft',
    path: { appId }
  })

/** 保存草稿。允许不合规，方便边画边存 */
export const saveWorkflowDraft = (appId: string, graph: WorkflowGraph) =>
  request<{ savedAt: string }>({
    method: 'PUT',
    url: '/assistants/:appId/workflow/draft',
    path: { appId },
    body: { graph }
  })

/** 发布前校验。发布必须零错误；警告可放行但界面要提示 */
export const validateWorkflow = (appId: string, graph: WorkflowGraph) =>
  request<WorkflowValidationResult>({
    method: 'POST',
    url: '/assistants/:appId/workflow/validate',
    path: { appId },
    body: { graph }
  })

/** 发布：把草稿固化为一个版本 */
export const publishWorkflow = (appId: string, body: { graph: WorkflowGraph; note: string }) =>
  request<WorkflowVersion>({
    method: 'POST',
    url: '/assistants/:appId/workflow/publish',
    path: { appId },
    body
  })

export const listWorkflowVersions = (appId: string) =>
  request<WorkflowVersion[]>({
    method: 'GET',
    url: '/assistants/:appId/workflow/versions',
    path: { appId }
  })

/**
 * 取某一版的流程图。
 *
 * 版本列表**刻意不带图**（带的话列表会随历史线性膨胀），
 * 但「点开某一版看它长什么样、再决定要不要回滚」是真实流程 ⇒ 单独一个端点。
 */
export const getWorkflowVersionGraph = (appId: string, version: number) =>
  request<WorkflowGraph>({
    method: 'GET',
    url: '/assistants/:appId/workflow/versions/:version',
    path: { appId, version }
  })

/**
 * 回滚到某个历史版本：**把那一版的图写回草稿**，不发布、不动历史。
 *
 * ★ 返回**写回后的草稿**，页面拿到就能直接重画，不必再发一次 `getWorkflowDraft`：
 *   - `graph`   是写回后的图（服务端从版本行里原样抄过来，没有二次加工）；
 *   - `updatedAt` 是这次写草稿的时刻 —— 显示"已保存"用它，不要用本地 `Date.now()` 猜；
 *   - `version` 是被回滚到的那一版 —— 写「已回滚到 v3」用它，不要再翻一次版本列表去猜。
 *
 * ★ ★ 回滚**不改变线上行为**：线上跑的仍是上一次发布的那一版，直到用户自己再点一次「发布」。
 *   所以调用方不要把回滚当"切换线上"，也别在回滚成功后提示"已上线"。
 */
export const rollbackWorkflow = (appId: string, version: number) =>
  request<{ graph: WorkflowGraph; updatedAt: string; version: number }>({
    method: 'POST',
    url: '/assistants/:appId/workflow/versions/:version/rollback',
    path: { appId, version },
  })

/** 画布内调试，不产生正式会话记录 */
export const debugWorkflow = (appId: string, body: DebugWorkflowBody) =>
  request<DebugWorkflowResult>({
    method: 'POST',
    url: '/assistants/:appId/workflow/debug',
    path: { appId },
    body
  })

/** 从暂停点恢复一次执行。`graph` 必须与调试时同一张（后端要用它重编译计划、比对已完成的步数） */
export interface ResumeWorkflowBody {
  executionId: string
  /** 人工介入节点要的答复；表单节点给序列化后的 form_data */
  answer: string
  graph: WorkflowGraph
}

/**
 * 提交一次恢复。
 *
 * ★ 后端有这个端点了（`WorkflowResumeController`），但**界面还没有恢复入口** ——
 * 今天只有 e2e（`frontend/e2e/workflow-full-run.mjs`）直接调它。登记在这里的理由与 `#79` 的三格台账一致：
 * 「后端已落 + 契约已登记 + 零消费点」是一格可以核对的空白；不登记则连这格空白都看不见，
 * 而 `ContractDriftTest` 的规则 1 也正是为此存在（它第一次真的跑起来就抓到了这条）。
 */
export const resumeWorkflow = (appId: string, body: ResumeWorkflowBody) =>
  request<{ finalOutput: string; nodeTrail: WorkflowRecord['nodeTrail']; elapsedMs: number }>({
    method: 'POST',
    url: '/assistants/:appId/workflow/resume',
    path: { appId },
    body
  })

export const listWorkflowRecords = (appId: string, query: WorkflowRecordQuery = {}) =>
  request<PageResult<WorkflowRecord>>({
    method: 'GET',
    url: '/assistants/:appId/workflow/records',
    path: { appId },
    query
  })
