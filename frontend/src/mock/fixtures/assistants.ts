import type { AccessCredential, ApiKeyItem, Assistant } from '@/api/assistants'
import type { WorkflowGraph } from '@/api/workflow'
import { assistantSharedWithUserRole } from './grants'

/**
 * 应用数据。
 *
 * 刻意覆盖目标设计里的关键路径，不是随手编的：
 *  - 两档类型并存：简单应用（提示词约束）/ 编排应用（画布式流程）
 *  - 内置应用（builtin）→ 配置项只读，仅供问答
 *  - 已发布 / 草稿两种发布态
 *  - 思考模式三态对应的应用（NONE 不渲染开关 / TOGGLEABLE 默认关 / ALWAYS_ON 置开禁用）
 *  - 未挂知识库的应用 → 调试无引用属正常
 *  - 凭证含「已撤销」终态 + 「已停用」可再启用，两套状态必须分清
 *  - API Key 与访问凭证是两套机制，互不替代
 *  - 归属分散在 u1–u4、三种可见性各至少一个（07 §3.1）：chenjun 看得见 as1 / as2 / as4，
 *    看不见 as3（别人的 private）与 as5（别人的 private 编排草稿）
 */
export const assistants: Assistant[] = [
  {
    id: 'as1',
    name: '合同审查助手',
    icon: '⚖️',
    description: '基于《合同管理制度》与风险清单，辅助审查合同条款并给出修改建议',
    type: 'simple',
    builtin: false,
    published: true,
    modelId: 'm2',
    modelName: 'qwen3:8b',
    kbIds: ['kb1', 'kb2'],
    rolePrompt: '你是企业合同审查助手，需严格依据提供的制度文件给出风险提示。',
    promptIds: ['pc1'],
    retrieval: {
      mode: '混合检索',
      threshold: 0.6,
      topN: 5,
      noRefStrategy: '拒答'
    },
    multiTurn: true,
    thinkingEnabled: true,
    prologue: '你好，我可以帮你审查合同风险。请上传或粘贴合同条款，或直接提问。',
    quickQuestions: ['这份采购合同有什么风险？', '付款条款是否合规？', '验收标准怎么写？'],
    callCount: 1284,
    tokenCount: 1_862_400,
    satisfaction: 92,
    // public：登录用户里人人可见（持有 assistant:view 即可），所以普通用户也看得见它
    ownerId: 'u3',
    visibility: 'public'
  },
  {
    id: 'as2',
    name: '报销制度咨询',
    icon: '🧾',
    description: '解答差旅、招待、采购报销的限额与流程问题',
    type: 'simple',
    builtin: false,
    published: true,
    modelId: 'm1',
    modelName: 'qwen3:4b-instruct',
    kbIds: ['kb3'],
    rolePrompt: '你是财务制度咨询助手，依据《报销管理制度》回答限额与流程问题。',
    promptIds: ['pc4'],
    retrieval: {
      mode: '全文检索',
      threshold: 0.5,
      topN: 3,
      noRefStrategy: '自由回答'
    },
    multiTurn: false,
    thinkingEnabled: false,
    prologue: '你好，报销限额、票据要求、审批流程都可以问我。',
    quickQuestions: ['差旅住宿标准是多少？', '发票丢失怎么办？'],
    callCount: 652,
    tokenCount: 421_300,
    satisfaction: 88,
    // 共享给「普通用户」角色：可用，但共享里只给了 assistant:view（不能改配置）
    ownerId: 'u2',
    visibility: 'shared',
    grants: assistantSharedWithUserRole
  },
  {
    id: 'as3',
    name: '供应商准入助手',
    icon: '🏭',
    description: '草稿：供应商资质与准入条件查询，尚未绑定完整知识库',
    type: 'simple',
    builtin: false,
    published: false,
    modelId: 'm3',
    modelName: 'qwen3:30b-a3b',
    kbIds: [],
    rolePrompt: '',
    promptIds: [],
    retrieval: {
      mode: '向量检索',
      threshold: 0.6,
      topN: 5,
      noRefStrategy: '拒答'
    },
    multiTurn: true,
    // ALWAYS_ON 模型：界面强制置开且禁用
    thinkingEnabled: true,
    prologue: '',
    quickQuestions: [],
    callCount: 0,
    tokenCount: 0,
    satisfaction: null,
    ownerId: 'u2',
    visibility: 'private'
  },
  {
    id: 'as4',
    name: '通用知识问答',
    icon: '🤖',
    description: '内置应用，挂载全部已启用知识库，仅供问答不可改对话设定',
    type: 'simple',
    builtin: true,
    published: true,
    modelId: 'm1',
    modelName: 'qwen3:4b-instruct',
    kbIds: ['kb1', 'kb2', 'kb3'],
    rolePrompt: '你是企业知识助理。优先依据知识库回答；没有覆盖时明确说明。',
    promptIds: ['pc6'],
    retrieval: {
      mode: '混合检索',
      threshold: 0.6,
      topN: 5,
      noRefStrategy: '自由回答'
    },
    multiTurn: true,
    thinkingEnabled: false,
    prologue: '你好，我是通用知识助理，可以回答公司制度、流程类问题。',
    quickQuestions: ['公司差旅制度是什么？', '合同审批流程？'],
    callCount: 3560,
    tokenCount: 4_210_000,
    satisfaction: 90,
    // 内置应用：平台资产，归管理员名下、public（普通用户只能问答，不能改配置）
    ownerId: 'u1',
    visibility: 'public'
  },
  {
    id: 'as5',
    name: '合同风险研判流程',
    icon: '🔀',
    description: '编排应用：信息收集 → 知识库检索 → AI 研判 → 指定回复，适合多步审查场景',
    type: 'workflow',
    builtin: false,
    published: false,
    // 编排应用**没有应用级模型**（模型在各 AI 节点里，可以一个流程用多个），
    // 改为给一份流程摘要供列表显示。nodeCount / publishedVersion 与
    // fixtures/workflow.ts 里 as5 的草稿节点数、版本列表保持一致。
    workflow: { nodeCount: 9, publishedVersion: 2 },
    kbIds: [],
    rolePrompt: '',
    promptIds: [],
    retrieval: {
      mode: '混合检索',
      threshold: 0.6,
      topN: 5,
      noRefStrategy: '拒答'
    },
    multiTurn: true,
    thinkingEnabled: false,
    prologue: '',
    quickQuestions: [],
    callCount: 0,
    tokenCount: 0,
    satisfaction: null,
    // 编排草稿：别人的 private —— 普通用户既看不见、也开不了它的会话
    ownerId: 'u3',
    visibility: 'private'
  }
]

