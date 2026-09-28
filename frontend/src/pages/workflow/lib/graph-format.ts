import type {
  AiChatNodeConfig,
  BaseNodeConfig,
  ConditionBranch,
  ConditionNodeConfig,
  ImplementedNodeType,
  NumberConditionBranch,
  NumberConditionItem,
  NumberConditionNodeConfig,
  QuestionNodeConfig,
  ReplyNodeConfig,
  SearchDatasetNodeConfig,
  StartNodeConfig,
  UserInputField,
  WorkflowEdge,
  WorkflowGraph,
  WorkflowNode,
  WorkflowNodeType
} from '@/api/workflow'
import type { RetrievalMode, RetrievalParams } from '@/api/retrieval'
import { cloneDeep } from 'lodash-es'
import { nodeDict } from '@/pages/workflow/components/common/data'

/**
 * 图格式转换：**契约格式 ↔ LogicFlow 格式**，含**按节点类型的字段级映射**。
 *
 * | 概念 | 契约（后端接口）        | LogicFlow（画布内部 / 旧组件）           |
 * |------|------------------------|------------------------------------------|
 * | 坐标 | `position: { x, y }`   | `x` / `y`                                |
 * | 标题 | `title`                | `properties.stepName`                    |
 * | 配置 | `config`               | `properties.node_data`（旧表单字段名）    |
 * | 输出 | （无，由类型决定）      | `properties.config.fields`（参数输出目录） |
 * | 边   | `source` / `target`    | `sourceNodeId` / `targetNodeId`          |
 *
 * `type` 两边取值**恰好一致**（都取 `WorkflowNodeType` 字符串）。
 *
 * **契约里没有、旧组件却要读写的字段**（如 `dialogue_number` / `model_params_setting`）
 * 一律挂在 config 的同级键上**透传**，并在下方逐个注明「契约缺」。
 * 双向转换必须可逆：`fromLogicFlow(toLogicFlow(g))` 不丢不变形。
 */

export interface LogicFlowGraph {
  nodes: Array<Record<string, unknown>>
  edges: Array<Record<string, unknown>>
}

/* ────────────────────────── 共享：检索参数 ────────────────────────── */

const searchModeToRetrieval: Record<string, RetrievalMode> = {
  embedding: 'VECTOR',
  keywords: 'FULLTEXT',
  blend: 'HYBRID'
}
const retrievalToSearchMode: Record<string, string> = {
  向量检索: 'embedding',
  全文检索: 'keywords',
  混合检索: 'blend'
}

/** 旧 `dataset_setting` → 契约 `RetrievalParams`；契约缺 `max_paragraph_char_number`，放进 extras */
function datasetSettingToRetrieval(ds: any): { retrieval: RetrievalParams; extra: Record<string, unknown> } {
  const mode = searchModeToRetrieval[ds?.search_mode] ?? '混合检索'
  const retrieval: RetrievalParams = {
    mode,
    threshold: typeof ds?.similarity === 'number' ? ds.similarity : 0.6,
    topN: typeof ds?.top_n === 'number' ? ds.top_n : 5,
    // 自由回答 ↔ ai_questioning（继续问模型）；拒答 ↔ designated_answer
    noRefStrategy: ds?.no_references_setting?.status === 'ai_questioning' ? 'FREE_ANSWER' : 'REFUSE'
  }
  return {
    retrieval,
    extra: {
      // 契约缺：最大引用字符数
      max_paragraph_char_number: ds?.max_paragraph_char_number ?? 5000,
      // 契约缺：无引用时的完整设置（含指定回答文案）
      no_references_setting: ds?.no_references_setting ?? {
        status: 'ai_questioning',
        value: '{question}'
      }
    }
  }
}

