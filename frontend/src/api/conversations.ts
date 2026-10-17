import { request } from '@/lib/http'
import type { SourceRef } from './stream-types'
import type { ClassificationLevel, Grant, PageQuery, PageResult, ResourceVisibility } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 消息状态机：GENERATING → GENERATED | STOPPED | FAILED（02:336） */
export type MessageStatus = 'GENERATING' | 'GENERATED' | 'STOPPED' | 'FAILED'

/** 聊天附件回执。上传后按 id 挂到消息上（`ChatMessage.attachments`） */
export interface ChatAttachment {
  id: string
  name: string
  size: number
  mime?: string
}

/** 消息角色（2.4 补录） */
export type ChatRole = 'USER' | 'ASSISTANT'
/** 赞踩（2.4 补录）。与 `FeedbackItem.type`、`submitMessageFeedback` 同口径 */
export type MessageFeedback = 'UP' | 'DOWN'

export interface ChatMessage {
  id: string
  conversationId: string
  role: ChatRole
  content: string
  status: MessageStatus
  refs: SourceRef[]
  suggestions: string[]
  feedback: MessageFeedback | null
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
   * 归属与可见性（07 §3.1）。会话是私人对话：默认 `PRIVATE` + `ownerId = 当前用户`，
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
  /**
   * ★ **公开面必须返回它**（= 凭证创建时冻结的密级上限，`13 §5.2` ①）。
   *
   * 为什么公开页需要它：访客应当知道"这条链接读得到多密的内容"，否则他会以为"打不开 = 这里没有"。
   * ★ 与 `AccessCredential.maxClassification` **同源同义** —— 只是换了一个消费面（免登录面）。
   *
   * ★★ 2026-10-14（第 17 周开工，`ROADMAP §11.2` 判据 1）**从可选改成必填**：此前标成可选只为一件事 ——
   * 契约冻结期"只加可选字段"，于是"后端必须返回"这条约束**只写在注释里**，而注释不会被类型检查。
   * 现在由**类型系统**承担它：谁构造 `PublicContext` 谁就必须给出这个值（mock 的 handlers / fixtures 已同批跟上）。
   */
  maxClassification: ClassificationLevel
}

/**
 * 会话级分享回执。`url` 是**前端真能打开**的形状：hash 路由 `/#/public/:token`
 * （见 bootstrap/router.ts 与 pages/conversation/route.ts）。
 */
export interface ConversationShare {
  token: string
  url: string
  /**
   * ★ **创建时冻结**的密级上限 = 分享者当时的 clearance（`13 §5.2` ① / `13:291`）。
   *
   * 与 `AccessCredential.maxClassification` **同一条口径、同一个理由**：分享链接不是"内容快照"，
   * 而是"一个受限的查询凭证" —— 每次打开都要**用这个冻结值重新过滤**，
   * 且被分享内容**升密后立即撤销**（`13 §5.3`）。★ **必填**，且**后加不可追溯**。
   */
  maxClassification: ClassificationLevel
  expiresAt: string | null
}

/** 分享范围：整应用 / 整会话 / 单条消息 */
export type ShareScope = 'APP' | 'CONVERSATION' | 'MESSAGE'

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

/**
 * 消息列表查询。
 *
 * 两种取数方式**互斥**：
 *  - `page`/`size`：常规分页（`page=-1` 表示最后一页，聊天窗口先看最近几条）
 *  - `around`：以某条消息为中心取一个窗口（深链 / 分享链接跳到某条消息）
 *
 * 传了 `around` 就**忽略 `page`**；`size` 在 around 模式下表示窗口宽度（中心前后各约 size/2）。
 */
export interface MessageQuery extends PageQuery {
  /**
   * 目标消息 id。以该消息为中心，前后各取若干条，使目标落在窗口内。
   * 与 `page` 互斥：传了 `around` 就忽略 `page`。
   * 找不到该消息时应报 404，而不是静默回退到第一页。
   */
  around?: string
}

export const listMessages = (conversationId: string, query: MessageQuery = {}) =>
  request<PageResult<ChatMessage>>({
    method: 'GET',
    url: '/conversations/:conversationId/messages',
    path: { conversationId },
    query
  })

/**
 * 停止回执（`POST /conversations/:conversationId/messages/:messageId/stop`）。
 *
 * ★ 它回答的是「服务端有没有收下这次停止请求」，**不是**「这一轮已经停住了」——
 * 终态由生成线程写到消息上（`STOPPED`），那一刻与这次响应之间有一段正常的间隔。
 * 所以 `status` 在城市话里是「服务端此刻读到的状态」，`GENERATING` 是**正常**取值。
 */
export interface ChatStopReceipt {
  /** 收下并置上了停止信号。`false` = 这一轮已经收尾（停止请求晚到了），服务端什么都没做 */
  accepted: boolean
  /** 服务端此刻读到的消息状态（可能仍是 `GENERATING` —— 终态由生成线程落库） */
  status: MessageStatus
}

