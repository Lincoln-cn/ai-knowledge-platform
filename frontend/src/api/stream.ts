import { USE_MOCK } from '@/config/env'
import { chatStreamViaSse } from '@/lib/sse'
import { chatStreamMock } from '@/mock/stream'
import type { ChatStreamOptions, StreamHandle } from './stream-types'

export * from './stream-types'

/**
 * 全平台唯一的问答流入口。
 *
 * 页面只 import 这里，永远不直接碰传输实现。
 * 换真实后端只取决于 USE_MOCK，调用方与事件契约都不动。
 */
export function chatStream(options: ChatStreamOptions): StreamHandle {
  return USE_MOCK ? chatStreamMock(options) : chatStreamViaSse(options)
}
