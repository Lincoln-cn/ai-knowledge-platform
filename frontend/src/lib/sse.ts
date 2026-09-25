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

function emit(frame: Frame, options: ChatStreamOptions, setMessageId: (id: string) => void) {
  if (!frame.data) return
  const payload = JSON.parse(frame.data) as Record<string, unknown>

  switch (frame.event) {
    case 'meta': {
      const meta = payload as unknown as StreamMeta
      setMessageId(meta.messageId)
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
      options.onDone?.(payload as unknown as StreamDone)
      break
    case 'error':
      options.onError?.(String(payload.reason ?? '生成失败'), payload.hint as string | undefined)
      break
    default:
      break
  }
}

/** 真实后端的流式实现：fetch + ReadableStream，自带中断 */
export function chatStreamViaSse(options: ChatStreamOptions): StreamHandle {
  const controller = new AbortController()
  let messageId = ''

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream'
  }
  // 免登录场景用访问凭证，内部场景用令牌 —— 两条链路共用同一个流式实现
  if (options.credentialToken) headers['X-Access-Credential'] = options.credentialToken
  else headers.Authorization = `Bearer ${getToken()}`

  const run = async () => {
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
      options.onError?.(`服务端返回 ${response.status}`, '请稍后重试；若持续失败请联系管理员')
      options.onDone?.({
        status: '失败',
        messageId,
        tokens: { prompt: 0, completion: 0, total: 0 },
        durationMs: 0
      })
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
      for (const frame of frames) emit(frame, options, (id) => { messageId = id })
    }
  }

  run().catch((error: unknown) => {
    if (controller.signal.aborted) return // 主动停止不算错误
    const reason = error instanceof Error ? error.message : '连接中断'
    options.onError?.(reason, '网络或服务异常，可重试')
    options.onDone?.({
      status: '失败',
      messageId,
      tokens: { prompt: 0, completion: 0, total: 0 },
      durationMs: 0
    })
  })

  return {
    get messageId() {
      return messageId
    },
    abort: () => controller.abort()
  }
}
