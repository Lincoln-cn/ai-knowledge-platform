import { request } from '@/lib/http'
import type { SourceRef } from './stream-types'
import type { Grant, PageQuery, PageResult, ResourceVisibility } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 消息状态机：生成中 → 已生成 | 已停止 | 失败（02:336） */
export type MessageStatus = '生成中' | '已生成' | '已停止' | '失败'

/** 聊天附件回执。上传后按 id 挂到消息上（`ChatMessage.attachments`） */
export interface ChatAttachment {
  id: string
  name: string
  size: number
  mime?: string
}

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
  /** 可选：本条消息携带的附件。无附件时整个字段可缺省 */
  attachments?: ChatAttachment[]
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
  /** 按标题 / 应用名模糊搜索（PageQuery 已有该字段，这里显式声明语义） */
  keyword?: string
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

/**
 * 会话级分享回执。`url` 是**前端真能打开**的形状：hash 路由 `/#/public/:token`
 * （见 bootstrap/router.ts 与 pages/conversation/route.ts）。
 */
export interface ConversationShare {
  token: string
  url: string
  expiresAt: string | null
}

/** 分享范围：整应用 / 整会话 / 单条消息 */
export type ShareScope = 'app' | 'conversation' | 'message'

export interface CreateShareBody {
  scope: ShareScope
  messageId?: string
  /** 时效；null 或不传表示长期有效 */
  expiresAt?: string | null
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

/**
 * 按 id 取一条知识分段（引用回溯用）。
 * 跳转「分段管理」与抽屉里补全文都靠它；看不见一律 404（与单条取数同口径）。
 */
export interface SegmentBrief {
  id: string
  documentId: string
  title: string
  content: string
  charCount: number
  enabled: boolean
}

export const getSegment = (id: string) =>
  request<SegmentBrief>({ method: 'GET', url: '/segments/:id', path: { id } })

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

/* ────────────────────────── 附件上传 ────────────────────────── */

/**
 * 上传一个聊天附件。走 multipart（与知识库文档上传同一套出口约定）。
 * 返回的 id 挂到 `ChatMessage.attachments` / 问答请求的 `attachmentIds` 上。
 */
export const uploadFile = (file: File): Promise<ChatAttachment> => {
  const form = new FormData()
  form.append('file', file)
  return request<ChatAttachment>({ method: 'POST', url: '/files', body: form })
}

/* ────────────────────────── 会话级分享 ────────────────────────── */

/** 给会话建一个免登录分享链接。scope 决定公开页能看到的范围 */
export const createConversationShare = (
  conversationId: string,
  body: CreateShareBody
): Promise<ConversationShare> =>
  request<ConversationShare>({
    method: 'POST',
    url: '/conversations/:id/shares',
    path: { id: conversationId },
    body
  })

export const listConversationShares = (
  conversationId: string
): Promise<ConversationShare[]> =>
  request<ConversationShare[]>({
    method: 'GET',
    url: '/conversations/:id/shares',
    path: { id: conversationId }
  })

export const revokeConversationShare = (
  conversationId: string,
  token: string
): Promise<void> =>
  request<void>({
    method: 'DELETE',
    url: '/conversations/:id/shares/:token',
    path: { id: conversationId, token }
  })
