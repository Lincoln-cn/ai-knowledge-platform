/**
 * 流式问答契约 —— 全平台**唯一**一套。
 *
 * 原项目有 4 个来源（api/chat.js、api/chat/chat.js、workFlowChat 内手写解析、
 * axios.js 的 postStream），各自解析、各自管中断。这里固定为一套事件契约：
 *
 *   meta     连接建立，带 messageId / traceId / model
 *            —— 先有 meta 才谈得上「生成中停止」
 *   refs     检索先于生成，引用先到，可边看答案边点引用
 *   delta    逐字增量
 *   artifact 结构化产物（当前为 suggestions）
 *   done     终态收尾：已生成 / 已停止 / 失败
 *   error    失败原因与下一步；之后仍会走 done 收尾
 *
 * 传输层保证（lib/sse.ts）：无论正常收尾、用户 abort、半途断流还是闲置超时，
 * onDone **恰好回调一次**；abort 场景不触发 onError，且已生成内容一律保留。
 *
 * 后端用 SSE(text/event-stream) 实现；前端换传输只需替换 lib 层实现，本文件不变。
 */

export interface StreamMeta {
  messageId: string
  traceId: string
  model: string
}

export interface SourceRef {
  /** 正文里 [N] 的编号，从 1 连续递增 */
  index: number
  title: string
  docName: string
  kbName: string
  score: number
  rank?: number
  full?: string
  /** 分段 id —— 「跳转到分段管理」深链用（`/knowledge?segment=`） */
  segmentId?: string
  /** 所属文档 id —— 深链用（`/knowledge?doc=`） */
  documentId?: string
  /** 所属知识库 id —— 深链用（`/knowledge?kb=`） */
  kbId?: string
}

export type Artifact =
  | { type: 'suggestions'; items: string[] }
  | { type: 'chart'; spec: unknown }
  | { type: 'mindmap'; spec: unknown }

export type StreamStatus = '已生成' | '已停止' | '失败'

export interface StreamDone {
  status: StreamStatus
  messageId: string
  tokens: { prompt: number; completion: number; total: number }
  durationMs: number
}

/**
 * 生成模式。缺省 `new` = 正常新一轮。
 *  - `regenerate` 覆盖 `targetMessageId` 对应的原答案（不新增消息对）
 *  - `continue`   接着 `targetMessageId` 已生成的内容续写（不伪造新问句）
 */
export type ChatMode = 'new' | 'regenerate' | 'continue'

export interface ChatStreamOptions {
  /** 应用 id；不传为通用问答 */
  appId?: string
  conversationId?: string
  question: string
  attachmentIds?: string[]
  /** 是否允许模型思考（由应用配置与模型能力共同决定） */
  thinking?: boolean
  /** 免登录场景的访问凭证，替代 Authorization 头 */
  credentialToken?: string
  /** 生成模式；不传按 `new` 处理 */
  mode?: ChatMode
  /** 重生成 / 继续生成的目标助手消息 id */
  targetMessageId?: string

  onMeta?: (meta: StreamMeta) => void
  onRefs?: (refs: SourceRef[]) => void
  onDelta?: (chunk: string) => void
  onArtifact?: (artifact: Artifact) => void
  onDone?: (result: StreamDone) => void
  onError?: (reason: string, hint?: string) => void
}

export interface StreamHandle {
  readonly messageId: string
  /** 主动停止。停止后已生成内容必须保留，不做清屏 */
  abort: () => void
}
