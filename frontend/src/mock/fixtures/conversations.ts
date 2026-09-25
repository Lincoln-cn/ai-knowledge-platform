import type { ChatMessage, Conversation, PublicContext } from '@/api/conversations'
import type { SourceRef } from '@/api/stream-types'

/**
 * 会话与消息数据。
 *
 * 刻意覆盖目标设计要求验证的全部状态：
 *  - 通用问答（无引用） / 应用问答（带引用）
 *  - 已生成 / 已停止 / 失败（含可读原因与下一步）
 *  - 赞 / 踩 / 未评 —— 互斥且可撤销，刷新后仍在
 *  - 推荐问题、多轮上下文
 *  - 免登录凭证三态：有效 / 过期 / 已撤销
 *  - 会话全部 `private` 且归属分散在 u1–u4（07 §4.2「会话：只有自己的」）：
 *    chenjun 只看得见自己的 c2 / c5，其余四个（c1 c3 c4 c6）是别人的
 */

/** 应用展示信息。契约 Conversation 上暂无这些字段，mock 以附加字段带回（见报告「契约缺口」） */
export interface AppBrief {
  id: string
  name: string
  icon: string
  description: string
  /** 应用类型，展示在标题副标题位 */
  type: string
  modelName: string
  modelId: string
  prologue: string
  quickQuestions: string[]
  kbNames: string[]
  /** 是否开启多轮上下文 */
  multiTurn: boolean
  thinkingEnabled: boolean
  /** 挂了知识库才有引用 */
  hasKnowledge: boolean
}

export const appBriefs: Record<string, AppBrief> = {
  as1: {
    id: 'as1',
    name: '合同审查助手',
    icon: '合',
    description: '基于《合同管理制度》与风险清单，辅助审查合同条款',
    type: '知识应用',
    modelName: 'qwen3:8b',
    modelId: 'm2',
    prologue: '你好，我是合同审查助手。可以贴上合同条款或描述你的疑问，我会依据制度文件给出风险提示与修改建议。',
    quickQuestions: [
      '这份采购合同有什么风险？',
      '付款条款应该怎么约定？',
      '验收标准写到什么程度合适？'
    ],
    kbNames: ['合同管理制度'],
    multiTurn: true,
    thinkingEnabled: true,
    hasKnowledge: true
  },
  as2: {
    id: 'as2',
    name: '制度问答',
    icon: '制',
    description: '未挂知识库的简单应用，回答基于模型自身知识，无引用来源',
    type: '知识应用',
    modelName: 'qwen3:4b-instruct',
    modelId: 'm1',
    prologue: '你好，我可以回答通用制度与流程问题。当前应用未挂载知识库，回答不带引用来源。',
    quickQuestions: ['报销一般需要哪些材料？', '出差审批流程是什么？'],
    kbNames: [],
    multiTurn: true,
    thinkingEnabled: false,
    hasKnowledge: false
  }
}

const now = Date.now()
const hour = 3600_000
const day = 24 * hour

const iso = (msAgo: number) => new Date(now - msAgo).toISOString()

/** 引用全文。流式 mock 的 canned refs 不带 full，历史消息这里补上，抽屉可读原文 */
export const segmentFullTexts: Record<string, string> = {
  'seg-3': '第三条 付款条款要求。合同付款应明确付款比例、付款节点与逾期违约责任。前期付款比例原则上不超过合同总额的 30%，尾款须在验收合格后支付。付款节点应与履约进度挂钩，不得以单纯时间节点作为唯一付款依据。',
  'seg-4': '第四条 验收标准与争议解决。验收标准应量化、可检验，避免使用「合格」「满意」等模糊表述。争议解决应优先约定甲方所在地法院或约定仲裁，并明确适用法律。'
}

/** 给 SourceRef 补上 full / 跳转所需 id（契约里是可选字段） */
export function enrichRef(ref: SourceRef): SourceRef {
  const key = ref.index === 1 ? 'seg-3' : 'seg-4'
  return {
    ...ref,
    full: segmentFullTexts[key],
    ...({
      segmentId: key,
      documentId: 'doc-1',
      kbId: 'kb1'
    } as Record<string, string>)
  }
}

const withRefs = (refs: SourceRef[]): SourceRef[] => refs.map(enrichRef)

