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
}

export const NODE_CONFIG_FIELDS: Record<ImplementedNodeType, readonly NodeConfigField[]> = {
  START_NODE: [],
  BASE_NODE: [
    { key: "fields", label: "收集字段", type: "object-list", required: false, panelOnly: false },
    { key: "prologue", label: "开场白", type: "string", required: false, panelOnly: true },
    { key: "apiFields", label: "接口传参", type: "object-list", required: false, panelOnly: true },
  ],
  AI_CHAT_NODE: [
    { key: "modelId", label: "模型", type: "uuid", required: false, panelOnly: false },
    { key: "rolePrompt", label: "角色提示词", type: "string", required: false, panelOnly: true },
    { key: "promptIds", label: "提示词模板", type: "string-list", required: false, panelOnly: true },
    { key: "kbIds", label: "知识库", type: "string-list", required: false, panelOnly: true },
    { key: "retrieval", label: "检索参数", type: "object", required: false, panelOnly: true },
    { key: "thinkingEnabled", label: "深度思考", type: "boolean", required: false, panelOnly: true },
    { key: "multiTurn", label: "多轮对话", type: "boolean", required: false, panelOnly: true },
  ],
  SEARCH_DATASET_NODE: [
    { key: "kbIds", label: "知识库", type: "string-list", required: true, panelOnly: false },
    { key: "retrieval", label: "检索参数", type: "object", required: false, panelOnly: false },
  ],
  QUESTION_NODE: [
    { key: "modelId", label: "模型", type: "uuid", required: false, panelOnly: true },
    { key: "rewritePrompt", label: "改写提示词", type: "string", required: false, panelOnly: true },
    { key: "defaultAnswer", label: "默认答复", type: "string", required: false, panelOnly: false },
  ],
  CONDITION_NODE: [
    { key: "branches", label: "分支", type: "branch-list", required: true, panelOnly: false },
    { key: "fallbackBranchId", label: "兜底分支", type: "string", required: false, panelOnly: false },
  ],
  NUMBER_CONDITION_NODE: [
    { key: "branches", label: "分支", type: "branch-list", required: true, panelOnly: false },
    { key: "fallbackBranchId", label: "兜底分支", type: "string", required: false, panelOnly: false },
  ],
  REPLY_NODE: [
    { key: "content", label: "回复内容", type: "string", required: true, panelOnly: false, editor: "markdown" },
    { key: "replyType", label: "回复形态", type: "string", required: false, panelOnly: false, options: ["content"], hint: "当前只有「自定义内容」一种。缺省即它，不必手选" },
    { key: "asResult", label: "返回内容", type: "boolean", required: false, panelOnly: true, hint: "打开后这条回复作为流程结果呈现给用户" },
  ],
  DOCUMENT_EXTRACT_NODE: [
    { key: "kbId", label: "知识库", type: "uuid", required: false, panelOnly: true, optionsSource: "knowledge-bases", hint: "先选库才能选文档。这一项只给面板用，执行器不读" },
    { key: "docId", label: "文档", type: "uuid", required: true, panelOnly: false, optionsSource: "documents", dependsOn: "kbId", hint: "只有已解析出正文的文档能用；正文会写进流程变量 document_markdown" },
  ],
  FORM_NODE: [
    { key: "formFields", label: "收集字段", type: "object-list", required: false, panelOnly: true },
    { key: "contentFormat", label: "引导文案", type: "string", required: false, panelOnly: true },
    { key: "asResult", label: "提交即结束", type: "boolean", required: false, panelOnly: true },
  ],
};

/** 目录里声明的流程变量（node-types.json 的 outputs）。 */
export const NODE_OUTPUT_FIELDS: Record<ImplementedNodeType, readonly string[]> = {
  START_NODE: ["query"],
  BASE_NODE: ["basic_info_fields", "basic_info_input"],
  AI_CHAT_NODE: ["answer"],
  SEARCH_DATASET_NODE: ["retrieval_result"],
  QUESTION_NODE: ["query"],
  CONDITION_NODE: ["active_branch"],
  NUMBER_CONDITION_NODE: ["active_branch"],
  REPLY_NODE: ["reply"],
  DOCUMENT_EXTRACT_NODE: ["document_markdown"],
  FORM_NODE: ["form_data"],
};

/**
 * 全部 18 种类型名。
 *
 * ★ 单独给一个"名字联合"而不是直接用 `ImplementedNodeType | PlannedNodeType`，是因为
 *   目录条目（NodeTypeMeta）覆盖全部 18 种，而画布模块只覆盖已就绪的那一批。
 */
export type WorkflowNodeTypeName = (typeof NODE_TYPE_ORDER)[number];
