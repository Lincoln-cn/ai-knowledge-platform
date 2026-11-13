/**
 * ⚠️ 本文件由 `frontend/scripts/generate-node-types.mjs` 生成，不要手改。
 *
 * 真源：
 *   · backend/src/main/resources/workflow/node-types.json（类型名 / 显示名 / 分类 / 字段 / 输出变量）
 *   · frontend/src/pages/workflow/components/nodes/<kebab>/index.ts（前端是否已就绪）
 *
 * 重新生成：`node --run gen:node-types`
 * 校验漂移：`node --run check:node-types`
 */

/** 画布上已经具备完整前端能力的节点类型（有 nodes/<kebab>/index.ts 就算就绪）。 */
export const IMPLEMENTED_NODE_TYPES = [
  "START_NODE",
  "BASE_NODE",
  "AI_CHAT_NODE",
  "SEARCH_DATASET_NODE",
  "QUESTION_NODE",
  "CONDITION_NODE",
  "NUMBER_CONDITION_NODE",
  "REPLY_NODE",
  "DOCUMENT_EXTRACT_NODE",
  "FORM_NODE",
] as const;

export type ImplementedNodeType = (typeof IMPLEMENTED_NODE_TYPES)[number];

/** 后端目录里有、前端还没进画布的类型。 */
export const PLANNED_NODE_TYPES = [
  "FUNCTION_LIB_NODE",
  "FUNCTION_NODE",
  "RERANKER_NODE",
  "APPLICATION_NODE",
  "IMAGE_UNDERSTAND_NODE",
  "TEXT_TO_SPEECH_NODE",
  "SPEECH_TO_TEXT_NODE",
  "IMAGE_GENERATE_NODE",
] as const;

export type PlannedNodeType = (typeof PLANNED_NODE_TYPES)[number];

/** 目录顺序（18 种）。菜单与校验都按它排。 */
export const NODE_TYPE_ORDER = [
  "START_NODE",
  "BASE_NODE",
  "AI_CHAT_NODE",
  "SEARCH_DATASET_NODE",
  "QUESTION_NODE",
  "CONDITION_NODE",
  "NUMBER_CONDITION_NODE",
  "REPLY_NODE",
  "FUNCTION_LIB_NODE",
  "FUNCTION_NODE",
  "RERANKER_NODE",
  "APPLICATION_NODE",
  "DOCUMENT_EXTRACT_NODE",
  "IMAGE_UNDERSTAND_NODE",
  "FORM_NODE",
  "TEXT_TO_SPEECH_NODE",
  "SPEECH_TO_TEXT_NODE",
  "IMAGE_GENERATE_NODE",
] as const;

/** 类型 → 显示名（node-types.json 的 label）。 */
export const NODE_TYPE_LABELS: Record<WorkflowNodeTypeName, string> = {
  START_NODE: "开始",
  BASE_NODE: "基础节点",
  AI_CHAT_NODE: "AI 对话",
  SEARCH_DATASET_NODE: "知识库检索",
  QUESTION_NODE: "提问",
  CONDITION_NODE: "条件判断",
  NUMBER_CONDITION_NODE: "数值条件",
  REPLY_NODE: "回复",
  FUNCTION_LIB_NODE: "函数库",
  FUNCTION_NODE: "函数",
  RERANKER_NODE: "重排",
  APPLICATION_NODE: "应用调用",
  DOCUMENT_EXTRACT_NODE: "文档抽取",
  IMAGE_UNDERSTAND_NODE: "图像理解",
  FORM_NODE: "表单收集",
  TEXT_TO_SPEECH_NODE: "语音合成",
  SPEECH_TO_TEXT_NODE: "语音识别",
  IMAGE_GENERATE_NODE: "图像生成",
};

