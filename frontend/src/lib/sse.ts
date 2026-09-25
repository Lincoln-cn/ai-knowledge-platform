import { API_PREFIX } from '@/config/env'
import { getToken } from '@/lib/token'
import type {
  Artifact,
  ChatStreamOptions,
  SourceRef,
  StreamDone,
  StreamHandle,
  StreamMeta
} from '@/api/stream-types'

/** 一帧 SSE：event + data */
interface Frame {
  event: string
  data: string
}

/**
 * 把 SSE 文本缓冲切成完整帧，返回剩余未完整部分。
 * 单帧可能被网络切在两半，必须保留尾部等下一次 —— 这是手写流解析最常见的错。
 */
export function splitFrames(buffer: string): { frames: Frame[]; rest: string } {
  const frames: Frame[] = []
  const blocks = buffer.split('\n\n')
  const rest = blocks.pop() ?? ''

  for (const block of blocks) {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue // 心跳
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (dataLines.length) frames.push({ event, data: dataLines.join('\n') })
  }

  return { frames, rest }
}

function emit(
  frame: Frame,
  options: ChatStreamOptions,
  hooks: {
    setMessageId: (id: string) => void
    /** 服务端 done 帧：原样透传，由调用方负责「恰好一次」闸门 */
    onServerDone: (result: StreamDone) => void
    /** 服务端 error 帧：只记账，不在此收尾 —— 契约规定之后仍会走 done */
    onServerError: () => void
  }
) {
  if (!frame.data) return
  const payload = JSON.parse(frame.data) as Record<string, unknown>

  switch (frame.event) {
    case 'meta': {
      const meta = payload as unknown as StreamMeta
      hooks.setMessageId(meta.messageId)
      options.onMeta?.(meta)
      break
    }
    case 'refs':
      options.onRefs?.(payload as unknown as SourceRef[])
      break
    case 'delta':
      options.onDelta?.(String(payload.text ?? ''))
      break
    case 'artifact':
      options.onArtifact?.(payload as unknown as Artifact)
      break
    case 'done':
      hooks.onServerDone(payload as unknown as StreamDone)
      break
    case 'error':
      hooks.onServerError()
      options.onError?.(String(payload.reason ?? '生成失败'), payload.hint as string | undefined)
      break
    default:
      break
  }
}

/** 连接闲置多久视为吊死（毫秒）。超过即按「中途断掉」收尾并掐断底层连接 */
const IDLE_TIMEOUT_MS = 60_000

/** 真实后端的流式实现：fetch + ReadableStream，自带中断 */
export function chatStreamViaSse(options: ChatStreamOptions): StreamHandle {
  const controller = new AbortController()
  let messageId = ''
  /** 终态闸门：无论走哪条路径，onDone 恰好发一次 */
  let settled = false
  /** 是否已经把失败说出口（服务端 error 帧或本地收尾）—— 避免半途断流再补一条重复 error */
  let errorEmitted = false

  let idleTimer: ReturnType<typeof setTimeout> | null = null

  const clearIdle = () => {
    if (idleTimer !== null) {
      clearTimeout(idleTimer)
      idleTimer = null
    }
  }

  /** 每收到一帧就重置。60 秒无帧 = 连接吊死 */
  const armIdle = () => {
    clearIdle()
    idleTimer = setTimeout(() => {
      if (settled) return
      // 闲置超时与「中途断掉」同口径收尾，并 abort 掉底层连接
      failTerminal('连接中断', '网络或服务异常，可重试')
      controller.abort()
    }, IDLE_TIMEOUT_MS)
  }

  /** 唯一的 onDone 出口。透传服务端 done 帧也走这里，保证恰好一次 */
  const finish = (result: StreamDone) => {
    if (settled) return
    settled = true
    clearIdle()
    options.onDone?.(result)
  }

  /** 本地合成的「失败」终态：可选补一条 error，再补契约要求的 done 收尾 */
  const failTerminal = (reason: string, hint: string) => {
    if (settled) return
    if (!errorEmitted) {
      errorEmitted = true
      options.onError?.(reason, hint)
    }
    finish({
      status: '失败',
      messageId,
      tokens: { prompt: 0, completion: 0, total: 0 },
      durationMs: 0
    })
  }

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream'
  }
  // 免登录场景用访问凭证，内部场景用令牌 —— 两条链路共用同一个流式实现
  if (options.credentialToken) headers['X-Access-Credential'] = options.credentialToken
  else headers.Authorization = `Bearer ${getToken()}`

  const run = async () => {
    armIdle()
    const response = await fetch(`${API_PREFIX}/conversations/chat`, {
      method: 'POST',
      headers,
      signal: controller.signal,
      body: JSON.stringify({
        appId: options.appId,
        conversationId: options.conversationId,
        question: options.question,
        attachmentIds: options.attachmentIds ?? [],
        thinking: options.thinking ?? false
      })
    })

    if (!response.ok || !response.body) {
      failTerminal(`服务端返回 ${response.status}`, '请稍后重试；若持续失败请联系管理员')
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const { frames, rest } = splitFrames(buffer)
      buffer = rest
      // 闲置超时按「帧」算：60 秒无一帧 = 吊死。心跳注释行不产生帧，不重置计时
      if (frames.length) armIdle()
      for (const frame of frames) {
        emit(frame, options, {
          setMessageId: (id) => {
            messageId = id
          },
          onServerDone: (result) => finish(result),
          onServerError: () => {
            errorEmitted = true
          }
        })
      }
    }

    // reader 走到尽头却没等到 done 帧 = 半途断流：补 error + 失败终态
    failTerminal('连接中断', '网络或服务异常，可重试')
  }

  run().catch((error: unknown) => {
    if (settled) return
    if (controller.signal.aborted) {
      // 主动停止：不算错误，但必须给出「已停止」终态，否则界面永远停在生成中
      finish({
        status: '已停止',
        messageId,
        tokens: { prompt: 0, completion: 0, total: 0 },
        durationMs: 0
      })
      return
    }
    const reason = error instanceof Error ? error.message : '连接中断'
    failTerminal(reason, '网络或服务异常，可重试')
  })

  return {
    get messageId() {
      return messageId
    },
    abort: () => controller.abort()
  }
}
