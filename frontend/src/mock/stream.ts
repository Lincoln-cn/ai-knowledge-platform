import type {
  ChatStreamOptions,
  SourceRef,
  StreamHandle
} from '@/api/stream-types'
import { cannedAnswer } from './fixtures/canned'
import { userFromToken } from './fixtures/identity'
import { getToken } from '@/lib/token'
import { appendTurn, findMessage } from './chat-store'
import { enrichRef } from './fixtures/conversations'

/**
 * 流式契约的 mock 实现。
 *
 * 严格按 api/stream-types.ts 的事件顺序发射：
 *   meta → refs → delta(多次) → artifact → done
 * 心跳（`: keep-alive` 注释帧的 mock 对应物）在生成间隙按固定节拍发，
 * 保证契约①「心跳帧」这一条在 mock 下也被真的走到。
 * 目的是让界面上的流式、引用、停止、重生成都是**真的事件驱动**，
 * 而不是假动画。换成真实 SSE 只取决于 config/env.ts 的 USE_MOCK。
 *
 * 落库（#4）：三种终态（已生成 / 已停止 / 失败）都在**收尾函数里同步落**，
 * 见 `finish`。用户点「停止」→ `abort()` → `finish('STOPPED')` → 已生成的半截
 * 内容一并写入存储，所以刷新页面不会丢。落库先于 `onDone` 回调：UI 收到
 * 终态时数据已经在库里，不存在「界面已收尾、落库还在路上」的窗口。
 *
 * 失败路径按问题文本触发（与 ingestion 的「扫描件命名约定」同一手法）：
 * 问题含「模拟失败」时走 onError + done(失败)，同样落库。
 * 问题含「模拟空回答」时走 done(已生成) 但正文为空，用来验证空回答提示。
 */

/** 逐字推送的字符数与间隔，模拟真实模型的吐字节奏 */
const CHAR_STEP_MIN = 2
const CHAR_STEP_MAX = 4

/**
 * mock 心跳节拍。生产是 `HEARTBEAT_INTERVAL_MS`（20s）—— mock 流几秒就结束，
 * 用同一个 20s 永远观测不到心跳 ⇒ 这里按时间压缩到 400ms，
 * 保证契约①「心跳帧会被发出」在 mock 下**真的被执行**（可被 onHeartbeat 断言）。
 */
const MOCK_HEARTBEAT_MS = 400

/** 问题里带这个词就模拟生成失败 —— 让「失败」这条终态可被真的走到 */
const FAIL_TRIGGER = '模拟失败'

/** 问题里带这个词就模拟「已生成但正文为空」 */
const EMPTY_TRIGGER = '模拟空回答'

/**
 * ★★ 正在生成的 mock 流：`messageId → 停止函数`。
 *
 * <h3>为什么 mock 也要有一张表（不是"多余的一层"）</h3>
 *
 * 契约里停止是一个**显式请求**（`conversations.ts` 的 `stopMessage`，
 * `POST /conversations/:id/messages/:messageId/stop`）。而 mock 的"服务端"就在浏览器里：
 * 没有这张表，那条请求在 `VITE_USE_MOCK=true` 下**没有对象可停**——
 * 调用方只会 await 到一次 404，于是"停止"在 mock 下变成一条没人接的请求
 * （而页面看起来一切正常，只是库里那条最终是 `FAILED`）。
 *
 * ★ 表里挂的是**同一个** `abort`：mock 的停止端点与页面上那个停止按钮走的是**一条**收尾路径
 * （`finish('STOPPED')` → 已生成内容落库），所以两种入口在 mock 下的读数必须一致。
 */
const inFlightStops = new Map<string, () => void>()

/**
 * 请求停止某一条正在生成的 mock 流（mock 版的停止端点用）。
 *
 * @param messageId 助手消息 id（mock 里在 `meta` 之前就已生成，与真实后端不同）
 * @return 是否真的停下了这一条（`false` = 没有这条流在跑 ⇒ 调用方按"已收尾"应答）
 */
export function requestMockStop(messageId: string): boolean {
  const stop = inFlightStops.get(messageId)
  if (!stop) return false
  stop()
  return true
}

