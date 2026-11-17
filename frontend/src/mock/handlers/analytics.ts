import dayjs from 'dayjs'
import type { MockRoute } from '../dispatch'
import {
  mySummaryBetween,
  overviewBetween,
  qualityBetween,
  rankBetween,
  trendBetween
} from '../fixtures/analytics'

/** 缺省区间：近 7 天（含今天） */
const defaultRange = () => {
  const to = dayjs()
  const from = to.subtract(6, 'day')
  return { from: from.format('YYYY-MM-DD'), to: to.format('YYYY-MM-DD') }
}

const readRange = (query: Record<string, unknown>) => {
  const from = query.from ? String(query.from) : ''
  const to = query.to ? String(query.to) : ''
  if (!from || !to) return defaultRange()
  return { from, to }
}

export const analyticsRoutes: MockRoute[] = [
  [
    'GET',
    '/analytics/overview',
    ({ query }) => {
      const { from, to } = readRange(query)
      return overviewBetween(from, to)
    }
  ],

  [
    'GET',
    '/analytics/trend',
    ({ query }) => {
      const { from, to } = readRange(query)
      return trendBetween(from, to)
    }
  ],

  [
    'GET',
    '/analytics/assistants',
    ({ query }) => {
      const { from, to } = readRange(query)
      const limit = Number(query.limit ?? 10)
      return rankBetween(from, to, limit)
    }
  ],

  [
    'GET',
    '/analytics/quality',
    ({ query }) => {
      const { from, to } = readRange(query)
      return qualityBetween(from, to)
    }
  ],

  /**
   * 运营指标（#357 前半）。
   *
   * ★ mock 里的数按"7 天、有提问、失败少、引用基本都有"造，量级与真栈同数量级；
   * 关键是**分母要对得上**：`answersWithoutRefs` / `generatedAnswerCount` 必须能算出
   * `noRefRate`，否则页面上那行"分母是成功回答"的说明就成了谎话。
   */
  [
    'GET',
    '/analytics/metrics',
    ({ query }) => {
      const { from, to } = readRange(query)
      const days = Math.max(1, dayjs(to).diff(dayjs(from), 'day') + 1)
      const questions = 40 * days
      const generated = questions - 3
      const withoutRefs = Math.round(generated * 0.04)
      const up = Math.round(questions * 0.18)
      const down = Math.round(questions * 0.03)
      return {
        questionCount: questions,
        answerCount: questions,
        generatedAnswerCount: generated,
        failedAnswerCount: 3,
        tokenCount: questions * 860,
        avgDurationMs: 7420,
        durationP50Ms: 6180,
        durationP95Ms: 12400,
        avgRefCount: 3.2,
        answersWithoutRefs: withoutRefs,
        noRefRate: Math.round((withoutRefs / generated) * 1000) / 10,
        activeAssistantCount: 4,
        activeUserCount: 7,
        upCount: up,
        downCount: down,
        satisfactionRate: Math.round((up / (up + down)) * 1000) / 10
      }
    }
  ],

  [
    'GET',
    '/analytics/me/summary',
    ({ query }) => {
      const { from, to } = readRange(query)
      return mySummaryBetween(from, to)
    }
  ]
]
