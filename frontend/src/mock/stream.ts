import type {
  ChatStreamOptions,
  SourceRef,
  StreamHandle
} from '@/api/stream-types'
import { cannedAnswer } from './fixtures/canned'
import { userFromToken } from './fixtures/identity'
import { getToken } from '@/lib/token'
import { appendTurn } from './chat-store'

/**
 * 流式契约的 mock 实现。
 *
 * 严格按 api/stream-types.ts 的事件顺序发射：
 *   meta → refs → delta(多次) → artifact → done
 * 目的是让界面上的流式、引用、停止、重生成都是**真的事件驱动**，
 * 而不是假动画。换成真实 SSE 只取决于 config/env.ts 的 USE_MOCK。
 *
 * 落库（#4）：三种终态（已生成 / 已停止 / 失败）都在**收尾函数里同步落**，
 * 见 `finish`。用户点「停止」→ `abort()` → `finish('已停止')` → 已生成的半截
 * 内容一并写入存储，所以刷新页面不会丢。落库先于 `onDone` 回调：UI 收到
 * 终态时数据已经在库里，不存在「界面已收尾、落库还在路上」的窗口。
 *
 * 失败路径按问题文本触发（与 ingestion 的「扫描件命名约定」同一手法）：
 * 问题含「模拟失败」时走 onError + done(失败)，同样落库。
 */

/** 逐字推送的字符数与间隔，模拟真实模型的吐字节奏 */
const CHAR_STEP_MIN = 2
const CHAR_STEP_MAX = 4

/** 问题里带这个词就模拟生成失败 —— 让「失败」这条终态可被真的走到 */
const FAIL_TRIGGER = '模拟失败'

export function chatStreamMock(options: ChatStreamOptions): StreamHandle {
  const startedAt = Date.now()
  const messageId = `msg_${Date.now().toString(36)}`
  const timers = new Set<ReturnType<typeof setTimeout>>()
  let aborted = false
  let closed = false

  /** 本轮已推给调用方的文本 —— 中断时要落的就是这个半截，不是整篇 canned */
  let emittedText = ''
  let emittedRefs: SourceRef[] = []
  let emittedSuggestions: string[] = []
  let failReason: string | null = null
  let failHint: string | null = null

  const later = (fn: () => void, ms: number) => {
    const timer = setTimeout(() => {
      timers.delete(timer)
      if (!aborted && !closed) fn()
    }, ms)
    timers.add(timer)
  }

  const cleanup = () => {
    timers.forEach(clearTimeout)
    timers.clear()
  }

  /** 当前登录人；拿不到人时传空串，公开流由 appendTurn 按 credentialToken 分支 */
  const currentOwnerId = (): string => userFromToken(getToken())?.id ?? ''

  /**
   * 统一收尾：**先落库，再通知 UI**。
   * 三种终态都进这里 —— 已生成（跑完）/ 已停止（abort）/ 失败（触发失败条件）。
   */
  const finish = (
    status: '已生成' | '已停止' | '失败',
    tokens: { prompt: number; completion: number; total: number }
  ) => {
    if (closed) return
    closed = true
    cleanup()

    // 落库同步完成：localStorage.setItem 无异步间隙，停止后立刻刷新也不丢
    try {
      appendTurn({
        credentialToken: options.credentialToken,
        conversationId: options.conversationId,
        appId: options.appId ?? null,
        ownerId: currentOwnerId(),
        question: options.question,
        answer: emittedText,
        status,
        refs: emittedRefs,
        suggestions: emittedSuggestions,
        tokens: tokens.total,
        durationMs: Date.now() - startedAt,
        failReason,
        failHint,
        attachmentIds: options.attachmentIds,
        messageId
      })
    } catch {
      /* 落库失败不该把流式收尾也拖垮 —— UI 仍要走完 done */
    }

    options.onDone?.({ status, messageId, tokens, durationMs: Date.now() - startedAt })
  }

  const failNow = (reason: string, hint?: string) => {
    failReason = reason
    failHint = hint ?? null
    // error 之后仍要走 done 收尾（契约 stream-types.ts 顶部注释）
    options.onError?.(reason, hint)
    finish('失败', { prompt: 0, completion: 0, total: 0 })
  }

  // 通用问答不带知识库，因此不带引用 —— 与页面上的「不显示引用来源区块」一致
  const payload = options.appId ? cannedAnswer.withRefs : cannedAnswer.withoutRefs
  const thinkingMs = options.thinking ? 1200 : 0
  const shouldFail = options.question.includes(FAIL_TRIGGER)

  // 1) meta：连接建立即返回。这是「生成中可停止」的前提
  later(() => {
    options.onMeta?.({
      messageId,
      traceId: `trace_${messageId}`,
      model: options.thinking ? 'qwen3:8b' : 'qwen3:4b-instruct'
    })
  }, 60)

  // 2) refs：检索先于生成，引用先到，用户可边看答案边点引用
  later(() => {
    if (shouldFail) return
    if (payload.refs.length) {
      emittedRefs = payload.refs as SourceRef[]
      options.onRefs?.(emittedRefs)
    }
  }, 260 + thinkingMs)

  // 3) delta：逐字推送
  later(() => {
    if (shouldFail) {
      failNow(
        '对话模型响应超时（mock 失败路径）',
        '请稍后重试；若持续失败请联系管理员'
      )
      return
    }
    let cursor = 0
    const tick = () => {
      if (aborted || closed) return
      const step = CHAR_STEP_MIN + Math.floor(Math.random() * (CHAR_STEP_MAX - CHAR_STEP_MIN + 1))
      const chunk = payload.text.slice(cursor, cursor + step)
      cursor += step

      if (chunk) {
        emittedText += chunk
        options.onDelta?.(chunk)
      }

      if (cursor >= payload.text.length) {
        if (payload.suggestions.length) {
          emittedSuggestions = payload.suggestions
          options.onArtifact?.({ type: 'suggestions', items: payload.suggestions })
        }
        finish('已生成', {
          prompt: 812,
          completion: Math.round(payload.text.length / 2.2),
          total: 812 + Math.round(payload.text.length / 2.2)
        })
        return
      }
      later(tick, 16 + Math.floor(Math.random() * 18))
    }
    tick()
  }, 520 + thinkingMs)

  return {
    messageId,
    abort() {
      if (aborted || closed) return
      aborted = true
      // 已生成的半截照样落库 —— 这是「用户点停止后数据不能丢」的兑现点
      const completion = Math.round(Math.max(emittedText.length, 1) / 2.2)
      finish('已停止', { prompt: 812, completion, total: 812 + completion })
    }
  }
}
