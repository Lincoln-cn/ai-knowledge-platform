import type {
  WorkflowEdge,
  WorkflowGraph,
  WorkflowNode,
  NodeTypeMeta,
  WorkflowRecord,
  WorkflowVersion
} from '@/api/workflow'

/**
 * 编排（画布）mock 数据。**这是流程图数据的唯一真相源**。
 *
 * - `workflowDrafts`：按应用 id 存草稿图。应用壳的 publish-check 只读这里，
 *   不再在 fixtures/assistants.ts 里另放一份 `workflowDrafts`（两份真相源已合并到此）。
 * - `sampleValidGraph`：能跑的示例图，覆盖 7 种已实现节点（判断走语义判断）。
 * - `sampleInvalidGraph`：故意不合规的图，用来演示校验拦截（孤立/断链/成环/条件缺口/坏引用）。
 * - 版本与流程记录单独挂在应用 id 下。
 *
 * 注意：图级合法性由 `@/pages/workflow/lib/validate` 判定；
 * 应用壳 publish-check 只查「有没有一张能跑的图」，不重复图级规则。
 */

/* ------------------------------------------------------------------ */
/* 节点目录（18 种：8 种已实现 + 10 种规划中）                            */
/* ------------------------------------------------------------------ */

const implementedExtras: Partial<NodeTypeMeta>[] = []

void implementedExtras

export const nodeTypeCatalog: NodeTypeMeta[] = [
  {
    type: 'START_NODE',
    label: '开始节点',
    category: '基础',
    implemented: true,
    singleton: true,
    canBeEntry: true,
    description: '流程唯一入口。配置开场白与推荐问题，可作为变量引用来源。'
  },
  {
    type: 'BASE_NODE',
    label: '基本信息收集',
    category: '基础',
    implemented: true,
    singleton: false,
    canBeEntry: false,
    description: '运行时向用户收集结构化字段（文本/单选/多选/日期/数字/文件），产出可被下游引用的变量。'
  },
  {
    type: 'QUESTION_NODE',
    label: '问题改写',
    category: '预处理',
    implemented: true,
    singleton: false,
    canBeEntry: false,
    description: '把多轮口语化问题改写成独立可检索的查询。可选改写模型，不选则原样透传。'
  },
  {
    type: 'SEARCH_DATASET_NODE',
    label: '知识库检索',
    category: '数据',
    implemented: true,
    singleton: false,
    canBeEntry: false,
    description: '按检索参数从绑定知识库召回片段，产出 snippets 供下游引用。'
  },
  {
    type: 'AI_CHAT_NODE',
    label: 'AI 对话',
    category: '模型',
    implemented: true,
    singleton: false,
    canBeEntry: false,
    description: '调用对话模型生成回答。可绑知识库、提示词，支持思考开关与多轮。'
  },
  {
    type: 'CONDITION_NODE',
    label: '语义判断',
    category: '逻辑',
    implemented: true,
    singleton: false,
    canBeEntry: false,
    description: '按自然语言条件把流程导向不同分支，条件由模型判定（不支持手写脚本），并要求配置兜底分支。'
  },
  {
    type: 'NUMBER_CONDITION_NODE',
    label: '数值判断',
    category: '逻辑',
    implemented: true,
    singleton: false,
    canBeEntry: false,
    description: '按「变量/操作符/值」结构化条件把流程导向不同分支，运行时比值判定，并要求配置兜底分支。'
  },
  {
    type: 'REPLY_NODE',
    label: '指定回复',
    category: '输出',
    implemented: true,
    singleton: false,
    canBeEntry: false,
    description: '流程出口。用固定模板回复，支持 {{节点id.字段名}} 变量引用。'
  },
  {
    type: 'FUNCTION_LIB_NODE',
    label: '函数库',
    category: '扩展',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：声明可复用的外部函数库，供函数调用节点选用。当前版本不可拖入画布。'
  },
  {
    type: 'FUNCTION_NODE',
    label: '函数调用',
    category: '扩展',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：按函数库定义发起 HTTP/内部调用，把结果写入变量。当前版本不可拖入画布。'
  },
  {
    type: 'RERANKER_NODE',
    label: '重排序',
    category: '数据',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：对检索结果做重排序，提升 TopN 质量。当前版本不可拖入画布。'
  },
  {
    type: 'APPLICATION_NODE',
    label: '应用调用',
    category: '扩展',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：编排中调用另一个已发布应用，实现能力复用。当前版本不可拖入画布。'
  },
  {
    type: 'DOCUMENT_EXTRACT_NODE',
    label: '文档提取',
    category: '预处理',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：从用户上传文档中抽取结构化字段。当前版本不可拖入画布。'
  },
  {
    type: 'IMAGE_UNDERSTAND_NODE',
    label: '图像理解',
    category: '多模态',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：调用视觉模型理解图片内容。当前版本不可拖入画布。'
  },
  {
    type: 'FORM_NODE',
    label: '表单',
    category: '基础',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：比基本信息收集更复杂的表单编排（联动/校验规则）。当前版本不可拖入画布。'
  },
  {
    type: 'TEXT_TO_SPEECH_NODE',
    label: '语音合成',
    category: '多模态',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：把回复文本合成语音。当前版本不可拖入画布。'
  },
  {
    type: 'SPEECH_TO_TEXT_NODE',
    label: '语音识别',
    category: '多模态',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：把用户语音转写为文本。当前版本不可拖入画布。'
  },
  {
    type: 'IMAGE_GENERATE_NODE',
    label: '图像生成',
    category: '多模态',
    implemented: false,
    singleton: false,
    canBeEntry: false,
    description: '规划中：按提示词生成配图。当前版本不可拖入画布。'
  }
]

