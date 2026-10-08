/**
 * 枚举取值 → 中文 label 映射（唯一文案出口）。
 *
 * 规格：`docs/系统设计方案/14-枚举与取值命名规范.md`
 *  - 取值（标识符 = 线上值 = 库存值）一律 `SCREAMING_SNAKE_CASE`，多词用下划线。
 *  - 中文**只**出现在本文件的 label 映射里，不进契约、不进 JSON、不进库。
 *  - 不引 i18n 框架；要多语种时再说。
 *
 * ★ 例外：**可维护的清单**（密级 / 文种 / 库分类 / 提示词类型）的文案不在这里，而在基础数据字典
 *   （`08 §十`）—— 那是管理员随时能改的数据，写成常量就等于"改一次要发版"。本文件只放
 *   **契约枚举**的文案：取值由前后端两侧写死，改它本来就要一起发版。
 *
 * 用法：展示处查 `*_LABELS[code]`，筛选/下拉用 `*_VALUES` 常用取值数组。
 */

import type { DataScope, ResourceVisibility, SubjectKind } from '@/api/types'
import type { User } from '@/api/identity'
import type {
  ModelCapabilitySource,
  ModelKind,
  ModelStatus,
  Supplier,
  ThinkingMode,
  ToolsMode
} from '@/api/models'
import type { FeedbackStatus } from '@/api/feedback'
import type { NoRefStrategy, RetrievalMode } from '@/api/retrieval'
import type {
  ChatRole,
  MessageFeedback,
  MessageStatus,
  ShareScope
} from '@/api/conversations'
import type { ChatMode, StreamStatus, StreamTerminationPath } from '@/api/stream-types'
import type { DocumentStatus, SegmentEmbeddingStatus } from '@/api/ingestion'
import type { ImportType } from '@/api/knowledge'
import type {
  AssistantOrigin,
  AssistantType,
  BundleDependencyKind
} from '@/api/assistants'
import type {
  ConditionLogic,
  ImplementedNodeType,
  NumberConditionOperator,
  UserInputFieldType,
  WorkflowNodeType
} from '@/api/workflow'

export type UserStatus = User['status']

/* ────────────────────────── 归属与数据权限 ────────────────────────── */

export const DATA_SCOPE_LABELS: Record<DataScope, string> = {
  OWN: '仅自己',
  SHARED: '自己 + 共享',
  ALL: '全平台'
}
export const DATA_SCOPE_VALUES: DataScope[] = ['OWN', 'SHARED', 'ALL']

export const RESOURCE_VISIBILITY_LABELS: Record<ResourceVisibility, string> = {
  PRIVATE: '私有',
  SHARED: '共享',
  PUBLIC: '公开'
}
export const RESOURCE_VISIBILITY_VALUES: ResourceVisibility[] = ['PRIVATE', 'SHARED', 'PUBLIC']

export const SUBJECT_KIND_LABELS: Record<SubjectKind, string> = {
  USER: '用户',
  ROLE: '角色',
  ORG: '组织',
  GROUP: '群组',
  LINK: '链接'
}
export const SUBJECT_KIND_VALUES: SubjectKind[] = ['USER', 'ROLE', 'ORG', 'GROUP', 'LINK']

/* ────────────────────────── 身份与模型 ────────────────────────── */

export const USER_STATUS_LABELS: Record<UserStatus, string> = {
  ACTIVE: '正常',
  DISABLED: '停用',
  PENDING_REVIEW: '待审核'
}
export const USER_STATUS_VALUES: UserStatus[] = ['ACTIVE', 'DISABLED', 'PENDING_REVIEW']

export const MODEL_STATUS_LABELS: Record<ModelStatus, string> = {
  NOT_LOADED: '未加载',
  LOADING: '加载中',
  LOADED: '已加载',
  LOAD_FAILED: '加载失败'
}
export const MODEL_STATUS_VALUES: ModelStatus[] = [
  'NOT_LOADED',
  'LOADING',
  'LOADED',
  'LOAD_FAILED'
]

