import { ApiError, ErrorCode } from '@/api/types'
import type {
  EvalVerdict,
  PromptAssertion,
  PromptAssertionResult,
  PromptEvalAnswer,
  PromptEvalItem,
  PromptEvalRun,
  PromptEvalSet,
  PromptEvalSummary
} from '@/api/prompts'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { currentSubjects } from '../policy'

/**
 * 提示词评测（ROADMAP §11.63）的 mock。
 *
 * 三件事按真实行为模拟，因为界面要看见它们：
 * ① 起运行**立刻返回** runId（后端就是这么做的：建 RUNNING 行后交给后台线程池），
 *    所以这里也立刻返回，再用定时器推进 itemDone —— 抽屉的轮询与进度条才有东西可看；
 * ② 两版答案**口径相同、内容不同**（一版严格、一版宽松），否则"逐题三档评分"变成走过场；
 * ③ 胜率由评分算出（`同一题两版没差别` 算半分），未评时返回 null ——
 *    界面靠 null 区分「还没评」与「评了但打平」。
 */
const READ_ACTION = PERM.assistantEdit
const WRITE_ACTION = PERM.assistantEdit

function requireRead(token: string): void {
  if (currentSubjects(token).permissions.has(READ_ACTION)) return
  throw new ApiError(ErrorCode.FORBIDDEN, '没有维护应用的权限', '请联系管理员为你的角色勾选该权限点')
}

function requireWrite(token: string): void {
  if (currentSubjects(token).permissions.has(WRITE_ACTION)) return
  throw new ApiError(ErrorCode.FORBIDDEN, '没有维护应用的权限', '请联系管理员为你的角色勾选该权限点')
}

type EvalSetRow = PromptEvalSet & { configId: string }
type EvalItemRow = PromptEvalItem & { setId: string }

/** 判定词表与后端 `PromptAssertions` 保持一致：两边对同一句话给出同一个结论，界面才不会自相矛盾。 */
const UNCERTAIN_WORDS = [
  '没有提到',
  '未提到',
  '未提及',
  '没有提及',
  '无法确定',
  '不确定',
  '无法回答',
  '不能确定',
  '不知道',
  '未涉及',
  '没有涉及',
  '未找到',
  '没有找到',
  '没有相关',
  '无相关',
  '无法判断'
]

/** 列表项：`- x` / `* x` / `• x` / `1. x` / `1、x` / `(1) x`。 */
const LIST_LINE = /^\s*(?:[-*•]\s+|\d+\s*[.、)）]\s+|\(\d+\)\s*)\S/gm

function labelOf(a: PromptAssertion): string {
  const label = (a.label ?? '').trim()
  if (label) return label
  const value = (a.value ?? '').trim()
  switch (a.kind) {
    case 'CONTAINS':
      return `必须出现「${value}」`
    case 'NOT_CONTAINS':
      return `不许出现「${value}」`
    case 'REGEX':
      return `必须匹配 /${value}/`
    case 'MAX_CHARS':
      return `不超过 ${value} 字`
    case 'MIN_CHARS':
      return `不少于 ${value} 字`
    case 'LIST':
      return '要分点输出'
    case 'UNCERTAIN':
      return '不确定时要说出来'
  }
}