/* ------------------------------------------------------------------ */
/* 可跑示例图：as5 合同风险研判（覆盖 7 种已实现节点）                     */
/* ------------------------------------------------------------------ */

const validNodes: WorkflowNode[] = [
  {
    id: 'start-1',
    type: 'START_NODE',
    title: '开始',
    position: { x: 40, y: 220 },
    config: {
      prologue: '你好，我是合同风险研判助理。请粘贴合同关键条款，或描述你的问题。',
      quickQuestions: ['帮我看看付款条款有没有风险', '违约责任怎么写更稳妥？']
    }
  },
  {
    id: 'base-1',
    type: 'BASE_NODE',
    title: '收集合同信息',
    position: { x: 280, y: 220 },
    config: {
      fields: [
        {
          field: 'contract_type',
          label: '合同类型',
          type: 'SINGLE_SELECT',
          required: true,
          placeholder: '',
          options: ['采购合同', '销售合同', '服务合同']
        },
        {
          field: 'clause_text',
          label: '待审条款',
          type: 'TEXT',
          required: true,
          placeholder: '粘贴需要研判的条款原文'
        },
        {
          field: 'amount',
          label: '合同金额（万元）',
          type: 'NUMBER',
          required: false,
          placeholder: '选填'
        }
      ],
      allowFileUpload: false,
      fileAccept: ['.pdf', '.docx']
    }
  },
  {
    id: 'question-1',
    type: 'QUESTION_NODE',
    title: '改写检索问',
    position: { x: 520, y: 220 },
    config: {
      modelId: 'm2',
      rewritePrompt:
        '结合用户提供的合同类型与条款，把问题改写成便于知识库检索的一句话。只输出改写结果。'
    }
  },
  {
    id: 'search-1',
    type: 'SEARCH_DATASET_NODE',
    title: '检索合同制度',
    position: { x: 760, y: 220 },
    config: {
      kbIds: ['kb1'],
      retrieval: { mode: 'HYBRID', threshold: 0.5, topN: 5, noRefStrategy: 'REFUSE' }
    }
  },
  {
    id: 'ai-chat-1',
    type: 'AI_CHAT_NODE',
    title: '风险研判',
    position: { x: 1000, y: 220 },
    config: {
      modelId: 'm2',
      rolePrompt:
        '你是企业合同审查助手。严格依据检索到的制度文件逐条列出风险，标注依据编号，给出可执行修改建议。不要编造不在引用中的条款。',
      promptIds: ['pc1'],
      kbIds: ['kb1'],
      retrieval: { mode: 'HYBRID', threshold: 0.5, topN: 5, noRefStrategy: 'REFUSE' },
      thinkingEnabled: true,
      multiTurn: false
    }
  },
  {
    id: 'condition-1',
    type: 'CONDITION_NODE',
    title: '风险分流',
    position: { x: 1240, y: 220 },
    config: {
      branches: [
        { id: 'b-high', label: '高风险', expression: '研判结论为高风险，或存在违约/付款重大隐患' },
        { id: 'b-low', label: '一般', expression: '研判结论为低风险或仅格式性问题' },
        { id: 'b-fallback', label: '兜底', expression: '以上条件都不命中' }
      ],
      fallbackBranchId: 'b-fallback'
    }
  },
  {
    id: 'reply-1',
    type: 'REPLY_NODE',
    title: '高风险提示',
    position: { x: 1500, y: 60 },
    config: {
      content:
        '⚠️ **高风险提示**\n\n{{ai-chat-1.answer}}\n\n建议在签署前完成条款修改并走法务复核。依据见：{{search-1.snippets}}'
    }
  },
  {
    id: 'reply-2',
    type: 'REPLY_NODE',
    title: '一般结论',
    position: { x: 1500, y: 240 },
    config: {
      content: '✅ **一般结论**\n\n{{ai-chat-1.answer}}\n\n如需逐条修改建议，请补充完整条款文本。'
    }
  },
  {
    id: 'reply-3',
    type: 'REPLY_NODE',
    title: '兜底回复',
    position: { x: 1500, y: 420 },
    config: {
      content:
        '已收到你的问题。\n\n{{ai-chat-1.answer}}\n\n若这不是你想问的，请换个说法再试一次。'
    }
  }
]

