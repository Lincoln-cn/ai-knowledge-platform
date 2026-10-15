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
 *   done     终态收尾：GENERATED / STOPPED / FAILED
 *   error    失败原因与下一步；之后仍会走 done 收尾
 *
 * 传输层保证（lib/sse.ts）：无论正常收尾、用户 abort、半途断流还是闲置超时，
 * onDone **恰好回调一次**；abort 场景不触发 onError，且已生成内容一律保留。
 *
 * 后端用 SSE(text/event-stream) 实现；前端换传输只需替换 lib 层实现，本文件不变。
 */
/* ────────────────────────── 传输层硬保证（契约，不是注释装饰） ────────────────────────── *
 *
 * 下面四条是**后端必须实现**的形状与行为。没写进这里的保证，后端可以合理地不做；
 * 写进来了就必须做，前端按此验收。
 */

/**
 * ① 心跳帧 —— wire 形状。
 *
 * SSE **注释帧**：一整行以 `:` 开头，推荐载荷 `keep-alive`：
 *
 *     : keep-alive\n\n
 *
 * - 不是业务事件，没有 `event:`/`data:`；解析端必须**跳过**（不得当成 message）
 * - 用途：证明连接仍活着（反向代理 60s 空闲超时会掐断无字节的连接）
 * - 发送方在**无业务帧可发**的间隙（模型思考、检索）按 `HEARTBEAT_INTERVAL_MS` 发
 */
export type HeartbeatComment = `:${string}`

/** 心跳注释帧的行首（SSE 规范 comment line） */
export const HEARTBEAT_PREFIX = ':'

/** 心跳推荐载荷。解析端只认前缀 `:`，不按载荷匹配 */
export const HEARTBEAT_PAYLOAD = 'keep-alive'

/**
 * 心跳间隔（毫秒），后端在无业务帧时按此间隔发。
 * **契约约束**：`HEARTBEAT_INTERVAL_MS × 3 ≤ IDLE_TIMEOUT_MS`
 * —— 连续丢 2 个心跳仍不会被前端闲置超时误判，第 3 个间隔到来前必有新帧。
 */
export const HEARTBEAT_INTERVAL_MS = 20_000

/**
 * 前端闲置超时（毫秒）：连续此时长内**一帧都未收到**（业务帧或心跳都算）即判连接吊死。
 * 数值关系（契约，两边都要遵守）：
 *   心跳 20s × 3 = 60s = 闲置超时
 *   ⇒ 心跳必须**重置**闲置计时器（心跳是存活信号）；
 *   ⇒ 后端最长静默 20s，前端最长容忍 60s，留两个心跳的余量。
 */
export const IDLE_TIMEOUT_MS = 60_000

/**
 * ② 终态必达 —— `done` / `error` 的收口纪律。
 *
 * 任何终止路径（正常完成 / 用户停止 / 连接断开 / 超时）**恰好产出一次**终态：
 * `onDone` 有且仅有一次回调，`StreamDone.status ∈ StreamStatus`。
 *
 * 中断路径（连接断开、超时、半途断流）必须至少给出 `onError` 或 `onDone` 之一；
 * **不允许「无错且无终态」** —— 那会让界面永远停在「生成中」。
 *
 * 服务端 `error` 帧之后仍必须跟 `done` 帧收尾（error 不是终态，done 才是）。
 */
export type StreamTerminationPath =
  /** 正常写完 → done(GENERATED|FAILED) */
  | 'COMPLETE'
  /** 用户主动停止 → done(STOPPED) */
  | 'ABORT'
  /** 连接断开 / 半途断流 → error? + done(FAILED) */
  | 'DISCONNECT'
  /** 闲置超时 → error + done(FAILED) */
  | 'IDLE_TIMEOUT'

/**
 * ★★ `ABORT` 与 `DISCONNECT` 靠什么分开（`#119` 的收口，读这一段的都是要动这条链的人）。
 *
 * `ABORT` 是**用户意图**，它必须由一次**显式请求**表达，不能由"连接没了"推断 ——
 * 而 `abort()`（`lib/sse.ts` 掐 fetch）在服务端**只表现为"连接断了"**。
 * 所以停止的表达位是契约里的 `stopMessage`
 * （`POST /conversations/:conversationId/messages/:messageId/stop`，见 `conversations.ts`）。
 *
 * 调用方的顺序是**写死的**（`useChatSession` 的 `stop`）：先发停止请求、**再** `abort()`，
 * 且请求成功与否都要 `abort()`（只发不 abort ⇒ 流一直挂着；只 abort 不发 ⇒ 服务端分不出这两件事）。
 *
 * ★ 「还没收到 `meta` 就点停止」的降级**写在 `stopMessage` 的注释里**：那时拿不到 `messageId`，
 * 只能跳过请求直接 `abort()` —— 服务端按 `DISCONNECT` 收（`FAILED`）。这条降级是契约的一部分，
 * 不许在实现里悄悄改成别的形状。
 */

/**
 * ③ 任务推进与前端连接无关。
 *
 * 任务（问答生成、向量化进度、导入等）的**唯一真相源在后端**；
 * 前端断连**不得**导致后端停止、回滚或取消任务进程 —— 断连只影响「看到」，不影响「做」。
 *
 * 前端轮询 / SSE 都只是**读取通道**：有进行中任务时前端才轮询或订阅；
 * 重连后靠 REST 补拉状态，不得假设服务端会因连接关闭而取消任务。
 * （本条是硬保证，不是「目前恰好这么做」。）
 */
export interface TaskProgressOwnership {
  /** 唯一真相源在后端 */
  readonly sourceOfTruth: 'server'
  /** 前端断连不影响任务推进 */
  readonly survivesClientDisconnect: true
}

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

export type StreamStatus = 'GENERATED' | 'STOPPED' | 'FAILED'

export interface StreamDone {
  status: StreamStatus
  messageId: string
  tokens: { prompt: number; completion: number; total: number }
  durationMs: number
}

/**
 * 生成模式。缺省 `NEW` = 正常新一轮。
 *  - `REGENERATE` 覆盖 `targetMessageId` 对应的原答案（不新增消息对）
 *  - `CONTINUE`   接着 `targetMessageId` 已生成的内容续写（不伪造新问句）
 */
export type ChatMode = 'NEW' | 'REGENERATE' | 'CONTINUE'

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
  /**
   * 传输层心跳（`: keep-alive` 注释帧）。收到即回调；业务层通常忽略。
   * 有它才谈得上「心跳帧被真的发出」可被观测/断言（见 mock/stream.ts）。
   */
  onHeartbeat?: () => void
}

export interface StreamHandle {
  readonly messageId: string
  /** 主动停止。停止后已生成内容必须保留，不做清屏 */
  abort: () => void
}