function retrievalToDatasetSetting(
  retrieval: RetrievalParams | undefined,
  extras?: Record<string, unknown>
): Record<string, unknown> {
  const r = retrieval ?? { mode: '混合检索', threshold: 0.6, topN: 5, noRefStrategy: 'REFUSE' }
  const maxChars = (extras?.max_paragraph_char_number as number) ?? 5000
  const noRef = (extras?.no_references_setting as any) ?? {
    status: r.noRefStrategy === 'FREE_ANSWER' ? 'ai_questioning' : 'designated_answer',
    value: r.noRefStrategy === 'FREE_ANSWER' ? '{question}' : ''
  }
  return {
    search_mode: retrievalToSearchMode[r.mode] ?? 'blend',
    similarity: r.threshold,
    top_n: r.topN,
    // 契约缺：最大引用字符数
    max_paragraph_char_number: maxChars,
    // 契约缺：无引用完整策略
    no_references_setting: noRef
  }
}

/* ────────────────────────── 共享：用户输入字段 ────────────────────────── */

const contractTypeToInputType: Record<string, string> = {
  文本: 'TextInput',
  单选: 'SingleSelect',
  多选: 'MultiSelect',
  日期: 'DatePicker',
  数字: 'Slider'
}
const inputTypeToContractType: Record<string, UserInputField['type']> = {
  TextInput: 'TEXT',
  Slider: 'NUMBER',
  SwitchInput: 'TEXT',
  SingleSelect: 'SINGLE_SELECT',
  MultiSelect: 'MULTI_SELECT',
  RadioCard: 'SINGLE_SELECT',
  DatePicker: 'DATE'
}

function userFieldToLegacy(f: UserInputField & Record<string, any>): Record<string, any> {
  return {
    field: f.field,
    label: f.label,
    input_type: f.input_type || contractTypeToInputType[f.type] || 'TextInput',
    // 保留契约 type，保证无损回写
    type: f.type,
    required: !!f.required,
    default_value: f.default_value ?? '',
    attrs: f.attrs ?? { maxlength: 20, minlength: 0, placeholder: f.placeholder ?? '' },
    option_list:
      f.option_list ??
      (f.options ?? []).map((o: string) => ({ key: o, value: o, label: o })),
    // 契约缺：tooltip
    tooltip: f.tooltip
  }
}

function userFieldFromLegacy(f: any): UserInputField & Record<string, any> {
  const label =
    f?.label && typeof f.label === 'object' && f.label.input_type === 'TooltipLabel'
      ? f.label.label
      : (f?.label ?? f?.name ?? '')
  const options: string[] | undefined = f?.option_list
    ? f.option_list.map((o: any) => (typeof o === 'string' ? o : (o.value ?? o.key)))
    : f?.optionList
  const type: UserInputField['type'] =
    f?.type === 'TEXT' || f?.type === 'SINGLE_SELECT' || f?.type === 'MULTI_SELECT' || f?.type === 'DATE' || f?.type === 'NUMBER'
      ? f.type
      : inputTypeToContractType[f?.input_type] ?? '文本'
  return {
    field: f?.field ?? f?.variable ?? '',
    label,
    type,
    required: f?.required ?? f?.is_required ?? false,
    placeholder: f?.attrs?.placeholder ?? f?.placeholder ?? '',
    ...(options && options.length ? { options } : {}),
    // 契约缺以下字段，透传
    default_value: f?.default_value,
    input_type: f?.input_type,
    attrs: f?.attrs,
    option_list: f?.option_list,
    tooltip: f?.tooltip
  }
}

/* ────────────────────────── 按节点类型：config → 旧 node_data ────────────────────────── */

type LegacyBundle = {
  node_data: Record<string, any>
  /** base-node 的用户输入字段表（properties.user_input_field_list） */
  user_input_field_list?: any[]
  /** base-node 的接口传参表（properties.api_input_field_list）——契约缺 */
  api_input_field_list?: any[]
  /** condition-node 的分支锚点表（properties.branch_condition_list） */
  branch_condition_list?: any[]
  /** condition-node 的分支锚点高度表（properties.branch_condition_list 旧键） */
  width?: number
  height?: number
}

function startToLegacy(config: StartNodeConfig & Record<string, any>): LegacyBundle {
  return {
    node_data: {
      prologue: config.prologue ?? '',
      quickQuestions: config.quickQuestions ?? []
    }
  }
}

function startFromLegacy(nd: any): StartNodeConfig & Record<string, any> {
  return {
    prologue: nd?.prologue ?? '',
    quickQuestions: nd?.quickQuestions ?? []
  }
}

