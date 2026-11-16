import { ApiError, ErrorCode } from '@/api/types'
import type {
  GuardCategory,
  GuardCategoryInput,
  GuardHitCount,
  GuardHitDetail,
  GuardRule,
  GuardRuleInput,
  GuardSettings,
  GuardSettingsUpdate
} from '@/api/guard'
import type { MockRoute } from '../dispatch'
import { PERM } from '@/constants/permissions'
import { currentSubjects } from '../policy'

/**
 * 安全围栏（`/settings/guard`，ROADMAP §11.53）的 mock。
 *
 * ★ 夹具里**不预置任何规则** —— 与真栈的 `V102` 迁移同口径（只建表、不灌词）。
 *   假词会让「这一页长什么样」被误读成「这一页默认拦什么」；而真实部署的第一条规则
 *   必须由部署方按自己的合规要求写。
 *
 * ★ 分类的四个内置项照抄迁移里的固定 UUID：mock 与真栈的 code/名称一致，
 *   界面上的计数与文案才不会在两种模式下说两句不同的话。
 */
const settings: GuardSettings = {
  inputEnabled: true,
  defaultRefusalText: '抱歉，这个问题不在可回答范围内。',
  advisorsEnabled: true,
  categoryRefusalEnabled: false,
  updatedAt: null as string | null
}

const categories: GuardCategory[] = [
  { id: '11111111-1111-4111-8111-111111111111', code: 'POLITICS', name: '政治', sortOrder: 10, enabled: true, builtin: true, refusalText: null },
  { id: '22222222-2222-4222-8222-222222222222', code: 'PORN', name: '色情', sortOrder: 20, enabled: true, builtin: true, refusalText: null },
  { id: '33333333-3333-4333-8333-333333333333', code: 'VIOLENCE', name: '暴力', sortOrder: 30, enabled: true, builtin: true, refusalText: null },
  { id: '00000000-0000-4000-8000-000000000000', code: 'UNCLASSIFIED', name: '未分类', sortOrder: 999, enabled: true, builtin: true, refusalText: null }
]

const rules: GuardRule[] = []

const hits: GuardHitCount[] = []

/**
 * 拦截明细的夹具。
 * ★ 空着 —— 与规则表同口径：预置一条"谁在什么时候问了什么"会让页面看起来
 *   像"已经有人踩过围栏"，而实际上这是这套系统最不该被假数据演示的一件事。
 */
const hitDetails: GuardHitDetail[] = []

/** 全局权限门：围栏没有"哪一条"可判，读写都守同一颗点（与后端类级 `@PreAuthorize` 一致）。 */
function requirePerm(token: string, label: string): void {
  if (currentSubjects(token).permissions.has(PERM.guardManage)) return
  throw new ApiError(ErrorCode.FORBIDDEN, `需要「${label}」权限`)
}

const categoryOf = (id: string): GuardCategory => {
  const found = categories.find((c) => c.id === id)
  if (!found) throw new ApiError(ErrorCode.NOT_FOUND, '分类不存在')
  return found
}

const ruleView = (rule: {
  id: string
  categoryId: string
  kind: GuardRule['kind']
  content: string
  enabled: boolean
  note?: string | null
}): GuardRule => {
  const category = categoryOf(rule.categoryId)
  return {
    id: rule.id,
    categoryId: rule.categoryId,
    categoryCode: category.code,
    categoryName: category.name,
    kind: rule.kind,
    content: rule.content,
    enabled: rule.enabled,
    note: rule.note ?? null
  }
}