export const SUPPLIER_LABELS: Record<Supplier, string> = {
  LOCAL_INFERENCE: '本地推理',
  EXTERNAL_API: '外部API'
}
export const SUPPLIER_VALUES: Supplier[] = ['LOCAL_INFERENCE', 'EXTERNAL_API']

export const MODEL_KIND_LABELS: Record<ModelKind, string> = {
  CHAT: '对话模型',
  EMBEDDING: '向量模型'
}
export const MODEL_KIND_VALUES: ModelKind[] = ['CHAT', 'EMBEDDING']

/** 思考三态（取值已是规范大写，不动） */
export const THINKING_MODE_LABELS: Record<ThinkingMode, string> = {
  NONE: '无思考模式',
  TOGGLEABLE: '可开关',
  ALWAYS_ON: '强制开启'
}
export const THINKING_MODE_VALUES: ThinkingMode[] = ['NONE', 'TOGGLEABLE', 'ALWAYS_ON']

/** 工具调用三态（取值已是规范大写，不动） */
export const TOOLS_MODE_LABELS: Record<ToolsMode, string> = {
  UNSUPPORTED: '不支持工具',
  SUPPORTED: '支持工具调用',
  PARALLEL: '支持并行工具调用'
}
export const TOOLS_MODE_VALUES: ToolsMode[] = ['UNSUPPORTED', 'SUPPORTED', 'PARALLEL']

/* ────────────────────────── 反馈 / 检索 ────────────────────────── */

export const FEEDBACK_STATUS_LABELS: Record<FeedbackStatus, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已处理',
  IGNORED: '已忽略'
}
export const FEEDBACK_STATUS_VALUES: FeedbackStatus[] = [
  'PENDING',
  'PROCESSING',
  'RESOLVED',
  'IGNORED'
]

export const RETRIEVAL_MODE_LABELS: Record<RetrievalMode, string> = {
  VECTOR: '向量检索',
  FULLTEXT: '全文检索',
  HYBRID: '混合检索'
}
export const RETRIEVAL_MODE_VALUES: RetrievalMode[] = ['VECTOR', 'FULLTEXT', 'HYBRID']

/* ────────────────────────── 会话 / 流 ────────────────────────── */

export const MESSAGE_STATUS_LABELS: Record<MessageStatus, string> = {
  GENERATING: '生成中',
  GENERATED: '已生成',
  STOPPED: '已停止',
  FAILED: '失败'
}
export const MESSAGE_STATUS_VALUES: MessageStatus[] = [
  'GENERATING',
  'GENERATED',
  'STOPPED',
  'FAILED'
]

/** 终态三态。比 MessageStatus 少一个 GENERATING */
export const STREAM_STATUS_LABELS: Record<StreamStatus, string> = {
  GENERATED: '已生成',
  STOPPED: '已停止',
  FAILED: '失败'
}
export const STREAM_STATUS_VALUES: StreamStatus[] = ['GENERATED', 'STOPPED', 'FAILED']

export const CHAT_MODE_LABELS: Record<ChatMode, string> = {
  NEW: '新对话',
  REGENERATE: '重新生成',
  CONTINUE: '继续生成'
}
export const CHAT_MODE_VALUES: ChatMode[] = ['NEW', 'REGENERATE', 'CONTINUE']

export const SHARE_SCOPE_LABELS: Record<ShareScope, string> = {
  APP: '整应用',
  CONVERSATION: '整会话',
  MESSAGE: '单条消息'
}
export const SHARE_SCOPE_VALUES: ShareScope[] = ['APP', 'CONVERSATION', 'MESSAGE']