/** 类型 → 一句话描述（node-types.json 的 description），菜单与节点卡片用它。 */
export const NODE_TYPE_DESCRIPTIONS: Record<WorkflowNodeTypeName, string> = {
  START_NODE: "流程入口：接收用户输入，本身没有可配置项",
  BASE_NODE: "只做数据传递的占位节点，用于整理连线",
  AI_CHAT_NODE: "调用大模型生成回复，可带检索结果作为上下文",
  SEARCH_DATASET_NODE: "在指定知识库里检索相关分段",
  QUESTION_NODE: "向用户追问一个问题并把回答带回流程",
  CONDITION_NODE: "按条件把流程分成两支",
  NUMBER_CONDITION_NODE: "按数值比较把流程分成多支",
  REPLY_NODE: "直接输出一段固定文案",
  FUNCTION_LIB_NODE: "调用预先登记的公共函数",
  FUNCTION_NODE: "在流程里直接写一段函数逻辑",
  RERANKER_NODE: "对检索结果重新排序",
  APPLICATION_NODE: "调用另一个已发布的编排应用",
  DOCUMENT_EXTRACT_NODE: "把库里选定文档的正文抽出来交给下游",
  IMAGE_UNDERSTAND_NODE: "识别图片内容并输出文字描述",
  FORM_NODE: "向用户收集多个字段",
  TEXT_TO_SPEECH_NODE: "把文本转成语音输出",
  SPEECH_TO_TEXT_NODE: "把用户语音转成文本",
  IMAGE_GENERATE_NODE: "按描述生成图片",
};

/** 画布节点菜单里的类型（就绪的那一批，按目录顺序）。 */
export const WORKFLOW_NODE_TYPE_VALUES: ImplementedNodeType[] = [
  ...IMPLEMENTED_NODE_TYPES,
];

/** 目录里声明的配置字段（node-types.json 的 configFields）。 */
export interface NodeConfigField {
  readonly key: string;
  readonly label: string;
  /** 字段类型：string / uuid / string-list / number / object / branch-list … */
  readonly type: string;
  readonly required: boolean;
  /** 只给面板用：执行器不读它（#336 的悬空字段就靠这一格表达）。 */
  readonly panelOnly: boolean;
  /** 长文本的编辑形态：`markdown` 用 Markdown 编辑器，缺省是普通输入框。 */
  readonly editor?: string;
  /** 选项从哪来：`knowledge-bases`（全部知识库）/ `documents`（所选库里的文档）。 */
  readonly optionsSource?: string;
  /** 候选项依赖哪个字段（文档依赖库）。依赖为空 ⇒ 候选也为空。 */
  readonly dependsOn?: string;
  /** 固定选项（如回复形态只有一种取值）。 */
  readonly options?: readonly string[];
  /** 面板上的说明文字。 */
  readonly hint?: string;
  /** 新建节点时这个字段的默认值（目录里写了 default 才有这一格）。 */
  readonly defaultValue?: unknown;
}

