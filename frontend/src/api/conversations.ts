import { request } from '@/lib/http'
import type { SourceRef } from './stream-types'
import type { Grant, PageQuery, PageResult, ResourceVisibility } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 消息状态机：生成中 → 已生成 | 已停止 | 失败（02:336） */
export type MessageStatus = '生成中' | '已生成' | '已停止' | '失败'

export interface ChatMessage {
  id: string
  conversationId: string
  role: 'user' | 'assistant'
  content: string
  status: MessageStatus
  refs: SourceRef[]
  suggestions: string[]
  feedback: 'up' | 'down' | null
  tokens: number
  durationMs: number
  failReason: string | null
  failHint: string | null
  createdAt: string
}

export interface Conversation {
  id: string
  title: string
  appId: string | null
  appName: string
  turnCount: number
  updatedAt: string
  /**
   * 归属与可见性（07 §3.1）。会话是私人对话：默认 `private` + `ownerId = 当前用户`，
   * 所以"会话只有自己的"由归属判定落地，不靠前端过滤（§4.2 会话一行）。
   */
  ownerId?: string
  visibility?: ResourceVisibility
  grants?: Grant[]
}

export interface ConversationQuery extends PageQuery {
  appId?: string
  /** 只看自己名下的（07 §六 阶段 1）。不传 = 按数据范围给全部可见的 */
  mine?: boolean
}

/** 免登录链接的解析结果：应用上下文由凭证决定，客户端无法指定 */
export interface PublicContext {
  appId: string
  appName: string
  icon: string
  description: string
  prologue: string
  quickQuestions: string[]
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listConversations = (query: ConversationQuery = {}) =>
  request<PageResult<Conversation>>({ method: 'GET', url: '/conversations', query })

export const getConversation = (id: string) =>
  request<Conversation>({ method: 'GET', url: '/conversations/:id', path: { id } })

export const createConversation = (body: { appId?: string }) =>
  request<Conversation>({ method: 'POST', url: '/conversations', body })

export const renameConversation = (id: string, title: string) =>
  request<Conversation>({
    method: 'PATCH',
    url: '/conversations/:id',
    path: { id },
    body: { title }
  })

export const deleteConversation = (id: string) =>
  request<void>({ method: 'DELETE', url: '/conversations/:id', path: { id } })

export const listMessages = (conversationId: string, query: PageQuery = {}) =>
  request<PageResult<ChatMessage>>({
    method: 'GET',
    url: '/conversations/:conversationId/messages',
    path: { conversationId },
    query
  })

/** 赞踩互斥且可撤销：type 传 null 表示取消 */
export const submitMessageFeedback = (messageId: string, type: 'up' | 'down' | null) =>
  request<ChatMessage>({
    method: 'POST',
    url: '/messages/:messageId/feedback',
    path: { messageId },
    body: { type }
  })

/**
 * 免登录：用访问凭证换应用上下文。
 * 校验三要素（启用 / 未过期 / 未撤销）由后端完成，失败时返回可读原因。
 */
export const resolvePublicContext = (token: string) =>
  request<PublicContext>({ method: 'GET', url: '/public/:token', path: { token }, skipAuthRetry: true })

export const listPublicMessages = (token: string) =>
  request<ChatMessage[]>({
    method: 'GET',
    url: '/public/:token/messages',
    path: { token },
    skipAuthRetry: true
  })
