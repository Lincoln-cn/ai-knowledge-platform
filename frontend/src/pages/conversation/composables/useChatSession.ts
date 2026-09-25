import { computed, reactive, ref, type Ref } from 'vue'
import { conversationsApi, streamApi } from '@/api'
import type { ChatMessage, Conversation, MessageStatus } from '@/api/conversations'
import type { SourceRef, StreamHandle } from '@/api/stream-types'
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

export type SourceRefEx = SourceRef & {
  segmentId?: string
  documentId?: string
  kbId?: string
}

export interface AttachmentDraft {
  id: string
  name: string
  size: number
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
  const extra = ref as SourceRefEx
  return {
    ...ref,
    segmentId: extra.segmentId,
    documentId: extra.documentId,
    kbId: extra.kbId
  }
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

export function useHistoryPanel() {
  const list: Ref<ConversationEx[]> = ref([])
  const loading = ref(false)
  const loadError = ref<{ reason: string; hint?: string } | null>(null)

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

  const load = async (appId?: string) => {
    loading.value = true
    loadError.value = null
    try {
      const result = await conversationsApi.listConversations({
        appId,
        page: 1,
        size: 50
      })
      list.value = result.list.map((c) => {
        const ex = c as ConversationEx
        if (!ex.app && ex.appId) ex.app = readAppBrief((c as ConversationEx).app) ?? undefined
        return ex
      })
    } catch (error) {
      loadError.value = errText(error, '加载历史会话失败')
    } finally {
      loading.value = false
    }
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

  return { list, groups, loading, loadError, load, remove, rename, prepend, touch }
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
  const messages: Ref<ChatMessage[]> = ref([])
  const loadingHistory = ref(false)
  const loadError = ref<{ reason: string; hint?: string } | null>(null)
  const generating = ref(false)
  /** 生成失败时的可读原因与下一步 —— 绝不静默失败 */
  const streamError = ref<{ reason: string; hint?: string } | null>(null)

  let handle: StreamHandle | null = null

  const lastAssistant = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'assistant') return messages.value[i]
    }
    return null
  })

  /** 统一从响应式数组取代理对象再改属性 —— 否则赞踩/流式回调不会触发视图 */
  const resolveMsg = (msg: ChatMessage): ChatMessage => {
    const found = messages.value.find((m) => m.id === msg.id)
    return found ?? msg
  }

  const loadHistory = async (conversationId: string) => {
    loadingHistory.value = true
    loadError.value = null
    messages.value = []
    try {
      if (options.credentialToken) {
        const list = await conversationsApi.listPublicMessages(options.credentialToken)
        messages.value = list.map(toReactiveMessage)
      } else {
        const result = await conversationsApi.listMessages(conversationId, { page: 1, size: 100 })
        messages.value = result.list.map(toReactiveMessage)
      }
    } catch (error) {
      loadError.value = errText(error, '加载消息失败')
    } finally {
      loadingHistory.value = false
    }
  }

  const stop = () => {
    if (!handle) return
    handle.abort()
    handle = null
    generating.value = false
  }

  interface StartStreamInput {
    conversationId: string
    question: string
    assistant: ChatMessage
    attachmentIds?: string[]
    /** 重新生成：覆盖原答案；「继续生成」不传，保留已生成内容并追加 */
    overwrite?: boolean
  }

  const startStream = (input: StartStreamInput) => {
    const { conversationId, question, attachmentIds, overwrite } = input
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
    assistant.status = '生成中'

    handle = streamApi.chatStream({
      appId: options.appId,
      conversationId: options.credentialToken ? undefined : conversationId,
      question,
      attachmentIds,
      thinking: options.thinking?.value ?? false,
      credentialToken: options.credentialToken,

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
        assistant.status = '失败'
        assistant.failReason = reason
        assistant.failHint = hint ?? null
      },
      onDone: (result) => {
        assistant.status = result.status as MessageStatus
        assistant.tokens = result.tokens.total
        assistant.durationMs = result.durationMs
        generating.value = false
        handle = null
        if (result.status === '失败' && !assistant.failReason) {
          assistant.failReason = '生成失败'
          assistant.failHint = '请稍后重试；若持续失败请联系管理员'
          streamError.value = { reason: assistant.failReason, hint: assistant.failHint }
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

    const userMsg = toReactiveMessage({
      id: `u_${Date.now().toString(36)}`,
      conversationId,
      role: 'user',
      content: text,
      status: '已生成',
      refs: [],
      suggestions: [],
      feedback: null,
      tokens: 0,
      durationMs: 0,
      failReason: null,
      failHint: null,
      createdAt: new Date().toISOString()
    })
    const assistantMsg = toReactiveMessage({
      id: `a_${Date.now().toString(36)}`,
      conversationId,
      role: 'assistant',
      content: '',
      status: '生成中',
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
      attachmentIds: attachments.map((a) => a.id)
    })
  }

  /** 重新生成：用同一个问题覆盖原答案 */
  const regenerate = (input: ChatMessage) => {
    if (generating.value) return
    const assistant = resolveMsg(input)
    const idx = messages.value.findIndex((m) => m.id === assistant.id)
    if (idx < 0) return
    let userMsg: ChatMessage | null = null
    for (let i = idx - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'user') {
        userMsg = messages.value[i]!
        break
      }
    }
    if (!userMsg) return

    startStream({
      conversationId: assistant.conversationId,
      question: userMsg.content,
      assistant,
      overwrite: true
    })
  }

  /**
   * 停止后的「继续」：保留已生成内容，在其后追加。
   * 契约暂无断点续传接口，这里以「请接着上文继续」发起新一轮流式并追加写入。
   */
  const continueGenerate = (input: ChatMessage) => {
    if (generating.value) return
    const assistant = resolveMsg(input)
    const idx = messages.value.findIndex((m) => m.id === assistant.id)
    let userMsg: ChatMessage | null = null
    for (let i = idx - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'user') {
        userMsg = messages.value[i]!
        break
      }
    }
    if (!userMsg) return

    const question = `${userMsg.content}\n\n（请接着上文已输出的内容继续，不要重复已写部分）`
    startStream({
      conversationId: assistant.conversationId,
      question,
      assistant
    })
  }

  /** 赞踩互斥且可撤销 */
  const toggleFeedback = async (input: ChatMessage, type: 'up' | 'down') => {
    const msg = resolveMsg(input)
    const next = msg.feedback === type ? null : type
    const updated = await conversationsApi.submitMessageFeedback(msg.id, next)
    msg.feedback = updated.feedback
  }

  return {
    messages,
    loadingHistory,
    loadError,
    generating,
    streamError,
    lastAssistant,
    loadHistory,
    send,
    stop,
    regenerate,
    continueGenerate,
    toggleFeedback
  }
}