function baseToLegacy(config: BaseNodeConfig & Record<string, any>): LegacyBundle {
  const fields = (config.fields ?? []).map(userFieldToLegacy)
  const accept = config.fileAccept ?? []
  const setting = config.file_upload_setting ?? {
    maxFiles: 3,
    fileLimit: 50,
    document: accept.length === 0 || accept.some((a) => /docx?|pdf|md|txt|xlsx?|csv|html/i.test(a)),
    image: accept.some((a) => /jpe?g|png|gif|webp/i.test(a)),
    audio: false,
    video: false
  }
  return {
    node_data: {
      // 契约缺：应用名 / 描述（旧 UI 在 base-node 上编辑）
      name: config.name ?? '',
      desc: config.desc ?? '',
      // 开场白在契约里属 StartNodeConfig；旧 UI 在 base-node 上编辑，这里镜像一份
      prologue: config.prologue ?? '',
      file_upload_enable: !!config.allowFileUpload,
      file_upload_setting: setting,
      // 契约缺：tts / stt
      tts_type: config.tts_type ?? 'BROWSER',
      tts_model_enable: config.tts_model_enable ?? false,
      tts_model_id: config.tts_model_id ?? '',
      tts_model_params_setting: config.tts_model_params_setting,
      stt_model_enable: config.stt_model_enable ?? false,
      stt_model_id: config.stt_model_id ?? ''
    },
    user_input_field_list: fields,
    // 契约缺：接口传参表
    api_input_field_list: config.api_input_field_list ?? []
  }
}

function baseFromLegacy(nd: any, props: any): BaseNodeConfig & Record<string, any> {
  const legacyFields: any[] = props?.user_input_field_list ?? []
  const fields = legacyFields.map(userFieldFromLegacy)
  const setting = nd?.file_upload_setting
  const accept: string[] = Array.isArray(nd?.fileAccept)
    ? nd.fileAccept
    : setting
      ? [
          ...(setting.document ? ['.pdf', '.docx', '.md'] : []),
          ...(setting.image ? ['.png', '.jpg'] : [])
        ]
      : []
  return {
    fields,
    allowFileUpload: !!nd?.file_upload_enable,
    fileAccept: accept,
    // 契约缺以下字段，透传
    name: nd?.name,
    desc: nd?.desc,
    // 开场白契约里在 start-node，这里保留 base-node 上的编辑值，由图级同步写回
    prologue: nd?.prologue,
    file_upload_setting: setting,
    tts_type: nd?.tts_type,
    tts_model_enable: nd?.tts_model_enable,
    tts_model_id: nd?.tts_model_id,
    tts_model_params_setting: nd?.tts_model_params_setting,
    stt_model_enable: nd?.stt_model_enable,
    stt_model_id: nd?.stt_model_id,
    api_input_field_list: props?.api_input_field_list ?? []
  }
}

function aiChatToLegacy(config: AiChatNodeConfig & Record<string, any>): LegacyBundle {
  const { retrieval, extra } = { retrieval: config.retrieval, extra: config }
  return {
    node_data: {
      model_id: config.modelId ?? '',
      system: config.rolePrompt ?? '',
      // 契约缺：提示词正文（契约只有 promptIds）
      prompt: config.prompt ?? defaultChatPrompt(),
      // 契约缺：历史轮数 / 范围 / 是否返回
      dialogue_number: config.dialogue_number ?? (config.multiTurn ? 1 : 0),
      dialogue_type: config.dialogue_type ?? 'WORKFLOW',
      is_result: config.is_result ?? false,
      model_params_setting: config.model_params_setting,
      // 检索：UI 在 search-dataset 节点编辑，但契约在 ai-chat 上也有，保留
      dataset_id_list: config.kbIds ?? [],
      dataset_setting: retrievalToDatasetSetting(retrieval, extra as any),
      // 契约有、旧 UI 无编辑点
      prompt_ids: config.promptIds ?? [],
      thinking_enabled: !!config.thinkingEnabled
    }
  }
}

function defaultChatPrompt() {
  return `已知信息：
{{知识库检索.data}}
问题：
{{开始.question}}`
}

