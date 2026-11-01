/**
 * 首次引导的四步与进度（`ROADMAP §五` F2：「建知识库 → 传文档 → 建应用 → 试问答」的最短路径 + 进度）。
 *
 * <h3>为什么要有这一层</h3>
 * <p>工作台此前只有一组「从这里开始」的**入口**（按权限过滤过，这半边是对的），
 * 但没有**进度**：一个刚进来的建设者看到四个入口，不知道哪一步做过了、下一步该做什么。
 * 外部用户的第一印象就停在这里 —— 这正是不开源也得先补的短板（F2 排在四件事最前）。</p>
 *
 * <h3>★ 三态，不是两态</h3>
 * <p>每步有 {@code done} / {@code todo} / <b>{@code unknown}</b>。第三档不是洁癖：
 * 每个计数来自一次接口调用，**接口失败时我们并不知道用户做没做**。把它算成 {@code todo}
 * 会引导一个已经有知识库的人再建一个（重复建设 + 重复入库），算成 {@code done} 则会让
 * 真正没做的人以为万事俱备。两种都是编造读数 —— 本工程的通则：没测到显示「—」，不显示 0。</p>
 *
 * <h3>★ 全做完了就整块消失</h3>
 * <p>引导条不是常驻横幅。四步都 {@code done} 时返回 {@code null}，工作台退回它原本的入口列表 ——
 * 老用户不该每天被"你已完成 4/4"打招呼。</p>
 *
 * <h3>零依赖</h3>
 * <p>只吃数字与一个「这一步归不归你管」的谓词，不引 `@/api/*`、不引 router/session ——
 * 理由同 `lib/degrade.ts` / `lib/modelReadiness.ts`：判据要能被 `node --test` 直接跑。
 */
export type OnboardingStepKey = 'kb' | 'document' | 'assistant' | 'ask'

/** 每步的计数。`null` = 没测到（接口失败或还没拉），**不是 0** */
export interface OnboardingCounts {
  kbCount: number | null
  docCount: number | null
  appCount: number | null
  askCount: number | null
}

export interface OnboardingStepDef {
  key: OnboardingStepKey
  label: string
  desc: string
  /** 这一步动手的地方 */
  path: string
}

export type OnboardingStatus = 'done' | 'todo' | 'unknown'

export interface OnboardingStep extends OnboardingStepDef {
  status: OnboardingStatus
  /** 这一步的计数读数（`null` 时界面上写「—」） */
  count: number | null
}

export interface OnboardingView {
  steps: OnboardingStep[]
  /** 在**可见**步骤里已完成的条数 */
  doneCount: number
  /** 可见步骤总数（分母） */
  totalCount: number
  /** 还没做完的数量（只数 todo —— unknown 不是"没做"） */
  todoCount: number
}

/** 四步的顺序就是最短路径的顺序：先有库、再有文、才谈得上建应用与提问 */
const STEP_DEFS: OnboardingStepDef[] = [
  { key: 'kb', label: '建知识库', desc: '把要检索的资料收进来', path: '/knowledge' },
  { key: 'document', label: '传文档', desc: '文档切分入库后才会被检索到', path: '/knowledge' },
  {
    key: 'assistant',
    label: '建应用',
    desc: '把模型、知识库与提示词组装成一个对话服务',
    path: '/assistants'
  },
  { key: 'ask', label: '试问答', desc: '提一个你真想问的问题，检查答案与引用', path: '/conversation' }
]

/**
 * 四步指向的页面 —— 工作台拿它把「已经写在路径里的入口」从入口列表里摘掉。
 * 路径块本身就是可点的入口；再并排放一条「建知识库」，同一件事就写了两遍。
 */
export const ONBOARDING_STEP_PATHS: readonly string[] = STEP_DEFS.map((def) => def.path)

function countOf(counts: OnboardingCounts, key: OnboardingStepKey): number | null {
  switch (key) {
    case 'kb':
      return counts.kbCount
    case 'document':
      return counts.docCount
    case 'assistant':
      return counts.appCount
    case 'ask':
      return counts.askCount
  }
}

/**
 * 算出上手路径。
 *
 * @param counts  四个计数（`null` = 没测到）
 * @param allowed 这一步归不归当前用户管（调用方按 `canReach` + `session.can` 算好）。
 *                不归他管的步骤**整条不出现** —— 让他看到"你没有知识库"而他又没权限建，
 *                等于给一个他解不开的暗号。
 * @returns `null` = 不该显示（还有没做完的才显示）
 */
export function buildOnboarding(
  counts: OnboardingCounts,
  allowed: (key: OnboardingStepKey) => boolean
): OnboardingView | null {
  const steps: OnboardingStep[] = []

  for (const def of STEP_DEFS) {
    if (!allowed(def.key)) continue
    const count = countOf(counts, def.key)
    const status: OnboardingStatus = count === null ? 'unknown' : count > 0 ? 'done' : 'todo'
    steps.push({ ...def, count, status })
  }

  const doneCount = steps.filter((s) => s.status === 'done').length
  const todoCount = steps.filter((s) => s.status === 'todo').length

  // 没有一步需要动手 ⇒ 不显示。★ 注意这里只数 `todo`：
  // 一份"全是 unknown"的读数是"我不知道你做到哪了"，它不该变成一屏「去建库」。
  if (todoCount === 0) return null

  return { steps, doneCount, totalCount: steps.length, todoCount }
}
