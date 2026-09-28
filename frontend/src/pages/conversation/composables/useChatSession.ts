import { computed, reactive, ref, type Ref } from 'vue'
import { conversationsApi, streamApi } from '@/api'
import type {
  ChatAttachment,
  ChatMessage,
  Conversation,
  MessageStatus
} from '@/api/conversations'
import type { ChatMode, SourceRef, StreamHandle } from '@/api/stream-types'
import { ApiError } from '@/api/types'

/* ── 本模块展示用扩展类型（契约字段之外的可选补充） ── */

export interface AppBrief {
  id: string
  name: string
  icon: string
  description: string
  type: string
  modelName: string
  modelId: string
  prologue: string
  quickQuestions: string[]
  kbNames: string[]
  multiTurn: boolean
  thinkingEnabled: boolean
  hasKnowledge: boolean
}

export type ConversationEx = Conversation & { app?: AppBrief }

/** 契约 ChatMessage.attachments 落盘前后的兼容读写（字段可选） */
export type ChatMessageEx = ChatMessage & { attachments?: ChatAttachment[] }

/** 契约 SourceRef 已带 segmentId/documentId/kbId —— 这里保留别名，兼容既有引用 */
export type SourceRefEx = SourceRef

/** 附件上传态。发送时只带上「已就绪」的真实 id，失败的必须能看见原因并移除 */
export type AttachmentStatus = 'uploading' | 'ready' | 'failed'

export interface AttachmentDraft {
  /** 上传前是本地临时 id，上传成功后换成服务端真实 id */
  id: string
  name: string
  size: number
  mime?: string
  status: AttachmentStatus
  /** 上传失败的可读原因 */
  error?: string
}