const validEdges: WorkflowEdge[] = [
  { id: 'e1', source: 'start-1', target: 'base-1' },
  { id: 'e2', source: 'base-1', target: 'question-1' },
  { id: 'e3', source: 'question-1', target: 'search-1' },
  { id: 'e4', source: 'search-1', target: 'ai-chat-1' },
  { id: 'e5', source: 'ai-chat-1', target: 'condition-1' },
  { id: 'e6', source: 'condition-1', target: 'reply-1', sourceHandle: 'b-high', label: '高风险' },
  { id: 'e7', source: 'condition-1', target: 'reply-2', sourceHandle: 'b-low', label: '一般' },
  {
    id: 'e8',
    source: 'condition-1',
    target: 'reply-3',
    sourceHandle: 'b-fallback',
    label: '兜底'
  }
]

export const sampleValidGraph: WorkflowGraph = {
  nodes: validNodes.map((n) => ({ ...n, position: { ...n.position } })),
  edges: validEdges.map((e) => ({ ...e }))
}

/* ------------------------------------------------------------------ */
/* 故意不合规示例图：演示校验拦截                                          */
/* ------------------------------------------------------------------ */

/**
 * 这张图刻意同时踩中多条规则，用于验证「校验能拦下发布」：
 *  - 两个 start-node（start-node-count）
 *  - a1 → a2 → a3 → a2 成环（cycle）
 *  - orphan-1 孤立（isolated-node）
 *  - condition-x 有分支无出边 + 未配兜底（condition-branch-no-edge / condition-no-fallback）
 *  - ai-bad 引用不存在模型 m-ghost、不存在知识库 kb-ghost（model-not-found / kb-not-found）
 *  - 没有 reply-node（no-reply-node 告警）
 */