export const NODE_CONFIG_FIELDS: Record<WorkflowNodeTypeName, readonly NodeConfigField[]> = {
  START_NODE: [],
  BASE_NODE: [
    { key: "fields", label: "收集字段", type: "object-list", required: false, panelOnly: false, defaultValue: [] },
    { key: "prologue", label: "开场白", type: "string", required: false, panelOnly: true },
    { key: "apiFields", label: "接口传参", type: "object-list", required: false, panelOnly: true, defaultValue: [] },
  ],
  AI_CHAT_NODE: [
    { key: "modelId", label: "模型", type: "uuid", required: false, panelOnly: false, defaultValue: null },
    { key: "rolePrompt", label: "角色提示词", type: "string", required: false, panelOnly: true, defaultValue: "" },
    { key: "promptIds", label: "提示词模板", type: "string-list", required: false, panelOnly: true, defaultValue: [] },
    { key: "kbIds", label: "知识库", type: "string-list", required: false, panelOnly: true, defaultValue: [] },
    { key: "retrieval", label: "检索参数", type: "object", required: false, panelOnly: true },
    { key: "thinkingEnabled", label: "深度思考", type: "boolean", required: false, panelOnly: true, defaultValue: false },
    { key: "multiTurn", label: "多轮对话", type: "boolean", required: false, panelOnly: true, defaultValue: false },
  ],
  SEARCH_DATASET_NODE: [
    { key: "kbIds", label: "知识库", type: "string-list", required: true, panelOnly: false, defaultValue: [] },
    { key: "retrieval", label: "检索参数", type: "object", required: false, panelOnly: false, defaultValue: {"mode":"HYBRID","threshold":0.6,"topN":5,"noRefStrategy":"REFUSE"} },
  ],
  QUESTION_NODE: [
    { key: "modelId", label: "模型", type: "uuid", required: false, panelOnly: true },
    { key: "rewritePrompt", label: "改写提示词", type: "string", required: false, panelOnly: true, defaultValue: "" },
    { key: "defaultAnswer", label: "默认答复", type: "string", required: false, panelOnly: false, defaultValue: "" },
  ],
  CONDITION_NODE: [
    { key: "branches", label: "分支", type: "branch-list", required: true, panelOnly: false, defaultValue: [{"id":"branch-1","type":"IF","label":"条件 1","expression":""},{"id":"branch-2","type":"ELSE","label":"否则","expression":""}] },
    { key: "fallbackBranchId", label: "兜底分支", type: "string", required: false, panelOnly: false, defaultValue: "branch-2" },
  ],
  NUMBER_CONDITION_NODE: [
    { key: "branches", label: "分支", type: "branch-list", required: true, panelOnly: false, defaultValue: [{"id":"branch-1","type":"IF","label":"条件 1","conditions":[]},{"id":"branch-2","type":"ELSE","label":"否则","conditions":[]}] },
    { key: "fallbackBranchId", label: "兜底分支", type: "string", required: false, panelOnly: false, defaultValue: "branch-2" },
  ],
  REPLY_NODE: [
    { key: "content", label: "回复内容", type: "string", required: true, panelOnly: false, editor: "markdown", defaultValue: "已为你整理如下：" },
    { key: "replyType", label: "回复形态", type: "string", required: false, panelOnly: false, options: ["content"], hint: "当前只有「自定义内容」一种。缺省即它，不必手选", defaultValue: "content" },
    { key: "asResult", label: "返回内容", type: "boolean", required: false, panelOnly: true, hint: "打开后这条回复作为流程结果呈现给用户", defaultValue: true },
  ],
  FUNCTION_LIB_NODE: [],
  FUNCTION_NODE: [],
  RERANKER_NODE: [],
  APPLICATION_NODE: [],
  DOCUMENT_EXTRACT_NODE: [
    { key: "kbId", label: "知识库", type: "uuid", required: false, panelOnly: true, optionsSource: "knowledge-bases", hint: "先选库才能选文档。这一项只给面板用，执行器不读", defaultValue: null },
    { key: "docId", label: "文档", type: "uuid", required: true, panelOnly: false, optionsSource: "documents", dependsOn: "kbId", hint: "只有已解析出正文的文档能用；正文会写进流程变量 document_markdown", defaultValue: null },
  ],
  IMAGE_UNDERSTAND_NODE: [],
  FORM_NODE: [
    { key: "formFields", label: "收集字段", type: "object-list", required: false, panelOnly: true, defaultValue: [] },
    { key: "contentFormat", label: "引导文案", type: "string", required: false, panelOnly: true, defaultValue: "你好，请先填写下面表单内容：\n{{form}}\n填写后请点击【提交】按钮进行提交。" },
    { key: "asResult", label: "提交即结束", type: "boolean", required: false, panelOnly: true, defaultValue: true },
  ],
  TEXT_TO_SPEECH_NODE: [],
  SPEECH_TO_TEXT_NODE: [],
  IMAGE_GENERATE_NODE: [],
};