function aiChatFromLegacy(nd: any): AiChatNodeConfig & Record<string, any> {
  const { retrieval, extra } = datasetSettingToRetrieval(nd?.dataset_setting)
  return {
    modelId: nd?.model_id ?? '',
    rolePrompt: nd?.system ?? '',
    promptIds: nd?.prompt_ids ?? [],
    kbIds: nd?.dataset_id_list ?? [],
    retrieval,
    thinkingEnabled: !!nd?.thinking_enabled,
    multiTurn: (nd?.dialogue_number ?? 0) > 0,
    // 契约缺以下字段，透传
    prompt: nd?.prompt,
    dialogue_number: nd?.dialogue_number,
    dialogue_type: nd?.dialogue_type,
    is_result: nd?.is_result,
    model_params_setting: nd?.model_params_setting,
    ...extra
  }
}

function searchToLegacy(config: SearchDatasetNodeConfig & Record<string, any>): LegacyBundle {
  return {
    node_data: {
      dataset_id_list: config.kbIds ?? [],
      dataset_setting: retrievalToDatasetSetting(config.retrieval, config as any),
      // 契约缺：检索问题引用地址
      question_reference_address: config.question_reference_address ?? []
    }
  }
}

function searchFromLegacy(nd: any): SearchDatasetNodeConfig & Record<string, any> {
  const { retrieval, extra } = datasetSettingToRetrieval(nd?.dataset_setting)
  return {
    kbIds: nd?.dataset_id_list ?? [],
    retrieval,
    // 契约缺以下字段，透传
    question_reference_address: nd?.question_reference_address ?? [],
    ...extra
  }
}

function questionToLegacy(config: QuestionNodeConfig & Record<string, any>): LegacyBundle {
  return {
    node_data: {
      model_id: config.modelId ?? '',
      // 改写提示词 ↔ 旧 `prompt`
      prompt: config.rewritePrompt ?? '',
      // 契约缺：角色设定
      system: config.system ?? '你是一个问题优化大师',
      // 契约缺：历史轮数 / 是否返回 / 模型参数
      dialogue_number: config.dialogue_number ?? 1,
      is_result: config.is_result ?? false,
      model_params_setting: config.model_params_setting
    }
  }
}

function questionFromLegacy(nd: any): QuestionNodeConfig & Record<string, any> {
  return {
    modelId: nd?.model_id || null,
    rewritePrompt: nd?.prompt ?? '',
    // 契约缺以下字段，透传
    system: nd?.system,
    dialogue_number: nd?.dialogue_number,
    is_result: nd?.is_result,
    model_params_setting: nd?.model_params_setting
  }
}

function conditionToLegacy(config: ConditionNodeConfig & Record<string, any>): LegacyBundle {
  const branches = config.branches ?? []
  const fallback = config.fallbackBranchId
  const branch = branches.map((b: ConditionBranch & Record<string, any>, i: number) => ({
    id: b.id,
    type: b.type ?? (b.id === fallback ? 'ELSE' : i === 0 ? 'IF' : `ELSE IF ${i}`),
    condition: b.condition ?? 'and',
    // 契约缺：结构化条件列表
    conditions: b.conditions ?? [{ field: [], compare: '', value: '' }],
    label: b.label,
    expression: b.expression
  }))
  return {
    node_data: {
      branch,
      // 契约缺：兜底分支 id（旧用 type==='ELSE' 表达，这里双写保证可逆）
      _fallbackBranchId: fallback
    },
    // 条件节点锚点靠这张表算 y 坐标，缺了会报「无法获取 endAnchor」
    branch_condition_list: branch.map((b: any, i: number) => ({
      id: b.id,
      height: 12,
      index: i
    }))
  }
}

function conditionFromLegacy(nd: any): ConditionNodeConfig & Record<string, any> {
  const branch: any[] = nd?.branch ?? []
  const branches: Array<ConditionBranch & Record<string, any>> = branch.map((b) => ({
    id: b.id,
    label: b.label ?? b.type ?? b.id,
    expression: b.expression ?? '',
    // 契约缺以下字段，透传
    type: b.type,
    condition: b.condition,
    conditions: b.conditions
  }))
  const fallback =
    nd?._fallbackBranchId ?? branch.find((b) => b.type === 'ELSE')?.id ?? null
  return {
    branches,
    fallbackBranchId: fallback
  }
}