export const guardRoutes: MockRoute[] = [
  // ── 设置（整行读写）────────────────────────────────────────────────
  ['GET', '/guard/settings', ({ token }) => {
    requirePerm(token, '安全围栏')
    return settings
  }],

  ['PUT', '/guard/settings', ({ body, token }) => {
    requirePerm(token, '安全围栏')
    const input = body as Partial<GuardSettingsUpdate>
    const next: GuardSettingsUpdate = {
      inputEnabled: input.inputEnabled ?? settings.inputEnabled,
      defaultRefusalText: (input.defaultRefusalText ?? settings.defaultRefusalText).trim(),
      advisorsEnabled: input.advisorsEnabled ?? settings.advisorsEnabled,
      categoryRefusalEnabled: input.categoryRefusalEnabled ?? settings.categoryRefusalEnabled
    }
    if (!next.defaultRefusalText) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '统一拒答话术不能为空')
    }
    if (next.defaultRefusalText.length > 200) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '统一拒答话术不能超过 200 字')
    }
    Object.assign(settings, next, { updatedAt: new Date().toISOString() })
    return settings
  }],

  // ── 分类 ──────────────────────────────────────────────────────────
  ['GET', '/guard/categories', ({ token }) => {
    requirePerm(token, '安全围栏')
    return [...categories].sort((a, b) => a.sortOrder - b.sortOrder)
  }],

  ['POST', '/guard/categories', ({ body, token }) => {
    requirePerm(token, '安全围栏')
    const input = body as GuardCategoryInput
    const code = (input.code ?? '').trim().toUpperCase()
    if (!/^[A-Z][A-Z0-9_]{0,31}$/.test(code)) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '编码需大写字母开头，只含大写字母、数字与下划线')
    }
    if (categories.some((c) => c.code === code)) {
      throw new ApiError(ErrorCode.CONFLICT, '这个编码已存在')
    }
    const created: GuardCategory = {
      id: crypto.randomUUID(),
      code,
      name: (input.name ?? '').trim(),
      sortOrder: input.sortOrder ?? 100,
      enabled: input.enabled ?? true,
      builtin: false,
      refusalText: (input.refusalText ?? '').trim() || null
    }
    if (!created.name) throw new ApiError(ErrorCode.BAD_REQUEST, '名称不能为空')
    categories.push(created)
    return created
  }],

  ['PUT', '/guard/categories/:id', ({ path, body, token }) => {
    requirePerm(token, '安全围栏')
    const current = categoryOf(path.id)
    const input = body as Partial<GuardCategoryInput>
    if (input.name !== undefined) current.name = input.name.trim()
    if (input.sortOrder !== undefined) current.sortOrder = input.sortOrder
    if (input.enabled !== undefined) current.enabled = input.enabled
    // ★ 只有真的带了这个字段才改：否则「只改名字」会把专属话术清成 null
    if (input.refusalText !== undefined) current.refusalText = (input.refusalText ?? '').trim() || null
    return current
  }],

  // DELETE 的语义是**停用**（历史审计里那个分类码还要有显示名可对照）
  ['DELETE', '/guard/categories/:id', ({ path, token }) => {
    requirePerm(token, '安全围栏')
    const current = categoryOf(path.id)
    current.enabled = false
    return current
  }],

  // ── 规则 ──────────────────────────────────────────────────────────
  ['GET', '/guard/rules', ({ query, token }) => {
    requirePerm(token, '安全围栏')
    const categoryId = typeof query.categoryId === 'string' ? query.categoryId : undefined
    return rules.filter((r) => !categoryId || r.categoryId === categoryId)
  }],

  ['POST', '/guard/rules', ({ body, token }) => {
    requirePerm(token, '安全围栏')
    const input = body as GuardRuleInput
    const content = (input.content ?? '').trim()
    if (!content) throw new ApiError(ErrorCode.BAD_REQUEST, '内容不能为空')
    if (content.length > 200) throw new ApiError(ErrorCode.BAD_REQUEST, '单条规则不能超过 200 字')
    categoryOf(input.categoryId)
    // ★ 归一化后为空等于"拦掉全平台"（包含匹配对空串恒真）—— 后端同样会拒
    const normalized = content.replace(/\s+/g, '')
    if (!normalized) throw new ApiError(ErrorCode.BAD_REQUEST, '这条规则归一化后是空串，会拦住所有输入')
    const created = ruleView({
      id: crypto.randomUUID(),
      categoryId: input.categoryId,
      kind: input.kind,
      content,
      enabled: input.enabled ?? true,
      note: input.note ?? null
    })
    if (rules.some((r) => r.categoryId === created.categoryId && r.kind === created.kind && r.content === content)) {
      throw new ApiError(ErrorCode.CONFLICT, '同一分类下已有相同内容的规则')
    }
    rules.push(created)
    return created
  }],

  ['PUT', '/guard/rules/:id', ({ path, body, token }) => {
    requirePerm(token, '安全围栏')
    const index = rules.findIndex((r) => r.id === path.id)
    if (index < 0) throw new ApiError(ErrorCode.NOT_FOUND, '规则不存在')
    const input = body as Partial<GuardRuleInput>
    const content = (input.content ?? rules[index].content).trim()
    if (!content) throw new ApiError(ErrorCode.BAD_REQUEST, '内容不能为空')
    const categoryId = input.categoryId ?? rules[index].categoryId
    categoryOf(categoryId)
    const updated = ruleView({
      id: path.id,
      categoryId,
      kind: input.kind ?? rules[index].kind,
      content,
      enabled: input.enabled ?? rules[index].enabled,
      note: input.note ?? rules[index].note
    })
    rules[index] = updated
    return updated
  }],

  ['DELETE', '/guard/rules/:id', ({ path, token }) => {
    requirePerm(token, '安全围栏')
    const index = rules.findIndex((r) => r.id === path.id)
    if (index < 0) throw new ApiError(ErrorCode.NOT_FOUND, '规则不存在')
    rules.splice(index, 1)
    return true
  }],

  // ── 拦截读数（汇总 + 明细；原话不在审计里，明细给的是会话 id）─────────
  ['GET', '/guard/hits', ({ token, query }) => {
    requirePerm(token, '安全围栏')
    const days = Number(query?.days ?? 7) || 7
    const recent = hitDetails.slice(0, 100)
    return {
      days,
      total: hitDetails.length,
      byCategory: hits,
      recent,
      truncated: hitDetails.length > recent.length
    }
  }]
]