export const STREAM_TERMINATION_PATH_LABELS: Record<StreamTerminationPath, string> = {
  COMPLETE: '正常完成',
  ABORT: '用户停止',
  DISCONNECT: '连接断开',
  IDLE_TIMEOUT: '闲置超时'
}
export const STREAM_TERMINATION_PATH_VALUES: StreamTerminationPath[] = [
  'COMPLETE',
  'ABORT',
  'DISCONNECT',
  'IDLE_TIMEOUT'
]

/* ────────────────────────── 知识 / 应用 ────────────────────────── */

/**
 * 责任部门建议值。★ 保留常量而不是搬进字典：部门不是"一张可维护的清单"，而是**组织机构树的节点**
 *   （`07 §十`）—— 它要参与数据权限（「我的部门 + 下级」）、要能作为共享主体，
 *   做成字典项只是把同一个欠账换个地方存。组织树落地前，这里仍给建议值 + 允许自定义。
 */
export const DEPARTMENT_SUGGESTIONS = [
  '采购部',
  '财务部',
  '法务部',
  '人事部',
  '技术部',
  '行政部',
  '市场部',
  '生产部'
]

export const DOCUMENT_STATUS_LABELS: Record<DocumentStatus, string> = {
  PENDING_PARSE: '待解析',
  SEGMENTING: '分段中',
  PENDING_EMBEDDING: '待向量化',
  EMBEDDING: '向量化中',
  SUCCEEDED: '成功',
  SEGMENT_FAILED: '分段失败',
  EMBEDDING_FAILED: '向量化失败'
}
export const DOCUMENT_STATUS_VALUES: DocumentStatus[] = [
  'PENDING_PARSE',
  'SEGMENTING',
  'PENDING_EMBEDDING',
  'EMBEDDING',
  'SUCCEEDED',
  'SEGMENT_FAILED',
  'EMBEDDING_FAILED'
]

export const IMPORT_TYPE_LABELS: Record<ImportType, string> = {
  LOCAL: '本地文档',
  ONLINE: '在线抓取'
}
export const IMPORT_TYPE_VALUES: ImportType[] = ['LOCAL', 'ONLINE']

export const ASSISTANT_TYPE_LABELS: Record<AssistantType, string> = {
  SIMPLE: '简单',
  WORKFLOW: '编排'
}
export const ASSISTANT_TYPE_VALUES: AssistantType[] = ['SIMPLE', 'WORKFLOW']

export const ASSISTANT_ORIGIN_LABELS: Record<AssistantOrigin, string> = {
  OFFICIAL: '官方',
  SHARED: '共享给我',
  MINE: '我的'
}
export const ASSISTANT_ORIGIN_VALUES: AssistantOrigin[] = ['OFFICIAL', 'SHARED', 'MINE']

/* ────────────────────────── 工作流 ────────────────────────── */

/**
 * 节点类型默认标题（放入画布时的初始 title）。
 * 集中自 `pages/workflow/lib/defaults.ts` 的 `defaultTitleFor`。
 */
export const WORKFLOW_NODE_TYPE_LABELS: Record<ImplementedNodeType, string> = {
  START_NODE: '开始',
  BASE_NODE: '基本信息收集',
  AI_CHAT_NODE: 'AI 对话',
  SEARCH_DATASET_NODE: '知识库检索',
  QUESTION_NODE: '问题改写',
  CONDITION_NODE: '语义判断',
  NUMBER_CONDITION_NODE: '数值判断',
  REPLY_NODE: '指定回复'
}

/**
 * 单例节点在删除确认里的称呼（比默认标题更完整）。
 * 集中自 `pages/workflow/lib/node-actions.ts` 的 `SINGLETON_LABELS`。
 */
export const WORKFLOW_NODE_SINGLETON_LABELS: Record<string, string> = {
  START_NODE: '开始节点'
}

/** 已实现节点类型取值数组（菜单 / 校验用） */
export const WORKFLOW_NODE_TYPE_VALUES: ImplementedNodeType[] = [
  'START_NODE',
  'BASE_NODE',
  'AI_CHAT_NODE',
  'SEARCH_DATASET_NODE',
  'QUESTION_NODE',
  'CONDITION_NODE',
  'NUMBER_CONDITION_NODE',
  'REPLY_NODE'
]

