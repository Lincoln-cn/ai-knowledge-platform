import { ApiError, ErrorCode } from '@/api/types'
import type {
  BatchHandleFeedbackBody,
  BatchHandleFeedbackResult,
  FeedbackStats,
  FeedbackStatus,
  HandleFeedbackBody
} from '@/api/feedback'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { feedbacks, promotableKnowledgeBases } from '../fixtures/feedback'
import { users } from '../fixtures/identity'
import { currentSubjects, selfId } from '../policy'

const findFeedback = (id: string) => {
  const item = feedbacks.find((f) => f.id === id)
  if (!item) throw new ApiError(404, '反馈不存在或已被清理')
  return item
}

/**
 * 动作级门禁（与 prompts.ts 同一写法）。页面 `/feedback` 的门禁是 `feedback:handle`，
 * 写接口必须用同一个点 —— 否则「进不去页面却写得动接口」就是一条越权路径。
 * 读路径（列表/详情/统计）不走这里，避免误伤只读场景。
 */
function requireHandle(token: string): void {
  const subjects = currentSubjects(token)
  if (subjects.permissions.has(PERM.feedbackHandle)) return
  throw new ApiError(
    ErrorCode.FORBIDDEN,
    `没有办理反馈的权限（需要 ${PERM.feedbackHandle}）`,
    '请联系管理员为你的角色勾选该权限点'
  )
}

/**
 * 状态机（05-第四梯队 §3.2 / 06 §2.2）：
 *   待处理 → 处理中 → 已处理 / 已忽略
 *   待处理 → 已忽略（直接忽略）
 *   已处理 / 已忽略 →（reopen）→ 处理中
 *
 * 注意：设计图里「重新打开」画的是回到「待处理」，
 * 但冻结契约 `reopenFeedback` 注释写明是「→ 处理中，并清空处理时间」。
 * 契约优先，这里按契约实现；差异已记入迁移报告。
 */
const TRANSITIONS: Record<FeedbackStatus, FeedbackStatus[]> = {
  // 同态更新（补记沉淀 / 改处理说明）也走 PATCH，故把当前状态算作合法目标
  待处理: ['待处理', '处理中', '已处理', '已忽略'],
  处理中: ['处理中', '已处理', '已忽略'],
  已处理: ['已处理'],
  已忽略: ['已忽略']
}