/**
 * 目录里声明的流程变量（node-types.json 的 outputs）。
 *
 * ★ 形状与画布 `properties.config.fields` 相同（`{label, value}`），因为那个字段就是
 *   "这个节点产出什么"的变量目录 —— 以前手写在 components/common/data.ts 里，与执行器
 *   实际 `state.write(...)` 的键对不上（检索节点写的是 `retrieval_result`，目录里却是
 *   `paragraph_list` / `data` 四个键），下游选到的是永远取不到值的字段。
 */
export const NODE_OUTPUT_FIELDS: Record<WorkflowNodeTypeName, readonly NodeVariableField[]> = {
  START_NODE: [{ label: "用户问题", value: "query" }],
  BASE_NODE: [{ label: "基本信息字段", value: "basic_info_fields" }, { label: "基本信息输入", value: "basic_info_input" }],
  AI_CHAT_NODE: [{ label: "AI 回答内容", value: "answer" }],
  SEARCH_DATASET_NODE: [{ label: "检索结果", value: "retrieval_result" }],
  QUESTION_NODE: [{ label: "改写后的问题", value: "query" }],
  CONDITION_NODE: [{ label: "命中的分支", value: "active_branch" }],
  NUMBER_CONDITION_NODE: [{ label: "命中的分支", value: "active_branch" }],
  REPLY_NODE: [{ label: "回复内容", value: "reply" }],
  FUNCTION_LIB_NODE: [],
  FUNCTION_NODE: [],
  RERANKER_NODE: [],
  APPLICATION_NODE: [],
  DOCUMENT_EXTRACT_NODE: [{ label: "文档正文", value: "document_markdown" }],
  IMAGE_UNDERSTAND_NODE: [],
  FORM_NODE: [{ label: "表单内容", value: "form_data" }],
  TEXT_TO_SPEECH_NODE: [],
  SPEECH_TO_TEXT_NODE: [],
  IMAGE_GENERATE_NODE: [],
};

/** 变量目录里的一项（`properties.config.fields` 的元素）。 */
export interface NodeVariableField {
  readonly label: string;
  readonly value: string;
}

/**
 * 节点被拖进画布时落在 `properties.node_data` 里的默认值（目录的 `configFields[].default`）。
 *
 * ★ 这里只放"从类型能推出来"的东西：空数组、null、默认检索参数、默认文案。
 *   业务上会变的取值由面板填，不进这里 —— 否则改一个默认值要发两次版本（后端目录 + 前端）。
 */
export const NODE_DEFAULT_DATA: Record<WorkflowNodeTypeName, Record<string, unknown>> = {
  START_NODE: {},
  BASE_NODE: {
    fields: [],
    apiFields: [],
  },
  AI_CHAT_NODE: {
    modelId: null,
    rolePrompt: "",
    promptIds: [],
    kbIds: [],
    thinkingEnabled: false,
    multiTurn: false,
  },
  SEARCH_DATASET_NODE: {
    kbIds: [],
    retrieval: {"mode":"HYBRID","threshold":0.6,"topN":5,"noRefStrategy":"REFUSE"},
  },
  QUESTION_NODE: {
    rewritePrompt: "",
    defaultAnswer: "",
  },
  CONDITION_NODE: {
    branches: [{"id":"branch-1","type":"IF","label":"条件 1","expression":""},{"id":"branch-2","type":"ELSE","label":"否则","expression":""}],
    fallbackBranchId: "branch-2",
  },
  NUMBER_CONDITION_NODE: {
    branches: [{"id":"branch-1","type":"IF","label":"条件 1","conditions":[]},{"id":"branch-2","type":"ELSE","label":"否则","conditions":[]}],
    fallbackBranchId: "branch-2",
  },
  REPLY_NODE: {
    content: "已为你整理如下：",
    replyType: "content",
    asResult: true,
  },
  FUNCTION_LIB_NODE: {},
  FUNCTION_NODE: {},
  RERANKER_NODE: {},
  APPLICATION_NODE: {},
  DOCUMENT_EXTRACT_NODE: {
    kbId: null,
    docId: null,
  },
  IMAGE_UNDERSTAND_NODE: {},
  FORM_NODE: {
    formFields: [],
    contentFormat: "你好，请先填写下面表单内容：\n{{form}}\n填写后请点击【提交】按钮进行提交。",
    asResult: true,
  },
  TEXT_TO_SPEECH_NODE: {},
  SPEECH_TO_TEXT_NODE: {},
  IMAGE_GENERATE_NODE: {},
};