/**
 * 数值判断操作符。
 * 取值与画布 `compareList` 一一对应，不另造词（2.2「按现值同样处理；多词用下划线」）。
 */
export const NUMBER_CONDITION_OPERATOR_LABELS: Record<NumberConditionOperator, string> = {
  IS_NULL: '为空',
  IS_NOT_NULL: '不为空',
  CONTAIN: '包含',
  NOT_CONTAIN: '不包含',
  EQ: '等于',
  GE: '大于等于',
  GT: '大于',
  LE: '小于等于',
  LT: '小于',
  LEN_EQ: '长度等于',
  LEN_GE: '长度大于等于',
  LEN_GT: '长度大于',
  LEN_LE: '长度小于等于',
  LEN_LT: '长度小于'
}
export const NUMBER_CONDITION_OPERATOR_VALUES: NumberConditionOperator[] = [
  'IS_NULL',
  'IS_NOT_NULL',
  'CONTAIN',
  'NOT_CONTAIN',
  'EQ',
  'GE',
  'GT',
  'LE',
  'LT',
  'LEN_EQ',
  'LEN_GE',
  'LEN_GT',
  'LEN_LE',
  'LEN_LT'
]

/** 一元操作符（不填值） */
export const NUMBER_CONDITION_UNARY_OPERATORS: ReadonlySet<NumberConditionOperator> = new Set([
  'IS_NULL',
  'IS_NOT_NULL'
])

/* ────────────────────────── 2.4 补录（表外 union） ────────────────────────── */

/** 流程记录 / 节点轨迹状态 */
export const WORKFLOW_RUN_STATUS_LABELS: Record<'SUCCEEDED' | 'FAILED', string> = {
  SUCCEEDED: '成功',
  FAILED: '失败'
}
export const WORKFLOW_RUN_STATUS_VALUES: Array<'SUCCEEDED' | 'FAILED'> = ['SUCCEEDED', 'FAILED']

/** 分段向量化状态 */
export const SEGMENT_EMBEDDING_LABELS: Record<SegmentEmbeddingStatus, string> = {
  PENDING_EMBEDDING: '待向量化',
  EMBEDDING: '向量化中',
  EMBEDDED: '已向量化',
  FAILED: '失败'
}
export const SEGMENT_EMBEDDING_VALUES: SegmentEmbeddingStatus[] = [
  'PENDING_EMBEDDING',
  'EMBEDDING',
  'EMBEDDED',
  'FAILED'
]

/** 无引用时的策略 */
export const NO_REF_STRATEGY_LABELS: Record<NoRefStrategy, string> = {
  REFUSE: '拒答',
  FREE_ANSWER: '自由回答'
}
export const NO_REF_STRATEGY_VALUES: NoRefStrategy[] = ['REFUSE', 'FREE_ANSWER']

/** 条件组内逻辑 */
export const CONDITION_LOGIC_LABELS: Record<ConditionLogic, string> = {
  AND: '且',
  OR: '或'
}
export const CONDITION_LOGIC_VALUES: ConditionLogic[] = ['AND', 'OR']

/**
 * 条件分支类型：`IF` / `ELSE IF n` / `ELSE`。
 * `ELSE IF n` 带序号、动态拼接，进不了静态表，故给函数与 `*_LABELS` 同级做唯一文案出口。
 */
export function branchTypeLabel(type: string | undefined | null): string {
  if (type == null || type === '') return ''
  if (type === 'IF') return '如果'
  if (type === 'ELSE') return '否则'
  const m = /^ELSE IF\s+(\d+)$/.exec(type)
  if (m) return `否则如果 ${m[1]}`
  return type
}

