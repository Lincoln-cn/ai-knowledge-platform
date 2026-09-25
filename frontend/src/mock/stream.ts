import type {
  ChatStreamOptions,
  SourceRef,
  StreamHandle
} from '@/api/stream-types'
import { cannedAnswer } from './fixtures/canned'

/**
 * 流式契约的 mock 实现。
 *
 * 严格按 api/stream-types.ts 的事件顺序发射：
 *   meta → refs → delta(多次) → artifact → done
 * 目的是让界面上的流式、引用、停止、重生成都是**真的事件驱动**，
 * 而不是假动画。换成真实 SSE 只取决于 config/env.ts 的 USE_MOCK。
 */

/** 逐字推送的字符数与间隔，模拟真实模型的吐字节奏 */
const CHAR_STEP_MIN = 2
const CHAR_STEP_MAX = 4

export function chatStreamMock(options: ChatStreamOptions): StreamHandle {
  const startedAt = Date.now()
  const messageId = `msg_${Date.now().toString(36)}`
  const timers = new Set<ReturnType<typeof setTimeout>>()
  let aborted = false
  let closed = false

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

  const finish = (status: '已生成' | '已停止') => {
    if (closed) return
    closed = true
    cleanup()
    const completion = Math.round(payload.text.length / 2.2)
    options.onDone?.({
      status,
      messageId,
      tokens: { prompt: 812, completion, total: 812 + completion },
      durationMs: Date.now() - startedAt
    })
  }

  // 通用问答不带知识库，因此不带引用 —— 与页面上的「不显示引用来源区块」一致
  const payload = options.appId ? cannedAnswer.withRefs : cannedAnswer.withoutRefs
  const thinkingMs = options.thinking ? 1200 : 0

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
    if (payload.refs.length) options.onRefs?.(payload.refs as SourceRef[])
  }, 260 + thinkingMs)

  // 3) delta：逐字推送
  later(() => {
    let cursor = 0
    const tick = () => {
      if (aborted || closed) return
      const step = CHAR_STEP_MIN + Math.floor(Math.random() * (CHAR_STEP_MAX - CHAR_STEP_MIN + 1))
      const chunk = payload.text.slice(cursor, cursor + step)
      cursor += step

      if (chunk) options.onDelta?.(chunk)

      if (cursor >= payload.text.length) {
        if (payload.suggestions.length) {
          options.onArtifact?.({ type: 'suggestions', items: payload.suggestions })
        }
        finish('已生成')
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
      finish('已停止')
    }
  }
}
