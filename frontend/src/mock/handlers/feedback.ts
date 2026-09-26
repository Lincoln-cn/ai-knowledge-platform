import { ApiError } from '@/api/types'
import type { FeedbackStats, FeedbackStatus, HandleFeedbackBody } from '@/api/feedback'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { feedbacks, promotableKnowledgeBases } from '../fixtures/feedback'
import { currentSubjects, selfId } from '../policy'

const findFeedback = (id: string) => {
  const item = feedbacks.find((f) => f.id === id)
  if (!item) throw new ApiError(404, '反馈不存在或已被清理')
  return item
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

  [
    'PATCH',
    '/feedbacks/:id',
    ({ path, body }) => {
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
    ({ path }) => {
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
