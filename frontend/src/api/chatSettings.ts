/**
 * 「对话设置」—— **平台级**的通用会话配置（契约，对应后端 `GET/PUT /api/v1/chat-settings`）。
 *
 * ## 它解决的是什么
 *
 * 「通用问答」（`appId` 为空、不带知识库、不带引用）在此之前**没有任何配置入口**：
 * 用的模型是 `pickChatModel()` 随便挑一个，流式参数只能改 yaml 并重启。
 * 而在 CPU + 小模型上「长回答被 60s 判吊死」这件事，用户在页面上**无处可调**（`#181`）。
 *
 * ## ★★ 三层继承（本页是中间那一层）
 *
 * ```
 * 应用 as_assistant.chat_*（可空）  →  通用会话（本页）  →  全局 app.chat.*（yaml 兜底）
 * ```
 *
 * ★ 逐项覆盖，不是"整层二选一"：某个应用只想改空闲超时时，心跳仍跟随这里。
 *
 * ## ★ 两个单位约定
 *
 * - **后端一律毫秒**（`idleTimeoutMs` / `heartbeatIntervalMs` / `firstTokenBudgetMs`）；
 * - 界面上用**秒**显示（人能读），只在提交/回填时换算 —— ★ 换算只发生在页面一处，
 *   契约层不掺和，否则"某些地方按秒、某些地方按毫秒"必然出现差 1000 倍的静默错误。
 */
import { request } from '@/lib/http'

/** 通用会话设置（与 `cv_chat_settings` 一一对应）。 */
export interface ChatSettings {
  /** 通用问答默认用的对话模型；★ `null` = 沿用「挑一个可用的」，不是「没配」 */
  defaultModelId: string | null
  /** 是否流式输出 */
  streamEnabled: boolean
  /** 多久没有字节写出就判吊死（毫秒）。★ SSE 的唯一超时权威 */
  idleTimeoutMs: number
  /** 心跳间隔（毫秒）。★ 约束：`heartbeat × 3 ≤ idle` */
  heartbeatIntervalMs: number
  /** 首字延迟预算（毫秒，ROADMAP B4：≤10s） */
  firstTokenBudgetMs: number
  /**
   * 通用问答的**初始提示词**（系统提示词）；`null` / 空 = 不加前缀。
   *
   * ★ 只对通用问答生效：应用有自己的 `role_prompt`，两者不叠加
   *   （叠加会变成「两条口径同时约束一个回答」，冲突时谁赢没人说得清）。
   */
  systemPrompt: string | null
  /**
   * 通用问答是否走召回。
   *
   * ★★ 默认 `false`：不配置就不检索。之前通用问答在**静默做全库检索**，
   * 于是「问 java hello world、答 Vue 单文件组件」——那 5 条引用全是 0.25 分。
   */
  retrievalEnabled: boolean
  /** 召回范围（知识库 id）；`null`/空 = 该用户可见的全部库（仅启用召回时生效） */
  retrievalKbIds: string[] | null
  /** 相似度下限（0~1），低于它的命中丢弃 */
  retrievalThreshold: number
  /** 召回条数上限 */
  retrievalTopN: number
  /** 单轮最大输出 tokens；`null` = 不限制 */
  maxOutputTokens: number | null
  /** 最后修改时间（服务端写，界面用来确认"改上了"） */
  updatedAt: string | null
}

/** 提交体：不含 `updatedAt`（那是服务端写的）。 */
export type ChatSettingsCommand = Omit<ChatSettings, 'updatedAt'>

/** 读当前设置（权限 `conversation:view`）。 */
export const getChatSettings = () =>
  request<ChatSettings>({ method: 'GET', url: '/chat-settings' })

/**
 * 整行覆盖保存（权限 `system:manage`）。
 *
 * ★ 后端会用 `ChatStreamPolicy` 的构造器再校验一次 `heartbeat × 3 ≤ idle`：
 * 前端拦一次是为了"改的时候就知道"，后端拦一次是因为**绕过前端的方式永远存在**。
 */
export const saveChatSettings = (body: ChatSettingsCommand) =>
  request<ChatSettings>({ method: 'PUT', url: '/chat-settings', body })