/**
 * 编排应用的流程草稿。完整流程契约见 `api/workflow.ts`（画布由另一任务落地）；
 * 这里只放发布前校验要用的最小线性样本：开始 → 基本信息 → AI 对话 → 指定回复。
 */
export const workflowDrafts: Record<string, WorkflowGraph> = {
  as5: {
    nodes: [
      {
        id: 'n-start',
        type: 'start-node',
        title: '开始',
        position: { x: 80, y: 160 },
        config: {
          prologue: '请粘贴需要研判的合同条款，我会按制度逐条给出风险提示。',
          quickQuestions: ['这份采购合同有什么风险？']
        }
      },
      {
        id: 'n-base',
        type: 'base-node',
        title: '信息收集',
        position: { x: 280, y: 160 },
        config: {
          fields: [
            {
              field: 'clauseText',
              label: '合同条款',
              type: '文本',
              required: true,
              placeholder: '粘贴需要审查的条款原文'
            }
          ],
          allowFileUpload: false,
          fileAccept: []
        }
      },
      {
        id: 'n-chat',
        type: 'ai-chat-node',
        title: 'AI 研判',
        position: { x: 480, y: 160 },
        config: {
          modelId: 'm2',
          rolePrompt: '你是企业合同审查助手，需严格依据提供的制度文件给出风险提示。',
          promptIds: ['pc1'],
          kbIds: ['kb1', 'kb2'],
          retrieval: {
            mode: '混合检索',
            threshold: 0.6,
            topN: 5,
            noRefStrategy: '拒答'
          },
          thinkingEnabled: true,
          multiTurn: false
        }
      },
      {
        id: 'n-reply',
        type: 'reply-node',
        title: '指定回复',
        position: { x: 680, y: 160 },
        config: {
          content: '研判完成。{{n-chat.answer}}'
        }
      }
    ],
    edges: [
      { id: 'e1', source: 'n-start', target: 'n-base' },
      { id: 'e2', source: 'n-base', target: 'n-chat' },
      { id: 'e3', source: 'n-chat', target: 'n-reply' }
    ]
  }
}