/**
 * ★★ 停止生成 —— **显式停止的表达位**（`03 §1.5` 的"显式停止"那一行；`#119` 的收口）。
 *
 * <h3>为什么必须加这条端点（不这么做，"停止"与"断开"在服务端长得一模一样）</h3>
 *
 * 契约给「用户停止」与「连接断开」**两个不同的终态**：
 *
 * | 事件 | 契约终态（`stream-types.ts` 的 `StreamTerminationPath`） |
 * |---|---|
 * | 用户点停止（`ABORT`） | `done(STOPPED)`，**不发** `error`，**保留**已生成内容 |
 * | 连接断开（`DISCONNECT`） | `error` + `done(FAILED)` |
 *
 * 而在此之前，前端唯一的停止动作是 `lib/sse.ts` 的 `abort()`（掐 fetch）——
 * 它对服务端**只表现为"连接断了"**⇒ 服务端分不出"用户点了停止"与"网线掉了"，
 * 于是用户停止会被记成故障、真正的断网会被记成用户意图（`A3 失败可见`失真）。
 * 停止必须是一个**独立于连接的显式请求**，这条端点就是它。
 *
 * <h3>★★ 形状：按消息 id（不是按会话）</h3>
 *
 * 两条都可行，取舍写在这里，免得后人以为是随手选的：
 *
 * - **按消息 id（本选择）**：与既有的停止机制 `ChatStopSignal`（**进程内、按助手消息 id** 的一次性信号）
 *   一一对应 —— 收到即 `request(messageId)`，**没有第二处"这一轮是谁"的口径**，也不需要先在库里
 *   查"这个会话当前哪条消息在生成"。消息 id 由 `meta` 帧给出，客户端在收到 `meta` 之后就有它。
 *   代价：没收到 `meta` 之前点停止时客户端**拿不到** messageId（见下面的降级）。
 * - 按会话 id：不需要 messageId，但它必须由服务端回答"这个会话**当前哪一轮**在生成"——
 *   那是第二处真相源，且多出一个失败态（"当前没有正在生成的消息"）。
 *
 * <h3>★★ 「还没收到 `meta` 就点了停止」怎么处置（写下来的降级，不是默默如此）</h3>
 *
 * `meta` 是流的第一条业务帧，且它带着本轮的 `messageId`。在它到达之前客户端**没有**这个值，
 * 于是发不出按消息的停止请求。契约在此**明确允许**降级：
 *
 * - 客户端**跳过**这次请求，**直接** `abort()`（`lib/sse.ts`）；
 * - 服务端因此只看到"连接断了" ⇒ 本轮按 `DISCONNECT` 收尾，库里是 `FAILED`
 *   （而界面侧由 `abort()` 分支给出 `STOPPED`）。
 *
 * ★ 这是一个**已知且被接受**的不一致窗口（它只存在于 `meta` 到达之前，正常是毫秒级）；
 * 要消掉它只能改成按会话的端点（上面那条代价），本批选择"一处机制"而不是"消掉这个窗口"。
 *
 * <h3>★★ 服务端的判定顺序（写死，不看竞态）</h3>
 *
 * 「收到显式停止」**优先于**「连接断了」：先停止、再断连 ⇒ 终态必须是 `STOPPED`（不是 `FAILED`）。
 * 反过来，从头到尾没收到过停止、只是连接断了 ⇒ `FAILED`。
 *
 * <h3>★ 边界（本批明确不做，见交付报告）</h3>
 *
 * 这条端点走**登录面**（`conversation:view`）。免登录公开面的问答流（`X-Access-Credential`）
 * 今天**没有**停止端点 ⇒ 公开页的"停止"仍退化为 `abort()`（= `DISCONNECT` ⇒ `FAILED`）。
 *
 * @param conversationId 会话 id（与 `listMessages` 同一条路径前缀，也是权限判定的入口）
 * @param messageId 助手消息 id（`meta` 帧的 `messageId`）
 */
export const stopMessage = (conversationId: string, messageId: string): Promise<ChatStopReceipt> =>
  request<ChatStopReceipt>({
    method: 'POST',
    url: '/conversations/:conversationId/messages/:messageId/stop',
    path: { conversationId, messageId }
  })

/**
 * ★★ 停止生成 —— **公开面（免登录）**的那一条（`#143` 前半，2026-10-16）。
 *
 * <h3>为什么不能复用上面那条 `stopMessage`</h3>
 *
 * 三条都可核对：
 *
 * | | 登录面 `stopMessage` | 公开面（本操作） |
 * |---|---|---|
 * | 路径参数 | `conversationId`（**UUID**） | `token`（链接原文） |
 * | 会话 id 从哪来 | 客户端持有 | ★ **服务端从消息行反解** —— 公开页的 `conversationId` 是合成串 `public:<token>`，打到 UUID 路径变量上必 400 |
 * | 正当性 | JWT 的 `conversation:view` | 令牌本身有效（`PublicTokenResolver`，四态码） |
 *
 * ⇒ 公开面**只发消息 id**：请求体里没有会话 id 可填，所以「客户端指定这条链接能停哪个会话」
 * 这条越权在形状上就不存在。
 *
 * <h3>回执形状与登录面**同构**</h3>
 *
 * 同样是 `accepted` + 服务端此刻读到的状态（`status` 仍可能是 `GENERATING` —— 终态由生成线程落库，
 * 与这次响应之间有一段正常的间隔）。`accepted = false` = 这一轮已经收尾（停止晚到了），**不是**错误。
 *
 * ★ 匿名访客调它时**没有** `Authorization`；带登录身份调它只是收窄，不是正当性来源
 * （与 `resolvePublicContext` / 公开问答流同一条，见 `08 §4.4.4`）。
 *
 * @param token 链接里的令牌原文
 * @param messageId 助手消息 id（`meta` 帧给出）
 */
export const stopPublicMessage = (token: string, messageId: string): Promise<ChatStopReceipt> =>
  request<ChatStopReceipt>({
    method: 'POST',
    url: '/public/:token/messages/:messageId/stop',
    path: { token, messageId },
    skipAuthRetry: true
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
export const submitMessageFeedback = (messageId: string, type: MessageFeedback | null) =>
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