/** 逐条判。空数组进来就空数组出去 —— 「没设断言」与「全过」必须分得开。 */
function judge(answer: string, defs: PromptAssertion[]): PromptAssertionResult[] {
  const text = answer ?? ''
  const stripped = text.trim()
  return defs.map((a) => {
    const value = (a.value ?? '').trim()
    const base = { assertionId: a.id, label: labelOf(a), kind: a.kind }
    switch (a.kind) {
      case 'CONTAINS': {
        const ok = text.includes(value)
        return { ...base, passed: ok, detail: ok ? `出现了「${value}」` : `没有出现「${value}」` }
      }
      case 'NOT_CONTAINS': {
        const ok = !text.includes(value)
        return { ...base, passed: ok, detail: ok ? `没有出现「${value}」` : `不该出现「${value}」，但出现了` }
      }
      case 'REGEX': {
        try {
          const ok = new RegExp(value).test(text)
          return { ...base, passed: ok, detail: ok ? `匹配到 /${value}/` : `没有匹配 /${value}/` }
        } catch {
          return { ...base, passed: false, detail: '正则写错了' }
        }
      }
      case 'MAX_CHARS': {
        const limit = Number(value)
        if (!Number.isInteger(limit) || limit <= 0) {
          return { ...base, passed: false, detail: `上限写错了：「${value}」不是正整数` }
        }
        return { ...base, passed: stripped.length <= limit, detail: `字数 ${stripped.length} / 上限 ${limit}` }
      }
      case 'MIN_CHARS': {
        const limit = Number(value)
        if (!Number.isInteger(limit) || limit <= 0) {
          return { ...base, passed: false, detail: `下限写错了：「${value}」不是正整数` }
        }
        return { ...base, passed: stripped.length >= limit, detail: `字数 ${stripped.length} / 下限 ${limit}` }
      }
      case 'LIST': {
        const count = (text.match(LIST_LINE) ?? []).length
        return { ...base, passed: count >= 2, detail: `列表项 ${count} 个（至少 2 个）` }
      }
      case 'UNCERTAIN': {
        const hit = UNCERTAIN_WORDS.find((w) => text.includes(w))
        return {
          ...base,
          passed: hit !== undefined,
          detail: hit ? `说了「${hit}」` : '没有出现「没有提到 / 无法确定」这类表述'
        }
      }
      default:
        return { ...base, passed: false, detail: '断言定义不完整' }
    }
  })
}

const sets: EvalSetRow[] = []
const items: EvalItemRow[] = []
type RunRow = Omit<PromptEvalRun, 'summary'> & { draftText: string; savedText: string }

const runs = new Map<string, RunRow>()

const findSet = (id: string) => {
  const found = sets.find((s) => s.id === id)
  if (!found) throw new ApiError(ErrorCode.NOT_FOUND, '评测集不存在')
  return found
}

const setItems = (setId: string) => items.filter((i) => i.setId === setId).sort((a, b) => a.ordinal - b.ordinal)

/** 两版的假答案：严格版先说结论再给依据，宽松版只给结论 —— 差异要肉眼可见。 */
function fakeAnswer(question: string, side: 'SAVED' | 'DRAFT'): string {
  return side === 'SAVED'
    ? `（已保存版）针对「${question}」：结论如下，依据见引用的两段原文。`
    : `（草稿版）关于「${question}」：先给结论，再逐条列出依据与出处，最后指出未覆盖的部分。`
}

function summaryOf(answers: PromptEvalAnswer[]): PromptEvalSummary {
  const drafts = answers.filter((a) => a.side === 'DRAFT' && a.verdict)
  const better = drafts.filter((a) => a.verdict === 'BETTER').length
  const same = drafts.filter((a) => a.verdict === 'SAME').length
  const worse = drafts.filter((a) => a.verdict === 'WORSE').length
  const rated = drafts.length
  let assertionPass = 0
  let assertionFail = 0
  let assertionAnswers = 0
  for (const a of answers) {
    if (a.passCount + a.failCount > 0) {
      assertionAnswers++
      assertionPass += a.passCount
      assertionFail += a.failCount
    }
  }
  return {
    winRate: rated === 0 ? null : (better + same * 0.5) / rated,
    ratedItems: rated,
    totalItems: new Set(answers.map((a) => a.itemId)).size,
    better,
    same,
    worse,
    assertionPass,
    assertionFail,
    assertionAnswers
  }
}

function runView(run: RunRow): PromptEvalRun {
  return {
    id: run.id,
    setId: run.setId,
    baseVersionNo: run.baseVersionNo,
    status: run.status,
    itemTotal: run.itemTotal,
    itemDone: run.itemDone,
    failReason: run.failReason,
    startedAt: run.startedAt,
    finishedAt: run.finishedAt,
    answers: run.answers,
    summary: summaryOf(run.answers)
  }
}

