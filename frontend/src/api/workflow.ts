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
 *    但用 `implemented` 显式标记，未实现的在节点菜单里标注「规划中」且不可拖拽
 *    —— 不让界面暗示一个不存在的能力。
 *  - 旧系统的节点配置散在各自的 `config` 里、无类型约束。这里按节点类型做成
 *    判别联合（discriminated union），配置面板与后端都按类型收窄。
 *  - 判断能力拆成两个节点：`CONDITION_NODE`（语义判断，自然语言由模型判定）与
 *    `NUMBER_CONDITION_NODE`（数值判断，变量/操作符/值结构化判定）。
 */

/* ────────────────────────── 节点类型 ────────────────────────── */

/** 已实现的节点类型（8 种） */
export type ImplementedNodeType =
  | 'START_NODE'
  | 'BASE_NODE'
  | 'AI_CHAT_NODE'
  | 'SEARCH_DATASET_NODE'
  | 'QUESTION_NODE'
  | 'CONDITION_NODE'
  | 'NUMBER_CONDITION_NODE'
  | 'REPLY_NODE'

/** 规划中的节点类型（10 种）—— V1.0 之后逐批实现 */
export type PlannedNodeType =
  | 'FUNCTION_LIB_NODE'
  | 'FUNCTION_NODE'
  | 'RERANKER_NODE'
  | 'APPLICATION_NODE'
  | 'DOCUMENT_EXTRACT_NODE'
  | 'IMAGE_UNDERSTAND_NODE'
  | 'FORM_NODE'
  | 'TEXT_TO_SPEECH_NODE'
  | 'SPEECH_TO_TEXT_NODE'
  | 'IMAGE_GENERATE_NODE'

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
  implemented: boolean
  /** 是否只允许出现一个（如开始节点） */
  singleton: boolean
  /** 是否允许作为流程入口 */
  canBeEntry: boolean
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

export interface BaseNodeConfig {
  /** 基本信息收集的输入字段 */
  fields: UserInputField[]
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
  /** 是否开启多轮上下文 */
  multiTurn: boolean
}

export interface SearchDatasetNodeConfig {
  kbIds: string[]
  retrieval: RetrievalParams
}

export interface QuestionNodeConfig {
  /** 问题改写所用模型；为空表示沿用上游模型 */
  modelId: string | null
  /** 改写提示词 */
  rewritePrompt: string
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
}

/** 按节点类型取配置类型。规划中的节点暂无配置定义 */
export type NodeConfigOf<T extends WorkflowNodeType> =
  T extends 'START_NODE' ? StartNodeConfig
  : T extends 'BASE_NODE' ? BaseNodeConfig
  : T extends 'AI_CHAT_NODE' ? AiChatNodeConfig
  : T extends 'SEARCH_DATASET_NODE' ? SearchDatasetNodeConfig
  : T extends 'QUESTION_NODE' ? QuestionNodeConfig
  : T extends 'CONDITION_NODE' ? ConditionNodeConfig
  : T extends 'NUMBER_CONDITION_NODE' ? NumberConditionNodeConfig
  : T extends 'REPLY_NODE' ? ReplyNodeConfig
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
  publishedBy: string
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

/** 回滚到某个历史版本（生成一份新的草稿，不动历史） */
export const rollbackWorkflow = (appId: string, version: number) =>
  request<{ graph: WorkflowGraph }>({
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

export const listWorkflowRecords = (appId: string, query: WorkflowRecordQuery = {}) =>
  request<PageResult<WorkflowRecord>>({
    method: 'GET',
    url: '/assistants/:appId/workflow/records',
    path: { appId },
    query
  })
