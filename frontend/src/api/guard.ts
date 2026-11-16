import { request } from '@/lib/http'

/* ────────────────────────── 契约类型 ────────────────────────── */

/**
 * 安全围栏的运行开关（`/settings/guard`，表 `pf_guard_settings`，迁移 `V102`）。
 *
 * ★ 与检索引擎参数同一形状：**整行提交**。`PUT` 只传一部分会把没传的字段写成默认值，
 * 表现为"改了一处、另一处悄悄回到初始值"—— 这是本项目栽过的形态（`cv_chat_settings` 的整行覆盖）。
 */
export interface GuardSettings {
  /** 输入检查总开关。关掉 = 全平台问答在无检查状态下运行（会写 `guard.settings.change` 审计） */
  inputEnabled: boolean
  /** 统一拒答话术。★ 默认值与后端 `SafeGuardChatAdvisor.REFUSAL` 一字不差 */
  defaultRefusalText: string
  /** advisor 链是否启用（`app.chat.advisors.enabled` 的运行时形态） */
  advisorsEnabled: boolean
  /**
   * 是否按分类给不同拒答话术。**默认关**：差异化话术会让"这句话属于哪一类"变成
   * 可探测的 —— 关着时所有命中都回同一句。
   */
  categoryRefusalEnabled: boolean
  updatedAt?: string | null
}

export type GuardSettingsUpdate = Omit<GuardSettings, 'updatedAt'>

/** 规则类型。★ 值域只有两个；后端收到别的值返 400（不是静默忽略） */
export type GuardRuleKind = 'KEYWORD' | 'QUESTION'

/** 一条围栏分类。★ 内置分类（政治/色情/暴力/未分类）`builtin=true`，只停用不删 */
export interface GuardCategory {
  id: string
  code: string
  name: string
  sortOrder: number
  enabled: boolean
  builtin: boolean
  /**
   * 这一类的专属拒答话术，null = 用统一话术。
   * ★ 必须带出来：编辑分类时它要能回填 —— 后端保存是**整行覆盖**，前端拿不到原值，
   * 改个名字就会把专属话术静默清掉。
   */
  refusalText: string | null
}

/** 一条围栏规则。★ `content` 是原始写法（界面要显示，否则管理员不知道自己加过什么） */
export interface GuardRule {
  id: string
  categoryId: string
  categoryCode: string
  categoryName: string
  kind: GuardRuleKind
  content: string
  enabled: boolean
  note?: string | null
}

/**
 * 一段时间内某一分类被拦的次数。★ 接口只回计数，不回落库的词面。
 *
 * ★ `categoryName` 由后端现场从分类表取 —— 界面**不要**自己写一张码→名的表：
 *   分类可改名、可自建，本地映射在改名后显示旧名、自建分类只剩机器码。
 *   分类被删掉时这里是 `null`，显示时按 `categoryCode` 回落。
 */
export interface GuardHitCount {
  categoryCode: string
  categoryName: string | null
  hits: number
}

/**
 * 一条拦截图：谁、什么时候、在哪个会话、命中哪条规则。
 *
 * ★ `conversationId` 是「点回那次提问」的唯一入口 —— 原话只存在会话消息里一份，
 *   不复制进审计（审计长期留存，复制一份就删不掉了）。
 */
export interface GuardHitDetail {
  at: string
  categoryCode: string
  /** 分类展示名（后端现场取）；分类已删时为 null，按 categoryCode 回落 */
  categoryName: string | null
  kind: GuardRuleKind
  ruleId: string
  contentHashPrefix: string
  actorId: string | null
  actorName: string | null
  conversationId: string | null
}

/** 「拦截记录」一屏的读数：汇总回答"严重不严重"，明细回答"是哪一次"。 */
export interface GuardHitPage {
  /** 窗口天数；0 表示不限时间（"全部"） */
  days: number
  total: number
  byCategory: GuardHitCount[]
  recent: GuardHitDetail[]
  page: number
  size: number
}

export interface GuardCategoryInput {  code: string
  name: string
  sortOrder?: number
  enabled?: boolean
  /** 分类专属话术；空 = 用统一话术。只有 `categoryRefusalEnabled` 打开时才生效 */
  refusalText?: string | null
}

export interface GuardRuleInput {
  categoryId: string
  kind: GuardRuleKind
  content: string
  enabled?: boolean
  note?: string | null
}

/** 某一天的拦截次数。日期是 `yyyy-MM-dd` 字符串，只有有拦截的日期才出现 */
export interface GuardTrendPoint {
  day: string
  hits: number
}

/**
 * 聚合读数：总量 + 按天 + 按分类。
 *
 * ★ 与 `GuardHitPage` 分开：看板要的是趋势与占比，不需要逐条明细。
 *   合成一个接口，看板一打开就要拉一页明细回来，而那些明细一个字都不显示。
 */
export interface GuardStats {
  /** 窗口天数；0 表示不限时间 */
  days: number
  total: number
  /** 按日期升序；没有拦截的日期不在里面，画图前要补零 */
  byDay: GuardTrendPoint[]
  byCategory: GuardHitCount[]
}

/* ────────────────────────── 接口 ────────────────────────── */

export const getGuardSettings = () =>
  request<GuardSettings>({ method: 'GET', url: '/guard/settings' })

export const updateGuardSettings = (body: GuardSettingsUpdate) =>
  request<GuardSettings>({ method: 'PUT', url: '/guard/settings', body })

export const listGuardCategories = () =>
  request<GuardCategory[]>({ method: 'GET', url: '/guard/categories' })

export const createGuardCategory = (body: GuardCategoryInput) =>
  request<GuardCategory>({ method: 'POST', url: '/guard/categories', body })

export const updateGuardCategory = (id: string, body: GuardCategoryInput) =>
  request<GuardCategory>({ method: 'PUT', url: '/guard/categories/:id', path: { id }, body })

/** 停用（不是删除）：历史审计里那个分类码还要有显示名可对照 */
export const disableGuardCategory = (id: string) =>
  request<GuardCategory>({ method: 'DELETE', url: '/guard/categories/:id', path: { id } })

export const listGuardRules = (categoryId?: string) =>
  request<GuardRule[]>({
    method: 'GET',
    url: '/guard/rules',
    query: categoryId ? { categoryId } : undefined
  })

export const createGuardRule = (body: GuardRuleInput) =>
  request<GuardRule>({ method: 'POST', url: '/guard/rules', body })

export const updateGuardRule = (id: string, body: GuardRuleInput) =>
  request<GuardRule>({ method: 'PUT', url: '/guard/rules/:id', path: { id }, body })

export const deleteGuardRule = (id: string) =>
  request<boolean>({ method: 'DELETE', url: '/guard/rules/:id', path: { id } })

/**
 * 聚合读数：运营看板的「安全围栏」一块用它。
 *
 * @param days 窗口天数；0 = 全部（不限时间）
 */
export const getGuardStats = (days = 30) =>
  request<GuardStats>({ method: 'GET', url: '/guard/stats', query: { days } })

/**
 * 拦截记录（汇总 + 分页明细）。
 *
 * @param days 窗口天数；0 = 全部（不限时间）
 * @param page 页码，从 1 开始
 * @param size 每页条数（后端上限 100）
 */
export const listGuardHits = (days = 7, page = 1, size = 20) =>
  request<GuardHitPage>({ method: 'GET', url: '/guard/hits', query: { days, page, size } })