/**
 * 数值判断：契约 NumberConditionNodeConfig ↔ 旧 node_data 字段名。
 *
 * | 契约（NumberConditionItem/Branch） | 旧 properties.node_data            |
 * |------------------------------------|------------------------------------|
 * | `variable: [nodeId, field]\|null`  | `conditions[].field`（数组，空=未选）|
 * | `operator`                         | `conditions[].compare`             |
 * | `value`                            | `conditions[].value`               |
 * | `logic: 'AND'\|'或'`                | `condition: 'and'\|'or'`            |
 * | `branches`                         | `branch`                           |
 * | `fallbackBranchId`                 | `_fallbackBranchId`（双写 type=ELSE）|
 */
function numberConditionToLegacy(
  config: NumberConditionNodeConfig & Record<string, any>
): LegacyBundle {
  const branches = config.branches ?? []
  const fallback = config.fallbackBranchId
  const branch = branches.map((b: NumberConditionBranch & Record<string, any>, i: number) => ({
    id: b.id,
    type: b.type ?? (b.id === fallback ? 'ELSE' : i === 0 ? 'IF' : `ELSE IF ${i}`),
    // 契约 logic ↔ 旧 condition（组件读写旧名）
    condition: b.condition ?? (b.logic === 'OR' ? 'or' : 'and'),
    logic: b.logic ?? (b.condition === 'or' ? '或' : '且'),
    // 契约 NumberConditionItem ↔ 旧 conditions[].field/compare/value
    conditions: (b.conditions ?? []).map((c: NumberConditionItem & Record<string, any>) => ({
      field: c.field ?? c.variable ?? [],
      compare: c.compare ?? c.operator ?? '',
      value: c.value ?? '',
      // 契约形状一并带上，保证 to→from 可逆
      variable: c.variable ?? (Array.isArray(c.field) && c.field.length >= 2 ? c.field : null),
      operator: c.operator ?? c.compare ?? 'eq'
    })),
    label: b.label
  }))
  return {
    node_data: {
      branch,
      // 契约缺：兜底分支 id（旧用 type==='ELSE' 表达，这里双写保证可逆）
      _fallbackBranchId: fallback,
      fallbackBranchId: fallback
    },
    // 条件节点锚点靠这张表算 y 坐标，缺了会报「无法获取 endAnchor」
    branch_condition_list: branch.map((b: any, i: number) => ({
      id: b.id,
      height: 12,
      index: i
    }))
  }
}

function numberConditionFromLegacy(nd: any): NumberConditionNodeConfig & Record<string, any> {
  const branch: any[] = nd?.branch ?? []
  const branches: Array<NumberConditionBranch & Record<string, any>> = branch.map((b) => ({
    id: b.id,
    label: b.label ?? b.type ?? b.id,
    logic: b.logic ?? (b.condition === 'or' ? '或' : '且'),
    conditions: (b.conditions ?? []).map((c: any) => {
      const rawVar = c.variable ?? c.field
      const variable: [string, string] | null =
        Array.isArray(rawVar) && rawVar.length >= 2
          ? [String(rawVar[0]), String(rawVar[1])]
          : null
      const rawVal = c.value
      const value: string | null =
        rawVal === null || rawVal === undefined || rawVal === '' ? null : String(rawVal)
      return {
        variable,
        operator: c.operator ?? c.compare ?? 'eq',
        value,
        // 旧字段名透传，保证与 properties 形状互通
        field: Array.isArray(rawVar) ? rawVar : [],
        compare: c.operator ?? c.compare ?? ''
      }
    }),
    // 契约缺以下字段，透传
    type: b.type,
    condition: b.condition
  }))
  const fallback =
    nd?._fallbackBranchId ??
    nd?.fallbackBranchId ??
    branch.find((b) => b.type === 'ELSE')?.id ??
    null
  return {
    branches,
    fallbackBranchId: fallback
  }
}