/** 推进一题：两版各记一条，走的正是后端的 SAVED/DRAFT 两行形状。 */
function answerOne(run: RunRow, index: number): void {
  const item = setItems(run.setId)[index]
  if (!item) return
  const stamp = new Date().toISOString()
  const base = { itemId: item.id, ordinal: item.ordinal, question: item.question, verdict: null as EvalVerdict | null, note: null }
  // ★ 断言按**当时这道题上挂的定义**判（后端同口径）：先判再塞进答案行，别在这里写死"通过 1 条"
  const judged = (side: 'SAVED' | 'DRAFT') => {
    const answer = fakeAnswer(item.question, side)
    const results = judge(answer, item.assertions ?? [])
    return {
      answer,
      results,
      passCount: results.filter((r) => r.passed).length,
      failCount: results.filter((r) => !r.passed).length
    }
  }
  const saved = judged('SAVED')
  const draft = judged('DRAFT')
  run.answers.push(
    { ...base, id: `${run.id}-${item.id}-S`, side: 'SAVED', answer: saved.answer, elapsedMs: 820 + index * 40, passCount: saved.passCount, failCount: saved.failCount, assertionResults: saved.results },
    { ...base, id: `${run.id}-${item.id}-D`, side: 'DRAFT', answer: draft.answer, elapsedMs: 910 + index * 40, passCount: draft.passCount, failCount: draft.failCount, assertionResults: draft.results }
  )
  run.itemDone = index + 1
  if (run.itemDone >= run.itemTotal) {
    run.status = 'DONE'
    run.finishedAt = stamp
  }
}