export function chatStreamMock(options: ChatStreamOptions): StreamHandle {
  const startedAt = Date.now()
  const messageId = `msg_${Date.now().toString(36)}`
  const timers = new Set<ReturnType<typeof setTimeout>>()
  let aborted = false
  let closed = false
  /** 心跳计时器：与业务 timer 分开清，finish/abort 都要停 */
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null

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

  const stopHeartbeat = () => {
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  const cleanup = () => {
    timers.forEach(clearTimeout)
    timers.clear()
    stopHeartbeat()
  }

  /** 当前登录人；拿不到人时传空串，公开流由 appendTurn 按 credentialToken 分支 */
  const currentOwnerId = (): string => userFromToken(getToken())?.id ?? ''

  /**
   * 统一收尾：**先落库，再通知 UI**。
   * 三种终态都进这里 —— 已生成（跑完）/ 已停止（abort）/ 失败（触发失败条件）。
   */
  const finish = (
    status: 'GENERATED' | 'STOPPED' | 'FAILED',
    tokens: { prompt: number; completion: number; total: number }
  ) => {
    if (closed) return
    closed = true
    cleanup()
    // ★ 收尾即摘牌：留着它会让一次迟到的停止请求命中一个已经结束的流
    inFlightStops.delete(messageId)

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
        messageId,
        mode: options.mode,
        targetMessageId: options.targetMessageId
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
    finish('FAILED', { prompt: 0, completion: 0, total: 0 })
  }

  // 通用问答不带知识库，因此不带引用 —— 与页面上的「不显示引用来源区块」一致
  // ★ 问句带了会话临时附件 ⇒ 引用里会出现「没有知识库行」的那一种（会话域），
  //   走独立语料，让「来源：」这一支在 mock 下可被看见、可被断言。
  const payload = options.attachmentIds?.length
    ? cannedAnswer.withAttachmentRef
    : options.appId
      ? cannedAnswer.withRefs
      : cannedAnswer.withoutRefs
  const thinkingMs = options.thinking ? 1200 : 0
  const shouldFail = options.question.includes(FAIL_TRIGGER)
  const shouldEmpty = options.question.includes(EMPTY_TRIGGER)

  /**
   * 「继续生成」只推还没写出去的尾巴，不整篇重放。
   * 先按 targetMessageId 找到已生成的部分，再从其长度处切开 canned 文本。
   */
  const resumeFrom = (() => {
    if (options.mode !== 'CONTINUE' || !options.targetMessageId) return 0
    const prev = findMessage(options.targetMessageId)
    return prev ? prev.content.length : 0
  })()

  // 1) meta：连接建立即返回。这是「生成中可停止」的前提
  later(() => {
    options.onMeta?.({
      messageId,
      traceId: `trace_${messageId}`,
      model: options.thinking ? 'qwen3:8b' : 'qwen3:4b-instruct'
    })
    // 契约①：连接建立后开始发心跳（`: keep-alive` 的 mock 对应物）。
    // 生产间隔 20s，mock 时间压缩到 400ms，保证短流也能被观测到。
    if (!closed && !aborted && heartbeatTimer === null) {
      heartbeatTimer = setInterval(() => {
        if (closed || aborted) return
        options.onHeartbeat?.()
      }, MOCK_HEARTBEAT_MS)
    }
  }, 60)

  // 2) refs：检索先于生成，引用先到，用户可边看答案边点引用
  later(() => {
    if (shouldFail || shouldEmpty) return
    if (payload.refs.length) {
      emittedRefs = payload.refs.map((r) => enrichRef(r as SourceRef))
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
    if (shouldEmpty) {
      // 空回答：不推任何 delta，直接以「已生成」收尾 —— 让空回答提示可被实测
      finish('GENERATED', { prompt: 512, completion: 0, total: 512 })
      return
    }
    // 继续生成：从已写到的位置接着吐；重生成/新一轮从头吐
    const text =
      options.mode === 'CONTINUE' && resumeFrom > 0
        ? payload.text.slice(resumeFrom)
        : payload.text
    let cursor = 0
    const tick = () => {
      if (aborted || closed) return
      const step = CHAR_STEP_MIN + Math.floor(Math.random() * (CHAR_STEP_MAX - CHAR_STEP_MIN + 1))
      const chunk = text.slice(cursor, cursor + step)
      cursor += step

      if (chunk) {
        emittedText += chunk
        options.onDelta?.(chunk)
      }

      if (cursor >= text.length) {
        if (payload.suggestions.length) {
          emittedSuggestions = payload.suggestions
          options.onArtifact?.({ type: 'suggestions', items: payload.suggestions })
        }
        finish('GENERATED', {
          prompt: 812,
          completion: Math.round(text.length / 2.2),
          total: 812 + Math.round(text.length / 2.2)
        })
        return
      }
      later(tick, 16 + Math.floor(Math.random() * 18))
    }
    tick()
  }, 520 + thinkingMs)

  /**
   * 收尾为 `STOPPED`（**已生成的半截照样落库** —— 这是「用户点停止后数据不能丢」的兑现点）。
   *
   * <p>★ 页面上的停止按钮（`abort()`）与 mock 的停止端点（`requestMockStop`）都走这一个函数：
   * 两个入口在 mock 下的终态与落库内容必须一模一样，否则"mock 下停止是对的、真栈上是错的"
   * 这类偏差不会有人发现。
   */
  const stopStream = () => {
    if (aborted || closed) return
    aborted = true
    const completion = Math.round(Math.max(emittedText.length, 1) / 2.2)
    finish('STOPPED', { prompt: 812, completion, total: 812 + completion })
  }

  // ★ 登记这一条正在跑的流（停止端点靠它找到"停谁"）；收尾（finish）会摘牌
  inFlightStops.set(messageId, stopStream)

  return {
    messageId,
    abort: stopStream
  }
}