export function formatBytes(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

/** 已生成但正文为空：要给提示，不能留白还照出操作条 */
export function isEmptyAnswer(msg: ChatMessage): boolean {
  return msg.role === 'ASSISTANT' && msg.status === 'GENERATED' && !msg.content.trim()
}

export function readAppBrief(raw: unknown): AppBrief | null {
  if (!raw || typeof raw !== 'object') return null
  const a = raw as Partial<AppBrief>
  if (!a.id || !a.name) return null
  return {
    id: a.id,
    name: a.name,
    icon: a.icon || a.name.slice(0, 1),
    description: a.description || '',
    type: a.type || '知识应用',
    modelName: a.modelName || '',
    modelId: a.modelId || '',
    prologue: a.prologue || '',
    quickQuestions: a.quickQuestions || [],
    kbNames: a.kbNames || [],
    multiTurn: a.multiTurn ?? true,
    thinkingEnabled: a.thinkingEnabled ?? false,
    hasKnowledge: a.hasKnowledge ?? (a.kbNames?.length ? true : false)
  }
}

export function toRefEx(ref: SourceRef): SourceRefEx {
  return { ...ref }
}

export function errText(error: unknown, fallback: string): { reason: string; hint?: string } {
  if (error instanceof ApiError) return { reason: error.message, hint: error.hint }
  if (error instanceof Error) return { reason: error.message || fallback }
  return { reason: fallback }
}

export function groupLabel(updatedAt: string): string {
  const t = new Date(updatedAt).getTime()
  const dayMs = 24 * 3600_000
  const startToday = new Date()
  startToday.setHours(0, 0, 0, 0)
  if (t >= startToday.getTime()) return '今天'
  if (t >= startToday.getTime() - dayMs) return '昨天'
  return '更早'
}

/**
 * 必须是 reactive：往响应式数组里 push 一个原始对象、再在外部改它的属性，
 * 不会触发重渲染（onDelta/onRefs/onStatus 全都反映不到界面上）。
 */
function toReactiveMessage(raw: ChatMessage): ChatMessage {
  return reactive({ ...raw }) as ChatMessage
}

/* ── 会话列表 ── */

const HISTORY_PAGE_SIZE = 50

export function useHistoryPanel() {
  const list: Ref<ConversationEx[]> = ref([])
  const loading = ref(false)
  const loadingMore = ref(false)
  const loadError = ref<{ reason: string; hint?: string } | null>(null)
  /** 还有更早的会话没拉完 */
  const hasMore = ref(false)
  /** 当前搜索词；空串 = 不筛 */
  const keyword = ref('')
  let page = 1

  const groups = computed(() => {
    const map = new Map<string, ConversationEx[]>()
    for (const c of list.value) {
      const key = groupLabel(c.updatedAt)
      const arr = map.get(key) ?? []
      arr.push(c)
      map.set(key, arr)
    }
    return [...map.entries()]
  })

  const fetchPage = async (appId: string | undefined, p: number) => {
    const result = await conversationsApi.listConversations({
      appId,
      keyword: keyword.value.trim() || undefined,
      page: p,
      size: HISTORY_PAGE_SIZE
    })
    return result
  }

  const mapConvs = (raw: Conversation[]): ConversationEx[] =>
    raw.map((c) => {
      const ex = c as ConversationEx
      if (!ex.app && ex.appId) ex.app = readAppBrief((c as ConversationEx).app) ?? undefined
      return ex
    })

  const load = async (appId?: string) => {
    loading.value = true
    loadError.value = null
    page = 1
    try {
      const result = await fetchPage(appId, 1)
      list.value = mapConvs(result.list)
      hasMore.value = result.total > list.value.length
    } catch (error) {
      loadError.value = errText(error, '加载历史会话失败')
    } finally {
      loading.value = false
    }
  }

  /** 加载更早的一页并接在后面（历史列表按更新时间倒序，后面的更早） */
  const loadMore = async (appId?: string) => {
    if (loadingMore.value || !hasMore.value) return
    loadingMore.value = true
    try {
      page += 1
      const result = await fetchPage(appId, page)
      const seen = new Set(list.value.map((c) => c.id))
      list.value = [...list.value, ...mapConvs(result.list).filter((c) => !seen.has(c.id))]
      hasMore.value = result.total > list.value.length
    } catch (error) {
      // 加载更多失败不该把已有列表清掉 —— 只报错并把页码退回去
      page -= 1
      loadError.value = errText(error, '加载更多历史会话失败')
    } finally {
      loadingMore.value = false
    }
  }

  /** 按关键词搜索（空串 = 恢复全量）。搜索结果从第一页重来 */
  const search = async (appId: string | undefined, kw: string) => {
    keyword.value = kw
    await load(appId)
  }

  const remove = async (id: string) => {
    await conversationsApi.deleteConversation(id)
    list.value = list.value.filter((c) => c.id !== id)
  }

  const rename = async (id: string, title: string) => {
    const updated = (await conversationsApi.renameConversation(id, title)) as ConversationEx
    const idx = list.value.findIndex((c) => c.id === id)
    if (idx >= 0) list.value[idx] = { ...list.value[idx], ...updated }
  }

  const prepend = (conv: ConversationEx) => {
    list.value = [conv, ...list.value.filter((c) => c.id !== conv.id)]
  }

  const touch = (conv: ConversationEx) => {
    const idx = list.value.findIndex((c) => c.id === conv.id)
    const next = { ...conv, updatedAt: new Date().toISOString() }
    if (idx >= 0) {
      list.value.splice(idx, 1)
      list.value.unshift(next)
    } else {
      list.value.unshift(next)
    }
  }

  return {
    list,
    groups,
    loading,
    loadingMore,
    loadError,
    hasMore,
    keyword,
    load,
    loadMore,
    search,
    remove,
    rename,
    prepend,
    touch
  }
}

/* ── 问答会话（流式） ── */

export interface ChatSessionOptions {
  /** 应用 id；不传为通用问答 */
  appId?: string
  credentialToken?: string
  thinking?: Ref<boolean>
  onConversationCreated?: (conv: ConversationEx) => void
  onNeedConversation?: () => Promise<string | null>
}

export function useChatSession(options: ChatSessionOptions = {}) {
  const messages: Ref<ChatMessageEx[]> = ref([])
  const loadingHistory = ref(false)
  const loadingEarlier = ref(false)
  const loadError = ref<{ reason: string; hint?: string } | null>(null)
  const generating = ref(false)
  /** 生成失败时的可读原因与下一步 —— 绝不静默失败 */
  const streamError = ref<{ reason: string; hint?: string } | null>(null)
  /** 还有更早的消息没拉完（长会话截断的出口） */
  const hasEarlier = ref(false)

  let handle: StreamHandle | null = null
  /** 当前会话 id；加载更早时要用 */
  let activeConvId = ''
  /** 已拉到的最早一页（page 从后往前走，-1 是最后一页） */
  let earliestPage = 1
  const MSG_PAGE_SIZE = 50

  const lastAssistant = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'ASSISTANT') return messages.value[i]
    }
    return null
  })

  /**
   * 统一从响应式数组取代理对象再改属性 —— 否则赞踩/流式回调不会触发视图。
   * 返回的可能是本地扩展消息（带 attachments），调用方按 ChatMessageEx 读写。
   */
  const resolveMsg = (msg: ChatMessage): ChatMessageEx => {
    const found = messages.value.find((m) => m.id === msg.id)
    return (found as ChatMessageEx | undefined) ?? (msg as ChatMessageEx)
  }

  /**
   * 打开会话：默认先拿**最近**一页（page=-1），再由「加载更早」往前翻。
   * 固定 size:100 且只取 page:1 会把 >100 条后的消息永久截断 —— 这里按页可回溯。
   *
   * `around`：深链到某条消息时，以它为中心取窗口（与 page 互斥，见 MessageQuery）。
   */
  const loadHistory = async (conversationId: string, opts: { around?: string } = {}) => {
    loadingHistory.value = true
    loadError.value = null
    messages.value = []
    activeConvId = conversationId
    hasEarlier.value = false
    earliestPage = 1
    try {
      if (options.credentialToken) {
        const list = await conversationsApi.listPublicMessages(options.credentialToken)
        messages.value = list.map((m) => toReactiveMessage(m) as ChatMessageEx)
        return
      }
      if (opts.around) {
        // 深链：围绕目标消息取窗口，目标必须落在结果里（mock 找不到会 404）
        const result = await conversationsApi.listMessages(conversationId, {
          around: opts.around,
          size: MSG_PAGE_SIZE
        })
        messages.value = result.list.map((m) => toReactiveMessage(m) as ChatMessageEx)
        // 窗口前还有更早的一页才算可回溯；around 不改 earliestPage 语义，按 total 估
        earliestPage = Math.max(1, Math.ceil(result.total / MSG_PAGE_SIZE))
        hasEarlier.value = earliestPage > 1
        return
      }
      // page=-1 = 最后一页（mock 按此语义实现；真实后端可按 around/offset 等价实现）
      const result = await conversationsApi.listMessages(conversationId, {
        page: -1,
        size: MSG_PAGE_SIZE
      })
      messages.value = result.list.map((m) => toReactiveMessage(m) as ChatMessageEx)
      earliestPage = Math.max(1, Math.ceil(result.total / MSG_PAGE_SIZE))
      hasEarlier.value = earliestPage > 1
    } catch (error) {
      loadError.value = errText(error, '加载消息失败')
    } finally {
      loadingHistory.value = false
    }
  }

  /** 加载更早一页，接在消息列表前面（长会话「加载更多」） */
  const loadEarlier = async () => {
    if (loadingEarlier.value || !hasEarlier.value || !activeConvId) return
    loadingEarlier.value = true
    try {
      earliestPage -= 1
      const result = await conversationsApi.listMessages(activeConvId, {
        page: earliestPage,
        size: MSG_PAGE_SIZE
      })
      const seen = new Set(messages.value.map((m) => m.id))
      const older = result.list
        .filter((m) => !seen.has(m.id))
        .map((m) => toReactiveMessage(m) as ChatMessageEx)
      messages.value = [...older, ...messages.value]
      hasEarlier.value = earliestPage > 1
    } catch (error) {
      earliestPage += 1
      loadError.value = errText(error, '加载更早消息失败')
    } finally {
      loadingEarlier.value = false
    }
  }

  /**
   * 主动停止。sse 保证任何终止路径都恰好一次 onDone（含「已停止」），
   * 这里先立刻收口呈现（转圈停掉、状态改掉），onDone 再以终态覆盖一遍 —— 两处一致。
   */
  const stop = () => {
    if (!handle) return
    handle.abort()
    handle = null
    generating.value = false
    for (const m of messages.value) {
      if (m.status === 'GENERATING') {
        m.status = 'STOPPED'
      }
    }
  }

  interface StartStreamInput {
    conversationId: string
    question: string
    assistant: ChatMessage
    attachmentIds?: string[]
    /** 重新生成：覆盖原答案；「继续生成」不传，保留已生成内容并追加 */
    overwrite?: boolean
    /** 生成模式：显式告诉后端这是新一轮 / 覆盖重生成 / 续写 */
    mode?: ChatMode
  }

  const startStream = (input: StartStreamInput) => {
    const { conversationId, question, attachmentIds, overwrite, mode } = input
    // 始终写响应式代理：外部传入的可能是原始对象
    const assistant = resolveMsg(input.assistant)
    generating.value = true
    streamError.value = null

    if (overwrite) {
      assistant.content = ''
      assistant.refs = []
      assistant.suggestions = []
      assistant.feedback = null
      assistant.failReason = null
      assistant.failHint = null
    }
    assistant.status = 'GENERATING'

    handle = streamApi.chatStream({
      appId: options.appId,
      conversationId: options.credentialToken ? undefined : conversationId,
      question,
      attachmentIds,
      thinking: options.thinking?.value ?? false,
      credentialToken: options.credentialToken,
      // 重生成/继续生成把目标消息 id 一并带上：服务端据此覆盖/续写，而不是新增一轮
      mode,
      targetMessageId: mode && mode !== 'NEW' ? assistant.id : undefined,

      onMeta: (meta) => {
        assistant.id = meta.messageId || assistant.id
      },
      onRefs: (refs) => {
        assistant.refs = refs.map(toRefEx)
      },
      onDelta: (chunk) => {
        assistant.content += chunk
      },
      onArtifact: (artifact) => {
        if (artifact.type === 'suggestions') {
          assistant.suggestions = artifact.items
        }
      },
      onError: (reason, hint) => {
        // 失败必须说话：原因 + 下一步
        streamError.value = { reason, hint }
        assistant.status = 'FAILED'
        assistant.failReason = reason
        assistant.failHint = hint ?? null
      },
      onDone: (result) => {
        assistant.status = result.status as MessageStatus
        assistant.tokens = result.tokens.total
        assistant.durationMs = result.durationMs
        generating.value = false
        handle = null
        if (result.status === 'FAILED') {
          if (!assistant.failReason) {
            assistant.failReason = '生成失败'
            assistant.failHint = '请稍后重试；若持续失败请联系管理员'
          }
          streamError.value = {
            reason: assistant.failReason,
            hint: assistant.failHint ?? undefined
          }
          return
        }
        // 空回答：done=已生成但正文为空 —— 给可读提示，不留白、不照出操作条
        if (result.status === 'GENERATED' && !assistant.content.trim()) {
          assistant.failReason = '本次没有返回内容'
          assistant.failHint = '可以点「重新生成」再试一次，或换个问法'
        }
      }
    })
  }

  /** 发送一条问题。conversationId 为空时由调用方先建会话 */
  const send = async (params: {
    conversationId: string
    question: string
    attachments?: AttachmentDraft[]
  }) => {
    const { conversationId, question, attachments = [] } = params
    const text = question.trim()
    if (!text || generating.value) return

    // 只带上传就绪的真实 id；失败/上传中的不允许混进请求
    const ready = attachments.filter((a) => a.status === 'ready' && a.id)
    const userMsg = toReactiveMessage({
      id: `u_${Date.now().toString(36)}`,
      conversationId,
      role: 'USER',
      content: text,
      status: 'GENERATED',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: new Date().toISOString(),
      attachments: ready.map(
        (a): ChatAttachment => ({
          id: a.id,
          name: a.name,
          size: a.size,
          mime: a.mime
        })
      )
    } as ChatMessageEx)
    const assistantMsg = toReactiveMessage({
      id: `a_${Date.now().toString(36)}`,
      conversationId,
      role: 'ASSISTANT',
      content: '',
      status: 'GENERATING',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: new Date().toISOString()
    })
    messages.value.push(userMsg, assistantMsg)

    startStream({
      conversationId,
      question: text,
      assistant: assistantMsg,
      attachmentIds: ready.map((a) => a.id)
    })
  }

  /** 重新生成：用同一个问题覆盖原答案（带 messageId，服务端就地替换，不新增消息对） */
  const regenerate = (input: ChatMessage) => {
    if (generating.value) return
    const assistant = resolveMsg(input)
    const idx = messages.value.findIndex((m) => m.id === assistant.id)
    if (idx < 0) return
    let userMsg: ChatMessageEx | null = null
    for (let i = idx - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'USER') {
        userMsg = messages.value[i]!
        break
      }
    }
    if (!userMsg) return

    startStream({
      conversationId: assistant.conversationId,
      question: userMsg.content,
      assistant,
      attachmentIds: (userMsg.attachments ?? []).map((a) => a.id),
      overwrite: true,
      mode: 'REGENERATE'
    })
  }

  /**
   * 停止后的「继续」：保留已生成内容，在其后追加。
   * 带 `mode:'continue'` + 目标 messageId —— **不伪造问句**，否则历史里会多出一条重复问题。
   */
  const continueGenerate = (input: ChatMessage) => {
    if (generating.value) return
    const assistant = resolveMsg(input)
    const idx = messages.value.findIndex((m) => m.id === assistant.id)
    let userMsg: ChatMessageEx | null = null
    for (let i = idx - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'USER') {
        userMsg = messages.value[i]!
        break
      }
    }
    if (!userMsg) return

    startStream({
      conversationId: assistant.conversationId,
      // 原问题原样发 —— 伪造后缀会让服务端把「继续」当成新一轮，历史里多一条重复问句
      question: userMsg.content,
      assistant,
      attachmentIds: (userMsg.attachments ?? []).map((a) => a.id),
      mode: 'CONTINUE'
    })
  }

  /** 赞踩互斥且可撤销 */
  const toggleFeedback = async (input: ChatMessage, type: 'UP' | 'DOWN') => {
    const msg = resolveMsg(input)
    const next = msg.feedback === type ? null : type
    const updated = await conversationsApi.submitMessageFeedback(msg.id, next)
    msg.feedback = updated.feedback
  }

  return {
    messages,
    loadingHistory,
    loadingEarlier,
    loadError,
    generating,
    streamError,
    hasEarlier,
    lastAssistant,
    loadHistory,
    loadEarlier,
    send,
    stop,
    regenerate,
    continueGenerate,
    toggleFeedback
  }
}
