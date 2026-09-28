/**
 * 会话 / 消息的可变运行时存储 —— mock 的「数据库」。
 *
 * 为什么要有这一层（而不是直接改 fixtures）：
 *  1. fixtures 是种子数据（`export const`），语义是初始夹具；运行时新增的问答
 *     要落进一份可变的表里，才谈得上「落库」。
 *  2. 页面一刷新，所有模块级内存态都会重置 —— 只改内存 = 刷新即丢，正是 #4 的
 *     症状。所以本存储把状态镜像到 `localStorage`，加载时水合、写入时落盘。
 *
 * 时序约定（#4 的关键）：`appendTurn` 在**流结束/中断的收尾处**同步调用、
 * 同步写 localStorage —— 用户点「停止」→ `abort()` → `finish('STOPPED')` → 落库，
 * 没有「先通知 UI 再异步落库」的中断窗口。三种终态（已生成 / 已停止 / 失败）
 * 走同一个入口，只是 status 不同。
 */
import type {
  ChatAttachment,
  ChatMessage,
  Conversation,
  ConversationShare,
  MessageStatus
} from '@/api/conversations'
import type { SourceRef } from '@/api/stream-types'
import { ApiError, ErrorCode } from '@/api/types'
import {
  appBriefs,
  conversations as seedConversations,
  enrichRef,
  messagesByConv as seedMessages,
  publicMessages as seedPublicMessages,
  type AppBrief
} from './fixtures/conversations'

/** localStorage 键。schema 变更时递增版本号，避免旧结构被误水合 */
const STORAGE_KEY = 'ai_plantform_mock_chat_store_v1'

export type StoredConversation = Conversation & { app?: AppBrief }

export type ShareScope = 'APP' | 'CONVERSATION' | 'MESSAGE'

export interface ShareRecord {
  token: string
  conversationId: string
  scope: ShareScope
  messageId?: string
  expiresAt: string | null
  revokedAt: string | null
  createdAt: string
}

interface StoreState {
  conversations: StoredConversation[]
  messagesByConv: Record<string, ChatMessage[]>
  publicMessages: Record<string, ChatMessage[]>
  shares: ShareRecord[]
  attachments: ChatAttachment[]
}

/** 持久化前把 `app` 摘掉（它是 appBriefs 的引用，水合时按 appId 重新挂） */
function stripApp(c: StoredConversation): Conversation {
  const { app: _app, ...rest } = c
  return rest
}

function cloneSeed(): StoreState {
  return {
    conversations: seedConversations.map((c) => ({ ...c })),
    messagesByConv: Object.fromEntries(
      Object.entries(seedMessages).map(([k, v]) => [k, v.map((m) => ({ ...m }))])
    ),
    publicMessages: Object.fromEntries(
      Object.entries(seedPublicMessages).map(([k, v]) => [k, v.map((m) => ({ ...m }))])
    ),
    shares: [],
    attachments: []
  }
}

function hydrateApp(c: Conversation): StoredConversation {
  const app = c.appId ? appBriefs[c.appId] : undefined
  return { ...c, app }
}

function loadState(): StoreState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as StoreState
      if (parsed && Array.isArray(parsed.conversations)) {
        return {
          conversations: parsed.conversations.map(hydrateApp),
          messagesByConv: parsed.messagesByConv ?? {},
          publicMessages: parsed.publicMessages ?? {},
          shares: parsed.shares ?? [],
          attachments: parsed.attachments ?? []
        }
      }
    }
  } catch {
    /* 损坏的 localStorage 不该让 mock 起不来 —— 回退到种子数据 */
  }
  return cloneSeed()
}

const state: StoreState = loadState()

/** 同步写盘。停止 → 落库之间没有异步间隙，刷新前一刻写进去的也带得走 */
export function persist(): void {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        conversations: state.conversations.map(stripApp),
        messagesByConv: state.messagesByConv,
        publicMessages: state.publicMessages,
        shares: state.shares,
        attachments: state.attachments
      })
    )
  } catch {
    /* 隐私模式 / 配额满：mock 不因此崩，但数据不保证跨刷新 */
  }
}

/* ── 读 ── */

/** 可变的会话表 —— handlers 直接 filter/sort 后分页，与旧写法一致 */
export const conversations = state.conversations

/** 可变的消息表：conversationId → 消息（按时间正序） */
export const messagesByConv = state.messagesByConv