const now = () => {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export const feedbackRoutes: MockRoute[] = [
  [
    'GET',
    '/feedbacks',
    ({ query, token }) => {
      const keyword = String(query.keyword ?? '').trim()
      const status = query.status ? (String(query.status) as FeedbackStatus) : ''
      const appId = query.appId ? String(query.appId) : ''
      const mine = query.mine === true || query.mine === 'true'
      // `mine=true` 时按提交者过滤（07 §六 阶段 1 可选参数 `mine`）
      const meId = mine ? selfId(currentSubjects(token)) : ''

      const list = feedbacks
        .filter((f) => {
          if (!keyword) return true
          const hay = `${f.question}\n${f.answer}\n${f.comment}`
          return hay.toLowerCase().includes(keyword.toLowerCase())
        })
        .filter((f) => !status || f.status === status)
        .filter((f) => !appId || f.appId === appId)
        .filter((f) => !mine || f.requesterId === meId)
        // 待处理优先、新提交在前 —— 形成工作队列
        .slice()
        .sort((a, b) => {
          const rank = (s: FeedbackStatus) =>
            s === '待处理' ? 0 : s === '处理中' ? 1 : s === '已处理' ? 2 : 3
          const diff = rank(a.status) - rank(b.status)
          if (diff !== 0) return diff
          return b.createdAt.localeCompare(a.createdAt)
        })

      return paginate(list, query)
    }
  ],

  [
    'GET',
    '/feedbacks/stats',
    (): FeedbackStats => {
      const count = (s: FeedbackStatus) => feedbacks.filter((f) => f.status === s).length
      return {
        待处理: count('待处理'),
        处理中: count('处理中'),
        已处理: count('已处理'),
        已忽略: count('已忽略')
      }
    }
  ],

  // 放在 /feedbacks/stats 之后：避免 :id 吞掉 stats
  [
    'GET',
    '/feedbacks/:id',
    ({ path }) => findFeedback(path.id)
  ],

  // 静态段必须排在 PATCH /feedbacks/:id 之前 —— 否则 :id 会把 batch 当成 id 吞掉
  // （与 /assistants/tags 同一教训）
  [
    'PATCH',
    '/feedbacks/batch',
    ({ body, token }): BatchHandleFeedbackResult => {
      requireHandle(token)
      const input = body as BatchHandleFeedbackBody
      const ids = Array.isArray(input.ids) ? input.ids : []
      if (!ids.length) {
        throw new ApiError(400, '请至少选择一条反馈再批量办理')
      }

      // 处理人是整批共用参数：id 不合法就整单拒绝，不要做一半才说指派失败
      let assigneeName: string | null | undefined
      if (input.assigneeId !== undefined) {
        if (input.assigneeId === null) {
          assigneeName = null
        } else {
          const user = users.find((u) => u.id === input.assigneeId)
          if (!user) {
            throw new ApiError(400, '指定的处理人不存在', '请从用户目录里选择处理人')
          }
          assigneeName = user.displayName
        }
      }

      const accepted: BatchHandleFeedbackResult['accepted'] = []
      const rejected: BatchHandleFeedbackResult['rejected'] = []

      for (const id of ids) {
        const item = feedbacks.find((f) => f.id === id)
        if (!item) {
          rejected.push({ id, reason: '反馈不存在或已被清理' })
          continue
        }

        if (!TRANSITIONS[item.status].includes(input.status)) {
          rejected.push({
            id,
            reason:
              item.status === '已处理' || item.status === '已忽略'
                ? `「${item.status}」不能改状态；换状态请先「重新打开」`
                : `不能从「${item.status}」流转到「${input.status}」`
          })
          continue
        }

        // 与单条办理同一条规则：已处理必须有结论，否则闭环是空壳
        if (input.status === '已处理' && !input.resolution?.trim()) {
          rejected.push({ id, reason: '流转到「已处理」必须填写处理说明' })
          continue
        }

        const prevStatus = item.status
        item.status = input.status
        if (assigneeName !== undefined) {
          item.assignee = assigneeName
        }
        if (input.resolution !== undefined) {
          item.resolution = input.resolution
        }

        const enteredTerminal =
          prevStatus !== input.status && (input.status === '已处理' || input.status === '已忽略')
        if (enteredTerminal) {
          item.handledAt = now()
        } else if (input.status === '待处理' || input.status === '处理中') {
          item.handledAt = null
        }

        accepted.push(item)
      }

      // 部分失败必须逐条回执：只回总数的话，用户不知道哪几条没办成、为什么
      return { accepted, rejected }
    }
  ],

  [
    'PATCH',
    '/feedbacks/:id',
    ({ path, body, token }) => {
      requireHandle(token)
      const item = findFeedback(path.id)
      const input = body as HandleFeedbackBody

      if (!TRANSITIONS[item.status].includes(input.status)) {
        throw new ApiError(
          4001,
          `不能从「${item.status}」流转到「${input.status}」`,
          item.status === '已处理' || item.status === '已忽略'
            ? '已办结的反馈不能改状态；补记沉淀可直接保存，换状态请先「重新打开」'
            : '请按 状态机：待处理 → 处理中 → 已处理 / 已忽略 操作'
        )
      }

      // 已处理必须有结论 —— 否则「处理记录」只是个空壳，闭环名存实亡
      if (input.status === '已处理' && !input.resolution?.trim()) {
        throw new ApiError(4002, '流转到「已处理」必须填写处理说明', '写清定位结论与采取的动作，便于回访与复盘')
      }

      const prevStatus = item.status
      item.status = input.status
      item.assignee = input.assignee ?? null
      item.resolution = input.resolution ?? ''

      // 同态补记（如事后沉淀）不刷新办结时间；真正进入终态才盖时间戳
      const enteredTerminal =
        prevStatus !== input.status && (input.status === '已处理' || input.status === '已忽略')
      if (enteredTerminal) {
        item.handledAt = now()
      } else if (input.status === '待处理' || input.status === '处理中') {
        item.handledAt = null
      }

      // 沉淀为知识库问题（FR-03-12 问答对）。目标库必须存在，避免写进空气里
      if (input.promoteToKbId) {
        const kb = promotableKnowledgeBases.find((k) => k.id === input.promoteToKbId)
        if (!kb) {
          throw new ApiError(4003, `目标知识库不存在`, '请选择一个可用的知识库作为沉淀目标')
        }
        item.promotedToKbId = kb.id
        item.promotedToKbName = kb.name
      } else if (input.promoteToKbId === null) {
        item.promotedToKbId = null
        item.promotedToKbName = null
      }

      return item
    }
  ],

  [
    'POST',
    '/feedbacks/:id/reopen',
    ({ path, token }) => {
      requireHandle(token)
      const item = findFeedback(path.id)
      if (item.status !== '已处理' && item.status !== '已忽略') {
        throw new ApiError(4004, `「${item.status}」状态的反馈无需重新打开`, '只有已处理 / 已忽略可以重新打开')
      }
      // 契约：已处理/已忽略 → 处理中，并清空处理时间
      item.status = '处理中'
      item.handledAt = null
      return item
    }
  ]
]