export const conversations: Array<Conversation & { app?: AppBrief }> = [
  {
    id: 'c1',
    title: '合同风险审查要点',
    appId: 'as1',
    appName: '合同审查助手',
    turnCount: 2,
    updatedAt: iso(0.2 * hour),
    app: appBriefs.as1,
    ownerId: 'u1',
    visibility: 'private'
  },
  {
    id: 'c2',
    title: '报销制度咨询',
    appId: null,
    appName: '通用问答',
    turnCount: 1,
    updatedAt: iso(1.5 * hour),
    ownerId: 'u4',
    visibility: 'private'
  },
  {
    id: 'c3',
    title: '供应商准入条件',
    appId: 'as1',
    appName: '合同审查助手',
    turnCount: 3,
    updatedAt: iso(5 * hour),
    app: appBriefs.as1,
    ownerId: 'u2',
    visibility: 'private'
  },
  {
    id: 'c4',
    title: '季度考核指标说明',
    appId: 'as2',
    appName: '制度问答',
    turnCount: 1,
    updatedAt: iso(day + 2 * hour),
    app: appBriefs.as2,
    ownerId: 'u3',
    visibility: 'private'
  },
  {
    id: 'c5',
    title: '入职流程问题',
    appId: null,
    appName: '通用问答',
    turnCount: 2,
    updatedAt: iso(day + 6 * hour),
    ownerId: 'u4',
    visibility: 'private'
  },
  {
    id: 'c6',
    title: '采购合同逾期责任',
    appId: 'as1',
    appName: '合同审查助手',
    turnCount: 1,
    updatedAt: iso(3 * day),
    app: appBriefs.as1,
    ownerId: 'u2',
    visibility: 'private'
  }
]

/** 消息按会话存放。feedback 可变 —— 赞踩持久化到刷新后仍在 */
export const messagesByConv: Record<string, ChatMessage[]> = {
  c1: [
    {
      id: 'm101',
      conversationId: 'c1',
      role: 'user',
      content: '审查这份采购合同有什么风险？',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: iso(0.5 * hour)
    },
    {
      id: 'm102',
      conversationId: 'c1',
      role: 'assistant',
      content:
        '根据《合同管理制度》的相关规定，我查到以下要点：\n\n1. **付款比例控制** — 前期付款比例原则上不超过合同总额的 30%，尾款需在验收合格后支付 [1]\n2. **付款节点** — 应与履约进度挂钩，不得以单纯时间节点作为唯一付款依据 [1]\n3. **逾期责任** — 合同须明确约定逾期付款的违约责任与计算方式 [2]\n\n建议在合同评审时重点核对以上三项，缺少任一项都应退回业务部门补充。',
      status: '已生成',
      refs: withRefs([
        {
          index: 1,
          title: '第三条 付款条款要求',
          docName: '采购管理制度.pdf',
          kbName: '合同管理制度',
          score: 0.87,
          rank: 1
        },
        {
          index: 2,
          title: '第四条 验收标准与争议解决',
          docName: '采购管理制度.pdf',
          kbName: '合同管理制度',
          score: 0.81,
          rank: 2
        }
      ]),
      suggestions: ['如果对方不同意改怎么处理？', '帮我起草一段修改意见'],
      feedback: 'up',
      tokens: 1120,
      durationMs: 3800,
      failReason: null,
      failHint: null,
      createdAt: iso(0.4 * hour)
    },
    {
      id: 'm103',
      conversationId: 'c1',
      role: 'user',
      content: '这些风险如何规避？',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: iso(0.3 * hour)
    },
    {
      id: 'm104',
      conversationId: 'c1',
      role: 'assistant',
      content:
        '规避建议如下：\n\n1. **付款条款**：补充「逾期按日万分之五支付违约金」，并明确尾款与验收合格挂钩 [1]\n2. **验收标准**：改为可量化指标（如尺寸公差、合格率），附验收清单 [2]\n3. **争议解决**：改约定甲方所在地法院，避免客场诉讼。\n\n需要我按上述三点起草修改意见原文吗？',
      status: '已停止',
      refs: withRefs([
        {
          index: 1,
          title: '第三条 付款条款要求',
          docName: '采购管理制度.pdf',
          kbName: '合同管理制度',
          score: 0.85,
          rank: 1
        },
        {
          index: 2,
          title: '第四条 验收标准与争议解决',
          docName: '采购管理制度.pdf',
          kbName: '合同管理制度',
          score: 0.79,
          rank: 2
        }
      ]),
      suggestions: [],
      feedback: null,
      tokens: 640,
      durationMs: 2100,
      failReason: null,
      failHint: null,
      createdAt: iso(0.25 * hour)
    }
  ],
  c2: [
    {
      id: 'm201',
      conversationId: 'c2',
      role: 'user',
      content: '报销一般需要哪些材料？',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: iso(1.6 * hour)
    },
    {
      id: 'm202',
      conversationId: 'c2',
      role: 'assistant',
      content:
        '这是一个不依赖知识库的通用问题。当前会话未挂载知识库，因此回答基于模型自身知识，不提供引用来源。\n\n通常报销需要：发票、费用明细、审批单、支付凭证。具体清单请以公司财务制度为准——如需基于公司制度的准确答案，请切换到已挂载知识库的应用。',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 420,
      durationMs: 1600,
      failReason: null,
      failHint: null,
      createdAt: iso(1.5 * hour)
    }
  ],
  c3: [
    {
      id: 'm301',
      conversationId: 'c3',
      role: 'user',
      content: '供应商准入需要满足什么条件？',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: iso(5.2 * hour)
    },
    {
      id: 'm302',
      conversationId: 'c3',
      role: 'assistant',
      content:
        '供应商准入一般需具备：有效营业执照、相关资质证书、近三年无重大违法记录，并通过现场或书面评审 [1]。\n\n建议在准入清单中明确必备材料与评分项，避免口头约定。',
      status: '已生成',
      refs: withRefs([
        {
          index: 1,
          title: '第三条 付款条款要求',
          docName: '采购管理制度.pdf',
          kbName: '合同管理制度',
          score: 0.72,
          rank: 1
        }
      ]),
      suggestions: [],
      feedback: 'down',
      tokens: 510,
      durationMs: 1900,
      failReason: null,
      failHint: null,
      createdAt: iso(5.1 * hour)
    }
  ],
  c4: [
    {
      id: 'm401',
      conversationId: 'c4',
      role: 'user',
      content: '季度考核指标怎么定？',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: iso(day + 2.1 * hour)
    },
    {
      id: 'm402',
      conversationId: 'c4',
      role: 'assistant',
      content: '',
      status: '失败',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 800,
      failReason: '对话模型 qwen2:7b-instruct 未加载完成（当前状态：加载失败）',
      failHint: '请到「模型管理」重新加载该模型，或在应用配置中更换为已加载的模型后再试',
      createdAt: iso(day + 2.05 * hour)
    }
  ],
  c5: [
    {
      id: 'm501',
      conversationId: 'c5',
      role: 'user',
      content: '入职第一天要做什么？',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: iso(day + 6.1 * hour)
    },
    {
      id: 'm502',
      conversationId: 'c5',
      role: 'assistant',
      content: '入职第一天通常包括：签署劳动合同、领取办公用品、开通账号、参加入职培训。请以人事部门当日安排为准。',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 280,
      durationMs: 1100,
      failReason: null,
      failHint: null,
      createdAt: iso(day + 6.05 * hour)
    }
  ],
  c6: [
    {
      id: 'm601',
      conversationId: 'c6',
      role: 'user',
      content: '采购合同逾期付款怎么追责？',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: iso(3 * day)
    },
    {
      id: 'm602',
      conversationId: 'c6',
      role: 'assistant',
      content: '应依据合同中约定的逾期违约责任条款追责，一般按日计算违约金 [1]。若合同未约定，需先补充协议再主张。',
      status: '已生成',
      refs: withRefs([
        {
          index: 1,
          title: '第三条 付款条款要求',
          docName: '采购管理制度.pdf',
          kbName: '合同管理制度',
          score: 0.8,
          rank: 1
        }
      ]),
      suggestions: [],
      feedback: null,
      tokens: 360,
      durationMs: 1400,
      failReason: null,
      failHint: null,
      createdAt: iso(3 * day - 1000)
    }
  ]
}