export const publicMessages = state.publicMessages

export function findConversation(id: string): StoredConversation | undefined {
  return state.conversations.find((c) => c.id === id)
}

export function messagesOf(conversationId: string): ChatMessage[] {
  return state.messagesByConv[conversationId] ?? []
}

export function findMessage(id: string): ChatMessage | undefined {
  for (const list of Object.values(state.messagesByConv)) {
    const hit = list.find((m) => m.id === id)
    if (hit) return hit
  }
  for (const list of Object.values(state.publicMessages)) {
    const hit = list.find((m) => m.id === id)
    if (hit) return hit
  }
  return undefined
}

/**
 * 改消息上的可变字段（如赞踩）后调一次，把变更写盘。
 * 直接改对象属性不会触发任何持久化 —— 少了这一句，刷新后赞踩就没了。
 */
export function touchMessage(msg: ChatMessage, patch: Partial<ChatMessage>): ChatMessage {
  Object.assign(msg, patch)
  persist()
  return msg
}

/* ── 会话写 ── */

export function addConversation(conv: StoredConversation): StoredConversation {
  state.conversations.unshift(conv)
  state.messagesByConv[conv.id] ??= []
  persist()
  return conv
}

export function removeConversation(id: string): void {
  const idx = state.conversations.findIndex((c) => c.id === id)
  if (idx >= 0) state.conversations.splice(idx, 1)
  delete state.messagesByConv[id]
  state.shares = state.shares.filter((s) => s.conversationId !== id)
  persist()
}

export function patchConversation(
  id: string,
  patch: Partial<Conversation>
): StoredConversation | undefined {
  const conv = findConversation(id)
  if (!conv) return undefined
  Object.assign(conv, patch)
  persist()
  return conv
}

function titleFromQuestion(question: string): string {
  const t = question.trim().replace(/\s+/g, ' ')
  if (!t) return '新建会话'
  return t.length > 24 ? `${t.slice(0, 24)}…` : t
}

/**
 * 取或建会话。`id` 给了但不存在时**不猜**，抛 404 —— 静默新建会把
 * 「指错会话」伪装成「新开会话」，最难查。
 */
export function ensureConversation(params: {
  id?: string
  appId?: string | null
  ownerId: string
  title?: string
}): StoredConversation {
  const { id, appId, ownerId, title } = params
  if (id) {
    const found = findConversation(id)
    if (!found) {
      throw new ApiError(ErrorCode.NOT_FOUND, `会话「${id}」不存在`)
    }
    return found
  }
  const app = appId ? appBriefs[appId] : undefined
  const created: StoredConversation = {
    id: `c${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`,
    title: title ?? '新建会话',
    appId: appId ?? null,
    appName: app?.name ?? '通用问答',
    turnCount: 0,
    updatedAt: new Date().toISOString(),
    app,
    // 归属只能来自已认证主体；拿不到人时留空，不把「匿名」伪装成某个用户
    ownerId: ownerId || '',
    visibility: 'PRIVATE'
  }
  return addConversation(created)
}

/* ── 附件 ── */

export function saveAttachment(att: ChatAttachment): ChatAttachment {
  const idx = state.attachments.findIndex((a) => a.id === att.id)
  if (idx >= 0) state.attachments[idx] = att
  else state.attachments.push(att)
  persist()
  return att
}

export function findAttachment(id: string): ChatAttachment | undefined {
  return state.attachments.find((a) => a.id === id)
}

/** 附件 id → 回执。查不到的 id 不编造内容，直接跳过（可选字段，宁缺毋滥） */
export function resolveAttachments(ids?: string[]): ChatAttachment[] {
  if (!ids?.length) return []
  return ids.map((id) => findAttachment(id)).filter((a): a is ChatAttachment => !!a)
}

/* ── 一轮问答落库（#4 核心） ── */

export interface AppendTurnInput {
  conversationId?: string
  /** 免登录场景：有值则落到 publicMessages，不进会话表 */
  credentialToken?: string
  appId?: string | null
  ownerId: string
  question: string
  answer: string
  status: MessageStatus
  refs?: SourceRef[]
  suggestions?: string[]
  tokens?: number
  durationMs?: number
  failReason?: string | null
  failHint?: string | null
  attachmentIds?: string[]
  /** 助手消息 id。传流式的 messageId，保证反馈接口刷新后仍按同一 id 回写 */
  messageId?: string
  /**
   * 生成模式（契约 `ChatStreamOptions.mode`）。
   * 传了就按显式意图落库；不传时才回落到「末尾问答对且问题相同 = 重生成」的近似识别。
   */
  mode?: 'NEW' | 'REGENERATE' | 'CONTINUE'
  /** 重生成 / 继续生成的目标助手消息 id */
  targetMessageId?: string
}

