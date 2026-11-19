import { ApiError, ErrorCode } from '@/api/types'
import type {
  EvalVerdict,
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
  return {
    winRate: rated === 0 ? null : (better + same * 0.5) / rated,
    ratedItems: rated,
    totalItems: new Set(answers.map((a) => a.itemId)).size,
    better,
    same,
    worse
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
  const base = { itemId: item.id, ordinal: item.ordinal, question: item.question, verdict: null as EvalVerdict | null, note: null, passCount: 1, failCount: 0 }
  run.answers.push(
    { ...base, id: `${run.id}-${item.id}-S`, side: 'SAVED', answer: fakeAnswer(item.question, 'SAVED'), elapsedMs: 820 + index * 40 },
    { ...base, id: `${run.id}-${item.id}-D`, side: 'DRAFT', answer: fakeAnswer(item.question, 'DRAFT'), elapsedMs: 910 + index * 40 }
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
        question
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
      return runView(run)
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
      return runView(run)
    }
  ]
]