/** 能力来源 */
export const MODEL_CAPABILITY_SOURCE_LABELS: Record<ModelCapabilitySource, string> = {
  PROBED: '探测',
  MANUAL: '手工声明'
}
export const MODEL_CAPABILITY_SOURCE_VALUES: ModelCapabilitySource[] = ['PROBED', 'MANUAL']

/**
 * 基础模型 / 向量维度取值来源。
 * 与能力声明 PROBED/MANUAL 同思想，多一个「目录带出」——
 * 界面必须让人分得清「测出来的 / 目录带的 / 填的」，不能混成一个数字。
 */
export const VALUE_SOURCE_LABELS: Record<'CATALOG' | 'PROBED' | 'MANUAL', string> = {
  CATALOG: '目录',
  PROBED: '探测',
  MANUAL: '手填'
}

/** 消息角色 */
export const CHAT_ROLE_LABELS: Record<ChatRole, string> = {
  USER: '用户',
  ASSISTANT: '助手'
}
export const CHAT_ROLE_VALUES: ChatRole[] = ['USER', 'ASSISTANT']

/** 赞踩 */
export const MESSAGE_FEEDBACK_LABELS: Record<MessageFeedback, string> = {
  UP: '赞',
  DOWN: '踩'
}
export const MESSAGE_FEEDBACK_VALUES: MessageFeedback[] = ['UP', 'DOWN']

/** 基本信息收集的字段类型 */
export const USER_INPUT_FIELD_TYPE_LABELS: Record<UserInputFieldType, string> = {
  TEXT: '文本',
  SINGLE_SELECT: '单选',
  MULTI_SELECT: '多选',
  DATE: '日期',
  NUMBER: '数字'
}
export const USER_INPUT_FIELD_TYPE_VALUES: UserInputFieldType[] = [
  'TEXT',
  'SINGLE_SELECT',
  'MULTI_SELECT',
  'DATE',
  'NUMBER'
]

/** 流程校验问题级别 */
export const WORKFLOW_VALIDATION_LEVEL_LABELS: Record<'ERROR' | 'WARNING', string> = {
  ERROR: '错误',
  WARNING: '警告'
}
export const WORKFLOW_VALIDATION_LEVEL_VALUES: Array<'ERROR' | 'WARNING'> = ['ERROR', 'WARNING']

/** 导出包依赖种类 */
export const BUNDLE_DEPENDENCY_KIND_LABELS: Record<BundleDependencyKind, string> = {
  MODEL: '模型',
  KNOWLEDGE_BASE: '知识库',
  PROMPT: '提示词',
  WORKFLOW: '流程'
}
export const BUNDLE_DEPENDENCY_KIND_VALUES: BundleDependencyKind[] = [
  'MODEL',
  'KNOWLEDGE_BASE',
  'PROMPT',
  'WORKFLOW'
]

/** 分段切分策略 */
export const SEGMENTATION_STRATEGY_LABELS: Record<'AUTO' | 'CUSTOM', string> = {
  AUTO: '自动切分',
  CUSTOM: '自定义切分'
}
export const SEGMENTATION_STRATEGY_VALUES: Array<'AUTO' | 'CUSTOM'> = ['AUTO', 'CUSTOM']

/** 应用市场排序 */
export const MARKET_SORT_LABELS: Record<'POPULAR' | 'LATEST' | 'NAME', string> = {
  POPULAR: '人气',
  LATEST: '最新',
  NAME: '名称'
}
export const MARKET_SORT_VALUES: Array<'POPULAR' | 'LATEST' | 'NAME'> = ['POPULAR', 'LATEST', 'NAME']

/* ────────────────────────── 便捷查表 ────────────────────────── */

/** 按枚举查中文；未知码原样返回，避免界面出现 undefined */
export function labelOf<T extends string>(
  map: Record<T, string> | Record<string, string>,
  code: T | string | undefined | null
): string {
  if (code == null || code === '') return ''
  return (map as Record<string, string>)[code] ?? String(code)
}

export type { WorkflowNodeType }