export interface AppendTurnResult {
  conversationId: string
  user: ChatMessage
  assistant: ChatMessage
}

function buildMessage(
  base: Omit<ChatMessage, 'id' | 'createdAt'> & { id: string }
): ChatMessage {
  return { ...base, createdAt: new Date().toISOString() }
}

/**
 * 把一轮问答写进存储。三种终态都从这里走：
 *  - `已生成`：流自然跑完
 *  - `已停止`：用户点停止 → `abort()` → `finish('STOPPED')`
 *  - `失败`：`onError` 后 `finish('FAILED')`
 *
 * **重生成 / 继续生成**：契约里 `mode` + `targetMessageId` 显式表达意图 ——
 *  - `regenerate` 覆盖目标助手消息（不新增消息对）
 *  - `continue`   把本轮增量接到目标助手消息正文之后（不伪造新问句、不重复问题）
 * 未传 mode 时回落到旧的「末尾问答对且问题相同」近似识别，保证旧调用不破。
 */
export function appendTurn(input: AppendTurnInput): AppendTurnResult {
  const {
    credentialToken,
    question,
    answer,
    status,
    refs,
    suggestions,
    tokens,
    durationMs,
    failReason,
    failHint,
    attachmentIds,
    messageId,
    mode,
    targetMessageId
  } = input

  const attachments = resolveAttachments(attachmentIds)
  const enrichedRefs = (refs ?? []).map((r) => enrichRef(r))

  if (credentialToken) {
    const key = credentialToken
    const list = (state.publicMessages[key] ??= [])
    const conversationId = `public:${credentialToken}`

    // 重生成 / 继续：先在公开消息里找目标助手消息，就地改写
    const target = targetMessageId ? list.find((m) => m.id === targetMessageId) : undefined
    if (target && target.role === 'ASSISTANT') {
      if (mode === 'CONTINUE') {
        target.content = `${target.content}${answer}`
        if (enrichedRefs.length) target.refs = enrichedRefs
      } else {
        target.content = answer
        target.refs = enrichedRefs
      }
      target.status = status
      target.suggestions = suggestions ?? []
      target.tokens = tokens ?? 0
      target.durationMs = durationMs ?? 0
      target.failReason = failReason ?? null
      target.failHint = failHint ?? null
      if (messageId) target.id = messageId
      persist()
      const user = list.filter((m) => m.role === 'USER').pop()
      return {
        conversationId,
        user: user ?? target,
        assistant: target
      }
    }

    const user = buildMessage({
      id: `um_${Date.now().toString(36)}`,
      conversationId,
      role: 'USER',
      content: question,
      status: 'GENERATED',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      attachments: attachments.length ? attachments : undefined
    })
    const assistant = buildMessage({
      id: messageId ?? `am_${Date.now().toString(36)}`,
      conversationId,
      role: 'ASSISTANT',
      content: answer,
      status,
      refs: enrichedRefs,
      suggestions: suggestions ?? [],
      feedback: null,
      tokens: tokens ?? 0,
      durationMs: durationMs ?? 0,
      failReason: failReason ?? null,
      failHint: failHint ?? null
    })
    list.push(user, assistant)
    persist()
    return { conversationId, user, assistant }
  }

  const conv = ensureConversation({
    id: input.conversationId,
    appId: input.appId ?? null,
    ownerId: input.ownerId,
    title: titleFromQuestion(question)
  })
  const list = (state.messagesByConv[conv.id] ??= [])

  const assistantBase = {
    conversationId: conv.id,
    role: 'ASSISTANT' as const,
    content: answer,
    status,
    refs: enrichedRefs,
    suggestions: suggestions ?? [],
    feedback: null,
    tokens: tokens ?? 0,
    durationMs: durationMs ?? 0,
    failReason: failReason ?? null,
    failHint: failHint ?? null
  }

  // 显式意图优先
  if (targetMessageId && (mode === 'REGENERATE' || mode === 'CONTINUE')) {
    const target = list.find((m) => m.id === targetMessageId && m.role === 'ASSISTANT')
    if (target) {
      if (mode === 'CONTINUE') {
        Object.assign(target, assistantBase, {
          content: `${target.content}${answer}`,
          attachments: target.attachments,
          createdAt: target.createdAt
        })
      } else {
        Object.assign(target, assistantBase, {
          attachments: target.attachments,
          createdAt: target.createdAt
        })
      }
      if (messageId) target.id = messageId
      conv.updatedAt = new Date().toISOString()
      persist()
      // 找它前面最近的问句作为配对的 user 消息
      const idx = list.indexOf(target)
      const user = list.slice(0, idx).reverse().find((m) => m.role === 'USER') ?? target
      return { conversationId: conv.id, user, assistant: target }
    }
  }

  // 回落：旧近似识别（末尾问答对且问题文本相同 = 重生成）
  const last = list[list.length - 1]
  const prev = list[list.length - 2]
  const isRegenerate =
    mode !== 'NEW' &&
    !!last &&
    last.role === 'ASSISTANT' &&
    !!prev &&
    prev.role === 'USER' &&
    prev.content === question

  if (isRegenerate && last) {
    Object.assign(last, assistantBase, {
      attachments: last.attachments,
      createdAt: last.createdAt
    })
    if (messageId) last.id = messageId
    conv.updatedAt = new Date().toISOString()
    persist()
    return { conversationId: conv.id, user: prev!, assistant: last }
  }

  const user = buildMessage({
    id: `um_${Date.now().toString(36)}`,
    conversationId: conv.id,
    role: 'USER',
    content: question,
    status: 'GENERATED',
    refs: [],
    suggestions: [],
    feedback: null,
    tokens: 0,
    durationMs: 0,
    failReason: null,
    failHint: null,
    attachments: attachments.length ? attachments : undefined
  })
  const assistant = buildMessage({
    id: messageId ?? `am_${Date.now().toString(36)}`,
    ...assistantBase
  })
  list.push(user, assistant)
  conv.turnCount += 1
  conv.updatedAt = new Date().toISOString()
  persist()
  return { conversationId: conv.id, user, assistant }
}