function replyToLegacy(config: ReplyNodeConfig & Record<string, any>): LegacyBundle {
  return {
    node_data: {
      content: config.content ?? '',
      // 契约缺：回复类型 / 引用字段 / 是否返回
      reply_type: config.reply_type ?? 'content',
      fields: config.fields ?? [],
      is_result: config.is_result ?? false
    }
  }
}

function replyFromLegacy(nd: any): ReplyNodeConfig & Record<string, any> {
  return {
    content: nd?.content ?? '',
    // 契约缺以下字段，透传
    reply_type: nd?.reply_type ?? 'content',
    fields: nd?.fields ?? [],
    is_result: nd?.is_result ?? false
  }
}

function toLegacy(type: string, config: any): LegacyBundle {
  switch (type as ImplementedNodeType) {
    case 'START_NODE':
      return startToLegacy(config)
    case 'BASE_NODE':
      return baseToLegacy(config)
    case 'AI_CHAT_NODE':
      return aiChatToLegacy(config)
    case 'SEARCH_DATASET_NODE':
      return searchToLegacy(config)
    case 'QUESTION_NODE':
      return questionToLegacy(config)
    case 'CONDITION_NODE':
      return conditionToLegacy(config)
    case 'NUMBER_CONDITION_NODE':
      return numberConditionToLegacy(config)
    case 'REPLY_NODE':
      return replyToLegacy(config)
    default:
      return { node_data: { ...config } }
  }
}

function fromLegacy(type: string, nd: any, props: any): Record<string, any> {
  switch (type as ImplementedNodeType) {
    case 'START_NODE':
      return startFromLegacy(nd)
    case 'BASE_NODE':
      return baseFromLegacy(nd, props)
    case 'AI_CHAT_NODE':
      return aiChatFromLegacy(nd)
    case 'SEARCH_DATASET_NODE':
      return searchFromLegacy(nd)
    case 'QUESTION_NODE':
      return questionFromLegacy(nd)
    case 'CONDITION_NODE':
      return conditionFromLegacy(nd)
    case 'NUMBER_CONDITION_NODE':
      return numberConditionFromLegacy(nd)
    case 'REPLY_NODE':
      return replyFromLegacy(nd)
    default:
      return { ...nd }
  }
}

/* ────────────────────────── 边 ────────────────────────── */

/** `${nodeId}_${branchId}_right` → branchId；普通右锚 `${nodeId}_right` → undefined */
function parseSourceHandle(sourceAnchorId: unknown, sourceNodeId: string): string | undefined {
  if (typeof sourceAnchorId !== 'string') return undefined
  const prefix = `${sourceNodeId}_`
  const suffix = '_right'
  if (!sourceAnchorId.startsWith(prefix) || !sourceAnchorId.endsWith(suffix)) return undefined
  const mid = sourceAnchorId.slice(prefix.length, sourceAnchorId.length - suffix.length)
  return mid || undefined
}

/* ────────────────────────── 对外：契约 → LogicFlow ────────────────────────── */