/** 免登录访问凭证。撤销是终态，撤销后不可重新启用，只能重新生成 */
export const credentials: Record<string, AccessCredential[]> = {
  as1: [
    {
      id: 'cr1',
      token: 'pk_live_a1b2c3d4e5f6',
      enabled: true,
      expiresAt: '2026-12-31T00:00:00Z',
      accessCount: 156,
      lastAccessAt: '2026-02-12T15:30:00Z',
      revokedAt: null,
      createdAt: '2026-01-10T08:00:00Z'
    },
    {
      id: 'cr2',
      token: 'pk_live_f6e5d4c3b2a1',
      enabled: false,
      expiresAt: null,
      accessCount: 12,
      lastAccessAt: '2026-01-28T09:12:00Z',
      revokedAt: null,
      createdAt: '2026-01-05T10:00:00Z'
    },
    {
      id: 'cr3',
      token: 'pk_live_deadbeef0000',
      enabled: false,
      expiresAt: null,
      accessCount: 88,
      lastAccessAt: '2026-01-20T18:40:00Z',
      revokedAt: '2026-01-22T11:00:00Z',
      createdAt: '2025-12-01T09:00:00Z'
    }
  ],
  as2: [
    {
      id: 'cr4',
      token: 'pk_live_aa11bb22cc33',
      enabled: true,
      expiresAt: null,
      accessCount: 40,
      lastAccessAt: '2026-02-13T08:05:00Z',
      revokedAt: null,
      createdAt: '2026-02-01T09:00:00Z'
    }
  ],
  as3: [],
  as4: [],
  as5: []
}

/** 程序化调用的 API Key，与访问凭证是两套机制（06 FR-06-04） */
export const apiKeys: Record<string, ApiKeyItem[]> = {
  as1: [
    {
      id: 'ak1',
      key: 'sk-ai-9f8e7d6c5b4a',
      enabled: true,
      createdAt: '2026-01-11T10:00:00Z',
      lastUsedAt: '2026-02-13T07:20:00Z'
    }
  ],
  as2: [],
  as3: [],
  as4: [
    {
      id: 'ak2',
      key: 'sk-ai-1a2b3c4d5e6f',
      enabled: true,
      createdAt: '2026-01-02T08:00:00Z',
      lastUsedAt: '2026-02-12T22:10:00Z'
    }
  ],
  as5: []
}

/**
 * 导出包依赖样本：用于导入前逐项校验。
 * as1 的依赖全部满足；另一份用于演示「缺依赖」路径。
 */
export const sampleBundleDeps = {
  complete: [
    {
      kind: '模型' as const,
      name: 'qwen3:8b',
      detail: '本地推理 · 已加载',
      satisfied: true,
      degrade: null
    },
    {
      kind: '知识库' as const,
      name: '合同管理制度',
      detail: '128 个文档 · 412 个分段',
      satisfied: true,
      degrade: null
    },
    {
      kind: '知识库' as const,
      name: '采购验收规范',
      detail: '36 个文档 · 180 个分段',
      satisfied: true,
      degrade: null
    },
    {
      kind: '提示词' as const,
      name: '合同审查助手',
      detail: '合同法务 · 被 2 个应用引用',
      satisfied: true,
      degrade: null
    }
  ],
  missing: [
    {
      kind: '模型' as const,
      name: 'deepseek-r1:32b',
      detail: '外部API · 未注册',
      satisfied: false,
      degrade: '导入后需手动选择一个已加载的对话模型，否则应用无法发布'
    },
    {
      kind: '知识库' as const,
      name: '历史判例库',
      detail: '导出方 220 个文档 · 1,040 个分段',
      satisfied: false,
      degrade: '跳过该知识库后，相关判例问题将无引用或拒答'
    },
    {
      kind: '提示词' as const,
      name: '判例分析专家',
      detail: '导出方「司法」类型',
      satisfied: false,
      degrade: '跳过后使用空角色设定，回答风格不受控'
    }
  ]
}