/* ── 免登录凭证三态 ── */

export interface PublicCredential {
  token: string
  appId: string
  enabled: boolean
  expiresAt: string | null
  revokedAt: string | null
}

export const publicCredentials: PublicCredential[] = [
  {
    token: 'pub_ok_demo',
    appId: 'as1',
    enabled: true,
    expiresAt: iso(-30 * day),
    revokedAt: null
  },
  {
    token: 'pub_expired_demo',
    appId: 'as1',
    enabled: true,
    expiresAt: iso(day),
    revokedAt: null
  },
  {
    token: 'pub_revoked_demo',
    appId: 'as1',
    enabled: true,
    expiresAt: null,
    revokedAt: iso(day)
  }
]

/** 免登录会话的消息（独立于登录态会话） */
export const publicMessages: Record<string, ChatMessage[]> = {
  pub_ok_demo: [
    {
      id: 'pm1',
      conversationId: 'public:pub_ok_demo',
      role: 'user',
      content: '付款比例上限是多少？',
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: iso(2 * hour)
    },
    {
      id: 'pm2',
      conversationId: 'public:pub_ok_demo',
      role: 'assistant',
      content: '前期付款比例原则上不超过合同总额的 30%，尾款须在验收合格后支付 [1]。',
      status: '已生成',
      refs: withRefs([
        {
          index: 1,
          title: '第三条 付款条款要求',
          docName: '采购管理制度.pdf',
          kbName: '合同管理制度',
          score: 0.9,
          rank: 1
        }
      ]),
      suggestions: ['尾款支付节点怎么写？'],
      feedback: null,
      tokens: 210,
      durationMs: 900,
      failReason: null,
      failHint: null,
      createdAt: iso(2 * hour - 500)
    }
  ]
}

/**
 * 把凭证翻成公开页要用的应用摘要。
 *
 * **查不到就返回 `null`，不抛也不猜** —— 这里是纯数据层，抛 `ApiError` 是 handler 的活；
 * 而返回 `null` 能让 handler 给外部访客一句可读原因，而不是把
 * `Cannot read properties of undefined (reading 'id')` 当页面错误渲染出去。
 */
export function toPublicContext(cred: PublicCredential): PublicContext | null {
  const app = appBriefs[cred.appId]
  if (!app) return null
  return {
    appId: app.id,
    appName: app.name,
    icon: app.icon,
    description: app.description,
    prologue: app.prologue,
    quickQuestions: app.quickQuestions
  }
}