export function toLogicFlow(graph: WorkflowGraph): LogicFlowGraph {
  // 开场白镜像：契约在 start-node，旧 UI 在 base-node 上编辑
  const startPrologue =
    (graph.nodes.find((n) => n.type === 'START_NODE')?.config as StartNodeConfig | undefined)?.prologue ??
    ''

  const nodes = graph.nodes.map((n) => {
    const config = (n.config ?? {}) as Record<string, any>
    // 整包深拷贝：任何内层数组/对象都不能在两棵树里共享引用
    // （LogicFlow/Vue 会报 "same observable object cannot appear twice"）
    const bundle = cloneDeep(toLegacy(n.type, config))
    // base-node 的开场白镜像自 start-node（用户在 base 表单里改的就是这一份）
    if (n.type === 'BASE_NODE' && (config.prologue === undefined || config.prologue === '')) {
      bundle.node_data.prologue = startPrologue
    }

    const dict = nodeDict[n.type] as { properties?: Record<string, any>; height?: number } | undefined
    const dictProps = dict?.properties ?? {}
    const width = (dictProps.width as number) || 340
    // 初始高度取 nodeDict，真实高度由 HtmlResize 量完 DOM 后回写
    const height = (dictProps.height as number) || (dict?.height as number) || 200

    const properties: Record<string, any> = {
      stepName: n.title,
      // 旧组件表单读 node_data
      node_data: bundle.node_data,
      // 输出参数目录：**不要共享 nodeDict 引用**（同类型多节点会撞 observable）。
      // AppNode 构造时会再用 cloneDeep(nodeDict) 覆盖一次。
      config: cloneDeep(dictProps.config ?? {}),
      width,
      height
    }
    if (bundle.user_input_field_list) properties.user_input_field_list = bundle.user_input_field_list
    if (bundle.api_input_field_list) properties.api_input_field_list = bundle.api_input_field_list
    if (bundle.branch_condition_list) properties.branch_condition_list = bundle.branch_condition_list

    return {
      id: n.id,
      type: n.type,
      x: n.position.x,
      y: n.position.y,
      text: '',
      properties
    }
  })

  const edges = graph.edges.map((e) => {
    const props = (e as any).properties ?? {}
    const sourceAnchorId =
      props.sourceAnchorId ??
      (e.sourceHandle ? `${e.source}_${e.sourceHandle}_right` : `${e.source}_right`)
    const targetAnchorId = props.targetAnchorId ?? `${e.target}_left`
    return {
      id: e.id,
      type: 'app-edge',
      sourceNodeId: e.source,
      targetNodeId: e.target,
      // 锚点 id 必须带上，否则 LogicFlow 找不到 endAnchor
      sourceAnchorId,
      targetAnchorId,
      properties: {
        sourceHandle: e.sourceHandle,
        label: e.label,
        sourceAnchorId,
        targetAnchorId
      }
      // 刻意不写 text：LogicFlow 会把 text 当独立文本挂在默认坐标，
      // 形成「飘在左上角的字」。连线文案只放 properties.label。
    }
  })

  return { nodes, edges }
}

/* ────────────────────────── 对外：LogicFlow → 契约 ────────────────────────── */

export function fromLogicFlow(graph: LogicFlowGraph): WorkflowGraph {
  const nodes: WorkflowNode[] = graph.nodes.map((n) => {
    const props = (n.properties ?? {}) as Record<string, any>
    const nd = (props.node_data ?? {}) as Record<string, any>
    const config = fromLegacy(String(n.type), nd, props)
    return {
      id: String(n.id),
      type: n.type as WorkflowNodeType,
      title: String(props.stepName ?? ''),
      position: { x: Number(n.x) || 0, y: Number(n.y) || 0 },
      config
    } as WorkflowNode
  })

  // 开场白以 base-node 表单的编辑结果为准，写回 start-node 的契约字段
  const start = nodes.find((n) => n.type === 'START_NODE')
  const base = nodes.find((n) => n.type === 'BASE_NODE')
  if (start && base) {
    const basePrologue = (base.config as any).prologue
    if (typeof basePrologue === 'string') {
      ;(start.config as StartNodeConfig).prologue = basePrologue
    }
  }

  const edges: WorkflowEdge[] = graph.edges.map((e, i) => {
    const props = (e.properties ?? {}) as Record<string, any>
    const source = String(e.sourceNodeId)
    const target = String(e.targetNodeId)
    const sourceAnchorId = (e.sourceAnchorId ?? props.sourceAnchorId) as string | undefined
    const targetAnchorId = (e.targetAnchorId ?? props.targetAnchorId) as string | undefined
    const text = e.text
    const label =
      props.label ??
      (typeof text === 'string' ? text : (text as { value?: string } | undefined)?.value)
    return {
      // 兜底造 id：契约要求必填
      id: String(e.id ?? `e-${i}`),
      source,
      target,
      sourceHandle: props.sourceHandle ?? parseSourceHandle(sourceAnchorId, source),
      ...(label ? { label: String(label) } : {}),
      // 契约缺：锚点 id，透传保证可逆
      properties: {
        sourceHandle: props.sourceHandle ?? parseSourceHandle(sourceAnchorId, source),
        label: label ?? undefined,
        sourceAnchorId,
        targetAnchorId
      }
    } as WorkflowEdge
  })

  return { nodes, edges }
}