export const sampleInvalidGraph: WorkflowGraph = {
  nodes: [
    {
      id: 'start-x1',
      type: 'START_NODE',
      title: '开始 A',
      position: { x: 40, y: 80 },
      config: { prologue: '违规示例：这里故意放了两个开始节点', quickQuestions: [] }
    },
    {
      id: 'start-x2',
      type: 'START_NODE',
      title: '开始 B',
      position: { x: 40, y: 240 },
      config: { prologue: '第二个开始节点，用来触发 start-node-count', quickQuestions: [] }
    },
    {
      id: 'a1',
      type: 'AI_CHAT_NODE',
      title: '环上节点 1',
      position: { x: 320, y: 80 },
      config: {
        modelId: 'm1',
        rolePrompt: '成环演示',
        promptIds: [],
        kbIds: [],
        retrieval: { mode: 'VECTOR', threshold: 0.5, topN: 3, noRefStrategy: 'FREE_ANSWER' },
        thinkingEnabled: false,
        multiTurn: false
      }
    },
    {
      id: 'a2',
      type: 'AI_CHAT_NODE',
      title: '环上节点 2',
      position: { x: 560, y: 80 },
      config: {
        modelId: 'm1',
        rolePrompt: '成环演示',
        promptIds: [],
        kbIds: [],
        retrieval: { mode: 'VECTOR', threshold: 0.5, topN: 3, noRefStrategy: 'FREE_ANSWER' },
        thinkingEnabled: false,
        multiTurn: false
      }
    },
    {
      id: 'a3',
      type: 'AI_CHAT_NODE',
      title: '环上节点 3',
      position: { x: 800, y: 80 },
      config: {
        modelId: 'm1',
        rolePrompt: '成环演示',
        promptIds: [],
        kbIds: [],
        retrieval: { mode: 'VECTOR', threshold: 0.5, topN: 3, noRefStrategy: 'FREE_ANSWER' },
        thinkingEnabled: false,
        multiTurn: false
      }
    },
    {
      id: 'orphan-1',
      type: 'REPLY_NODE',
      title: '孤立节点',
      position: { x: 320, y: 400 },
      config: { content: '我是孤立节点，没有任何连线。' }
    },
    {
      id: 'condition-x',
      type: 'CONDITION_NODE',
      title: '缺出边的分支',
      position: { x: 320, y: 540 },
      config: {
        branches: [
          { id: 'bx1', label: '分支一', expression: '命中 A' },
          { id: 'bx2', label: '分支二（无出边）', expression: '命中 B' }
        ],
        fallbackBranchId: null
      }
    },
    {
      id: 'ai-bad',
      type: 'AI_CHAT_NODE',
      title: '坏引用节点',
      position: { x: 560, y: 540 },
      config: {
        modelId: 'm-ghost',
        rolePrompt: '引用不存在的模型与知识库',
        promptIds: [],
        kbIds: ['kb-ghost'],
        retrieval: { mode: 'HYBRID', threshold: 0.6, topN: 5, noRefStrategy: 'REFUSE' },
        thinkingEnabled: false,
        multiTurn: false
      }
    }
  ],
  edges: [
    // 成环：a1 → a2 → a3 → a2（再回到 a2 形成环；另加 a2→a1 保证环可见）
    { id: 'x-e1', source: 'start-x1', target: 'a1' },
    { id: 'x-e2', source: 'a1', target: 'a2' },
    { id: 'x-e3', source: 'a2', target: 'a3' },
    { id: 'x-e4', source: 'a3', target: 'a2' },
    // 条件：只有 bx1 有出边，bx2 无出边，且未配兜底
    { id: 'x-e5', source: 'start-x2', target: 'condition-x' },
    { id: 'x-e6', source: 'condition-x', target: 'ai-bad', sourceHandle: 'bx1', label: '分支一' }
  ]
}

/* ------------------------------------------------------------------ */
/* 草稿（唯一真相源）                                                    */
/* ------------------------------------------------------------------ */

export const workflowDrafts: Record<string, WorkflowGraph> = {
  as5: {
    nodes: sampleValidGraph.nodes.map((n) => ({ ...n, position: { ...n.position } })),
    edges: sampleValidGraph.edges.map((e) => ({ ...e }))
  }
}

/** 新建编排应用时调用：落一份空草稿 */
export function initWorkflowDraft(appId: string): void {
  if (!workflowDrafts[appId]) {
    workflowDrafts[appId] = { nodes: [], edges: [] }
  }
}

/** 取草稿；不存在时给空图（不要抛错，便于首屏） */
export function getWorkflowDraft(appId: string): WorkflowGraph {
  return workflowDrafts[appId] ?? { nodes: [], edges: [] }
}

export function setWorkflowDraft(appId: string, graph: WorkflowGraph): void {
  workflowDrafts[appId] = {
    nodes: graph.nodes.map((n) => ({ ...n, position: { ...n.position }, config: { ...n.config } })),
    edges: graph.edges.map((e) => ({ ...e }))
  }
}

/* ------------------------------------------------------------------ */
/* 版本                                                                 */
/* ------------------------------------------------------------------ */

/**
 * 版本记录。**比契约多带 `graph`** —— 回滚需要它，
 * 但接口响应不返回（见 handlers/workflow.ts 的 toPublicVersion）。
 */