/* ── 分享（#5） ── */

/**
 * 分享链接形状固定为 `/#/public/:token`（hash 路由，见 bootstrap/router.ts）。
 * ⚠️ 不要拼成 `/#/public?token=xxx` —— 那个形状在路由表里不存在，打开即 404
 * （现存 CredentialsPanel 就踩了这个坑，见交付报告）。
 */
export function buildShareUrl(token: string): string {
  if (typeof window !== 'undefined' && window.location) {
    const { origin, pathname } = window.location
    return `${origin}${pathname}#/public/${token}`
  }
  return `/#/public/${token}`
}

function newShareToken(): string {
  return `sh_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}

export function toShareView(rec: ShareRecord): ConversationShare {
  return { token: rec.token, url: buildShareUrl(rec.token), expiresAt: rec.expiresAt }
}

export function createShare(params: {
  conversationId: string
  scope: ShareScope
  messageId?: string
  expiresAt?: string | null
}): ShareRecord {
  const rec: ShareRecord = {
    token: newShareToken(),
    conversationId: params.conversationId,
    scope: params.scope,
    messageId: params.messageId,
    expiresAt: params.expiresAt ?? null,
    revokedAt: null,
    createdAt: new Date().toISOString()
  }
  state.shares.push(rec)
  persist()
  return rec
}

export function listShares(conversationId: string): ShareRecord[] {
  return state.shares.filter((s) => s.conversationId === conversationId && !s.revokedAt)
}

export function revokeShare(conversationId: string, token: string): boolean {
  const rec = state.shares.find((s) => s.conversationId === conversationId && s.token === token)
  if (!rec) return false
  rec.revokedAt = new Date().toISOString()
  persist()
  return true
}

export function findShareByToken(token: string): ShareRecord | undefined {
  return state.shares.find((s) => s.token === token)
}

/** 分享可见的消息：消息级只给那一条，其余给整段会话 */
export function messagesOfShare(share: ShareRecord): ChatMessage[] {
  const list = messagesOf(share.conversationId)
  if (share.scope === 'MESSAGE' && share.messageId) {
    return list.filter((m) => m.id === share.messageId)
  }
  return list
}