/**
 * 全部 18 种类型名。
 *
 * ★ 单独给一个"名字联合"而不是直接用 `ImplementedNodeType | PlannedNodeType`，是因为
 *   目录条目（NodeTypeMeta）覆盖全部 18 种，而画布模块只覆盖已就绪的那一批。
 */
export type WorkflowNodeTypeName = (typeof NODE_TYPE_ORDER)[number];

/**
 * 目录条目的完整镜像 —— 后端 `NodeTypeMeta` 的等价物。
 *
 * ★ mock 的数据与画布菜单都从这里取。以前 `mock/fixtures/workflow.ts` 手写过一份 180 行的
 *   目录，结果 `QUESTION_NODE` 在它上面一直标着 `executable: false`（真值是 true），
 *   演示里显示「不能运行」—— 目录只有一处真源，mock 也不例外。
 *
 * ★ `singleton` / `canBeEntry` 不在这里：它们是画布概念（能不能复制、能不能当入口），
 *   后端不下发，由 mock 侧补。
 */
export interface NodeTypeMetaRecord {
  readonly type: WorkflowNodeTypeName;
  readonly label: string;
  readonly description: string;
  readonly category: string;
  readonly catalogEnabled: boolean;
  readonly executable: boolean;
  readonly configFields: readonly NodeConfigField[];
  /** 后端目录形状 `{key,label}`（画布用的 `{label,value}` 在 NODE_OUTPUT_FIELDS）。 */
  readonly outputs: readonly { readonly key: string; readonly label: string }[];
}