export interface WorkflowVersionRecord extends WorkflowVersion {
  graph: WorkflowGraph
}

export const workflowVersions: Record<string, WorkflowVersionRecord[]> = {
  as5: [
    {
      id: 'wv-as5-2',
      version: 2,
      nodeCount: sampleValidGraph.nodes.length,
      note: '补充风险分流与三路回复',
      publishedAt: '2026-02-12T15:40:00Z',
      publishedBy: '张三',
      graph: {
        nodes: sampleValidGraph.nodes.map((n) => ({ ...n, position: { ...n.position } })),
        edges: sampleValidGraph.edges.map((e) => ({ ...e }))
      }
    },
    {
      id: 'wv-as5-1',
      version: 1,
      nodeCount: 3,
      note: '首次发布：线性「收集 → 检索 → 对话 → 回复」',
      publishedAt: '2026-02-10T09:18:00Z',
      publishedBy: '张三',
      graph: {
        nodes: [
          {
            id: 'start-1',
            type: 'START_NODE',
            title: '开始',
            position: { x: 40, y: 160 },
            config: { prologue: '你好，我是合同风险研判助理。', quickQuestions: [] }
          },
          {
            id: 'ai-chat-1',
            type: 'AI_CHAT_NODE',
            title: '风险研判',
            position: { x: 320, y: 160 },
            config: {
              modelId: 'm2',
              rolePrompt: '审查合同条款风险。',
              promptIds: ['pc1'],
              kbIds: ['kb1'],
              retrieval: { mode: 'HYBRID', threshold: 0.5, topN: 5, noRefStrategy: 'REFUSE' },
              thinkingEnabled: true,
              multiTurn: false
            }
          },
          {
            id: 'reply-1',
            type: 'REPLY_NODE',
            title: '结论',
            position: { x: 600, y: 160 },
            config: { content: '{{ai-chat-1.answer}}' }
          }
        ],
        edges: [
          { id: 'e1', source: 'start-1', target: 'ai-chat-1' },
          { id: 'e2', source: 'ai-chat-1', target: 'reply-1' }
        ]
      }
    }
  ]
}

/* ------------------------------------------------------------------ */
/* 流程记录                                                             */
/* ------------------------------------------------------------------ */