export const promptEvalRoutes: MockRoute[] = [
  [
    'GET',
    '/prompt-configs/:id/eval-sets',
    ({ path, token }) => {
      requireRead(token)
      return sets.filter((s) => s.configId === path.id).map((s) => ({ ...s, itemCount: setItems(s.id).length }))
    }
  ],

  [
    'POST',
    '/prompt-configs/:id/eval-sets',
    ({ path, body, token }) => {
      requireWrite(token)
      const { name } = body as { name: string }
      if (sets.some((s) => s.configId === path.id && s.name === name)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `评测集「${name}」已存在`)
      }
      const created: EvalSetRow = {
        id: `pes${Date.now()}`,
        configId: path.id,
        name,
        createdAt: new Date().toISOString(),
        itemCount: 0
      }
      sets.unshift(created)
      return created
    }
  ],

  [
    'DELETE',
    '/prompt-eval-sets/:setId',
    ({ path, token }) => {
      requireWrite(token)
      const target = findSet(path.setId)
      sets.splice(sets.indexOf(target), 1)
      items.splice(0, items.length, ...items.filter((i) => i.setId !== path.setId))
      return undefined
    }
  ],

  [
    'GET',
    '/prompt-eval-sets/:setId/items',
    ({ path, token }) => {
      requireRead(token)
      findSet(path.setId)
      return setItems(path.setId)
    }
  ],

  [
    'POST',
    '/prompt-eval-sets/:setId/items',
    ({ path, body, token }) => {
      requireWrite(token)
      findSet(path.setId)
      const { question } = body as { question: string }
      const ordinal = setItems(path.setId).length + 1
      const created: EvalItemRow = {
        id: `pei${Date.now()}`,
        setId: path.setId,
        ordinal,
        question,
        assertions: []
      }
      items.push(created)
      return created
    }
  ],

  [
    'DELETE',
    '/prompt-eval-items/:itemId',
    ({ path, token }) => {
      requireWrite(token)
      const index = items.findIndex((i) => i.id === path.itemId)
      if (index < 0) throw new ApiError(ErrorCode.NOT_FOUND, '题目不存在')
      items.splice(index, 1)
      return undefined
    }
  ],

  [
    'PUT',
    '/prompt-eval-items/:itemId/assertions',
    ({ path, body, token }) => {
      requireWrite(token)
      const index = items.findIndex((i) => i.id === path.itemId)
      if (index < 0) throw new ApiError(ErrorCode.NOT_FOUND, '题目不存在')
      const { assertions } = body as { assertions?: PromptAssertion[] }
      const input = assertions ?? []
      if (input.length > 20) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '一道题最多 20 条断言', '断言是给人逐条核对的，再多就不会有人看读数了')
      }
      input.forEach((a, i) => {
        const where = `第 ${i + 1} 条断言`
        if (!a.kind) throw new ApiError(ErrorCode.BAD_REQUEST, `${where}没有判定类型`)
        const needsValue = a.kind !== 'LIST' && a.kind !== 'UNCERTAIN'
        if (needsValue && !(a.value ?? '').trim()) {
          throw new ApiError(ErrorCode.BAD_REQUEST, `${where}要填内容`, '改好这一条再保存')
        }
      })
      items[index] = {
        ...items[index],
        // 新增的几条前端还不知道 id（传空串）：在这里补，否则两条断言会共用一个空 id
        assertions: input.map((a) => ({
          id: a.id && a.id.trim() ? a.id : `pas${Date.now()}${Math.random().toString(36).slice(2, 6)}`,
          label: (a.label ?? '').trim(),
          kind: a.kind,
          value: (a.value ?? '').trim()
        }))
      }
      return items[index]
    }
  ],

  [
    'POST',
    '/prompt-eval-sets/:setId/runs',
    ({ path, body, token }) => {
      requireWrite(token)
      findSet(path.setId)
      const { draftText } = body as { appId: string; draftText: string }
      const list = setItems(path.setId)
      if (list.length === 0) throw new ApiError(ErrorCode.BAD_REQUEST, '这个评测集还没有题')
      const created = {
        id: `per${Date.now()}`,
        setId: path.setId,
        baseVersionNo: 1,
        status: 'RUNNING' as const,
        itemTotal: list.length,
        itemDone: 0,
        failReason: null,
        startedAt: new Date().toISOString(),
        finishedAt: null,
        answers: [] as PromptEvalAnswer[],
        savedText: '',
        draftText
      }
      runs.set(created.id, created)
      // 模拟后台线程池：每题约 300ms 推进一格，抽屉的 2 秒轮询能看见进度在动
      list.forEach((_, index) => {
        setTimeout(() => answerOne(created, index), 300 * (index + 1))
      })
      // ★ 与后端 `CreateRunResult(UUID id)` 同形状：字段名是 `id`，不是 `runId`
      return { id: created.id }
    }
  ],

  [
    'GET',
    '/prompt-eval-runs/:runId',
    ({ path, token }) => {
      requireRead(token)
      const run = runs.get(path.runId)
      if (!run) throw new ApiError(ErrorCode.NOT_FOUND, '这次评测不存在')
      return runView(run)
    }
  ],

  [
    'PUT',
    '/prompt-eval-runs/:runId/items/:itemId/rating',
    ({ path, body, token }) => {
      requireWrite(token)
      const run = runs.get(path.runId)
      if (!run) throw new ApiError(ErrorCode.NOT_FOUND, '这次评测不存在')
      const { verdict, note } = body as { verdict: EvalVerdict; note?: string | null }
      // 只评草稿侧：人评的是"这道题两版之间"，不该出现两条各自带评分的行
      const draft = run.answers.find((a) => a.itemId === path.itemId && a.side === 'DRAFT')
      if (!draft) throw new ApiError(ErrorCode.NOT_FOUND, '这道题还没有草稿侧结果')
      draft.verdict = verdict
      draft.note = note ?? null
      // 契约是 `request<void>`（src/api/prompts.ts 的 `ratePromptEvalAnswer`）——
      // 返回整份 run 会让 mock 的顶层形状与契约种类不符；页面评完本来就会 poll 一次重拉。
      return null
    }
  ],

  [
    'DELETE',
    '/prompt-eval-runs/:runId/items/:itemId/rating',
    ({ path, token }) => {
      requireWrite(token)
      const run = runs.get(path.runId)
      if (!run) throw new ApiError(ErrorCode.NOT_FOUND, '这次评测不存在')
      const draft = run.answers.find((a) => a.itemId === path.itemId && a.side === 'DRAFT')
      if (draft) {
        draft.verdict = null
        draft.note = null
      }
      // 同 PUT 那条：契约是 `request<void>`。
      return null
    }
  ]
]