export const NODE_TYPE_META: readonly NodeTypeMetaRecord[] = [
  {
    type: "START_NODE",
    label: "开始",
    description: "流程入口：接收用户输入，本身没有可配置项",
    category: "基础",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["START_NODE"] ?? [],
    outputs: [{ key: "query", label: "用户问题" }]
  },
  {
    type: "BASE_NODE",
    label: "基础节点",
    description: "只做数据传递的占位节点，用于整理连线",
    category: "基础",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["BASE_NODE"] ?? [],
    outputs: [{ key: "basic_info_fields", label: "基本信息字段" }, { key: "basic_info_input", label: "基本信息输入" }]
  },
  {
    type: "AI_CHAT_NODE",
    label: "AI 对话",
    description: "调用大模型生成回复，可带检索结果作为上下文",
    category: "生成",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["AI_CHAT_NODE"] ?? [],
    outputs: [{ key: "answer", label: "AI 回答内容" }]
  },
  {
    type: "SEARCH_DATASET_NODE",
    label: "知识库检索",
    description: "在指定知识库里检索相关分段",
    category: "检索",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["SEARCH_DATASET_NODE"] ?? [],
    outputs: [{ key: "retrieval_result", label: "检索结果" }]
  },
  {
    type: "QUESTION_NODE",
    label: "提问",
    description: "向用户追问一个问题并把回答带回流程",
    category: "交互",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["QUESTION_NODE"] ?? [],
    outputs: [{ key: "query", label: "改写后的问题" }]
  },
  {
    type: "CONDITION_NODE",
    label: "条件判断",
    description: "按条件把流程分成两支",
    category: "逻辑",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["CONDITION_NODE"] ?? [],
    outputs: [{ key: "active_branch", label: "命中的分支" }]
  },
  {
    type: "NUMBER_CONDITION_NODE",
    label: "数值条件",
    description: "按数值比较把流程分成多支",
    category: "逻辑",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["NUMBER_CONDITION_NODE"] ?? [],
    outputs: [{ key: "active_branch", label: "命中的分支" }]
  },
  {
    type: "REPLY_NODE",
    label: "回复",
    description: "直接输出一段固定文案",
    category: "生成",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["REPLY_NODE"] ?? [],
    outputs: [{ key: "reply", label: "回复内容" }]
  },
  {
    type: "FUNCTION_LIB_NODE",
    label: "函数库",
    description: "调用预先登记的公共函数",
    category: "扩展",
    catalogEnabled: false,
    executable: false,
    configFields: NODE_CONFIG_FIELDS["FUNCTION_LIB_NODE"] ?? [],
    outputs: []
  },
  {
    type: "FUNCTION_NODE",
    label: "函数",
    description: "在流程里直接写一段函数逻辑",
    category: "扩展",
    catalogEnabled: false,
    executable: false,
    configFields: NODE_CONFIG_FIELDS["FUNCTION_NODE"] ?? [],
    outputs: []
  },
  {
    type: "RERANKER_NODE",
    label: "重排",
    description: "对检索结果重新排序",
    category: "检索",
    catalogEnabled: false,
    executable: false,
    configFields: NODE_CONFIG_FIELDS["RERANKER_NODE"] ?? [],
    outputs: []
  },
  {
    type: "APPLICATION_NODE",
    label: "应用调用",
    description: "调用另一个已发布的编排应用",
    category: "扩展",
    catalogEnabled: false,
    executable: false,
    configFields: NODE_CONFIG_FIELDS["APPLICATION_NODE"] ?? [],
    outputs: []
  },
  {
    type: "DOCUMENT_EXTRACT_NODE",
    label: "文档抽取",
    description: "把库里选定文档的正文抽出来交给下游",
    category: "检索",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["DOCUMENT_EXTRACT_NODE"] ?? [],
    outputs: [{ key: "document_markdown", label: "文档正文" }]
  },
  {
    type: "IMAGE_UNDERSTAND_NODE",
    label: "图像理解",
    description: "识别图片内容并输出文字描述",
    category: "多模态",
    catalogEnabled: false,
    executable: false,
    configFields: NODE_CONFIG_FIELDS["IMAGE_UNDERSTAND_NODE"] ?? [],
    outputs: []
  },
  {
    type: "FORM_NODE",
    label: "表单收集",
    description: "向用户收集多个字段",
    category: "交互",
    catalogEnabled: true,
    executable: true,
    configFields: NODE_CONFIG_FIELDS["FORM_NODE"] ?? [],
    outputs: [{ key: "form_data", label: "表单内容" }]
  },
  {
    type: "TEXT_TO_SPEECH_NODE",
    label: "语音合成",
    description: "把文本转成语音输出",
    category: "多模态",
    catalogEnabled: false,
    executable: false,
    configFields: NODE_CONFIG_FIELDS["TEXT_TO_SPEECH_NODE"] ?? [],
    outputs: []
  },
  {
    type: "SPEECH_TO_TEXT_NODE",
    label: "语音识别",
    description: "把用户语音转成文本",
    category: "多模态",
    catalogEnabled: false,
    executable: false,
    configFields: NODE_CONFIG_FIELDS["SPEECH_TO_TEXT_NODE"] ?? [],
    outputs: []
  },
  {
    type: "IMAGE_GENERATE_NODE",
    label: "图像生成",
    description: "按描述生成图片",
    category: "多模态",
    catalogEnabled: false,
    executable: false,
    configFields: NODE_CONFIG_FIELDS["IMAGE_GENERATE_NODE"] ?? [],
    outputs: []
  },
];
