import dayjs from 'dayjs'
import type { MockRoute } from '../dispatch'
import {
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
  ]
]