export const workflowRecords: Record<string, WorkflowRecord[]> = {
  as5: [
    {
      id: 'wr-1001',
      appId: 'as5',
      conversationId: null,
      input: '帮我看看付款条款有没有风险',
      nodeTrail: [
        { nodeId: 'start-1', type: 'START_NODE', title: '开始', status: 'SUCCEEDED', enteredAt: '2026-02-13T09:22:10Z', elapsedMs: 12 },
        { nodeId: 'base-1', type: 'BASE_NODE', title: '收集合同信息', status: 'SUCCEEDED', enteredAt: '2026-02-13T09:22:10Z', elapsedMs: 840 },
        { nodeId: 'question-1', type: 'QUESTION_NODE', title: '改写检索问', status: 'SUCCEEDED', enteredAt: '2026-02-13T09:22:10Z', elapsedMs: 460 },
        { nodeId: 'search-1', type: 'SEARCH_DATASET_NODE', title: '检索合同制度', status: 'SUCCEEDED', enteredAt: '2026-02-13T09:22:11Z', elapsedMs: 320 },
        { nodeId: 'ai-chat-1', type: 'AI_CHAT_NODE', title: '风险研判', status: 'SUCCEEDED', enteredAt: '2026-02-13T09:22:11Z', elapsedMs: 3120 },
        { nodeId: 'condition-1', type: 'CONDITION_NODE', title: '风险分流', status: 'SUCCEEDED', enteredAt: '2026-02-13T09:22:14Z', elapsedMs: 80 },
        { nodeId: 'reply-1', type: 'REPLY_NODE', title: '高风险提示', status: 'SUCCEEDED', enteredAt: '2026-02-13T09:22:14Z', elapsedMs: 5 }
      ],
      finalOutputPreview: '⚠️ 高风险提示：付款比例前置过高，尾款未与验收挂钩……',
      status: 'SUCCEEDED',
      failReason: null,
      elapsedMs: 4841,
      startedAt: '2026-02-13T09:22:10Z',
      // 这条跑的是第 3 版图（#112：执行记录要能回答"当时跑的是哪一版"）
      graphVersion: 3
    },
    {
      id: 'wr-1002',
      appId: 'as5',
      conversationId: null,
      input: '违约责任怎么写更稳妥？',
      nodeTrail: [
        { nodeId: 'start-1', type: 'START_NODE', title: '开始', status: 'SUCCEEDED', enteredAt: '2026-02-13T10:05:44Z', elapsedMs: 10 },
        { nodeId: 'base-1', type: 'BASE_NODE', title: '收集合同信息', status: 'SUCCEEDED', enteredAt: '2026-02-13T10:05:44Z', elapsedMs: 210 },
        { nodeId: 'question-1', type: 'QUESTION_NODE', title: '改写检索问', status: 'SUCCEEDED', enteredAt: '2026-02-13T10:05:44Z', elapsedMs: 390 },
        { nodeId: 'search-1', type: 'SEARCH_DATASET_NODE', title: '检索合同制度', status: 'FAILED', enteredAt: '2026-02-13T10:05:44Z', elapsedMs: 90, failReason: '向量服务超时' }
      ],
      finalOutputPreview: '',
      status: 'FAILED',
      failReason: '检索节点失败：向量服务超时',
      elapsedMs: 720,
      startedAt: '2026-02-13T10:05:44Z',
      // 失败那次跑的是第 2 版：改版前的失败与改版后的失败要能分开查
      graphVersion: 2
    },
    {
      id: 'wr-1003',
      appId: 'as5',
      conversationId: null,
      input: '服务合同的验收标准要注意什么？',
      nodeTrail: [
        { nodeId: 'start-1', type: 'START_NODE', title: '开始', status: 'SUCCEEDED', enteredAt: '2026-02-13T11:01:03Z', elapsedMs: 8 },
        { nodeId: 'base-1', type: 'BASE_NODE', title: '收集合同信息', status: 'SUCCEEDED', enteredAt: '2026-02-13T11:01:03Z', elapsedMs: 300 },
        { nodeId: 'question-1', type: 'QUESTION_NODE', title: '改写检索问', status: 'SUCCEEDED', enteredAt: '2026-02-13T11:01:03Z', elapsedMs: 420 },
        { nodeId: 'search-1', type: 'SEARCH_DATASET_NODE', title: '检索合同制度', status: 'SUCCEEDED', enteredAt: '2026-02-13T11:01:03Z', elapsedMs: 280 },
        { nodeId: 'ai-chat-1', type: 'AI_CHAT_NODE', title: '风险研判', status: 'SUCCEEDED', enteredAt: '2026-02-13T11:01:04Z', elapsedMs: 2560 },
        { nodeId: 'condition-1', type: 'CONDITION_NODE', title: '风险分流', status: 'SUCCEEDED', enteredAt: '2026-02-13T11:01:06Z', elapsedMs: 60 },
        { nodeId: 'reply-2', type: 'REPLY_NODE', title: '一般结论', status: 'SUCCEEDED', enteredAt: '2026-02-13T11:01:06Z', elapsedMs: 4 }
      ],
      finalOutputPreview: '✅ 一般结论：验收标准应量化、可检测，避免「符合要求」等模糊表述……',
      status: 'SUCCEEDED',
      failReason: null,
      elapsedMs: 3632,
      startedAt: '2026-02-13T11:01:03Z',
      // 同一条应用、不同版本 ⇒ 记录里必须看得出差别
      graphVersion: 4
    }
  ]
}

/* ------------------------------------------------------------------ */
/* 校验上下文：模型 / 知识库存在性（与 fixtures/models、knowledge 对齐）     */
/* ------------------------------------------------------------------ */

/** 存在的模型 id（与 fixtures/models.ts 的 models[].id 对齐） */
export const knownModelIds = ['m1', 'm2', 'm3', 'm4', 'm5', 'm6']

/** 存在的知识库 id（与 fixtures/knowledge.ts 的 knowledgeBases[].id 对齐） */
export const knownKbIds = ['kb1', 'kb2', 'kb3', 'kb4']

/** 已停用的知识库 id（kb2 当前停用） */
export const disabledKbIds = ['kb2']
