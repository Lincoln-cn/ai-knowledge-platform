import dayjs from 'dayjs'
import type {
  AssistantRankItem,
  OverviewMetrics,
  QualityMetrics,
  TrendPoint
} from '@/api/analytics'
import { assistants } from './assistants'

/**
 * 运营看板数据。
 *
 * 刻意构造出目标设计里的几条关键结论，不是随手编的：
 *  - 首字延迟 P50 在 2 秒内（对照验收 B4 ≤ 10s，且文档强调它比总耗时更贴近用户感知）
 *  - 结构化输出失败率非空但不参与考核（MVP 只埋点）
 *  - 满意度有一个应用无数据（null → 界面显示「—」而不是 0）
 *  - 待处理反馈 > 0，用来验证看板能跳到 /feedback
 */

/** 以固定纪元做种子，保证同一天多次刷新数据稳定 */
const EPOCH = dayjs('2024-01-01')

const daySeed = (d: dayjs.Dayjs) => d.diff(EPOCH, 'day')

/** 确定性波动：不用 Math.random，刷新页面数字不乱跳 */
const wave = (seed: number, base: number, amp: number) =>
  Math.max(0, Math.round(base + amp * Math.sin(seed / 3) + ((seed * 17) % 7) - 3))

const dayList = (from: string, to: string): dayjs.Dayjs[] => {
  const start = dayjs(from)
  const end = dayjs(to)
  if (!start.isValid() || !end.isValid() || end.isBefore(start)) return []
  const days: dayjs.Dayjs[] = []
  for (let d = start; d.isBefore(end) || d.isSame(end, 'day'); d = d.add(1, 'day')) {
    days.push(d)
  }
  // 防御：区间过长时截到 90 天，避免 mock 生成过量点
  return days.slice(-90)
}

export function trendBetween(from: string, to: string): TrendPoint[] {
  return dayList(from, to).map((d) => {
    const seed = daySeed(d)
    return {
      day: d.format('YYYY-MM-DD'),
      questions: wave(seed, 120, 36),
      users: wave(seed + 5, 48, 14)
    }
  })
}

export function overviewBetween(from: string, to: string): OverviewMetrics {
  const points = trendBetween(from, to)
  const questionCount = points.reduce((s, p) => s + p.questions, 0)
  // 活跃用户按日活的约 6% 重复访问估算，保证 ≤ 累计日活
  const activeUserCount = Math.round(points.reduce((s, p) => s + p.users, 0) * 0.62) + 12
  const newUserCount = points.length * 3 + 4
  const tokenCount = questionCount * 860 + 12000
  // 区间太短且几乎没提问时满意度无意义 → null，界面显示「—」
  const satisfaction = questionCount < 50 ? null : 91.5
  return { questionCount, activeUserCount, newUserCount, tokenCount, satisfaction }
}

/**
 * 应用排行的**用量种子**。
 *
 * 只放用量数字，**不放应用名** —— 名字从 `assistants` 派生。
 * 原先这里另存了一份名字，结果与实际应用对不上（旧系统残留名），
 * 界面上的排行会出现查不到的应用。名字只有一个真相源。
 *
 * 第三条 satisfaction 故意为 null ——
 * 「无数据时显示 — 而不是 0」这条约定必须在界面上真的能被看到。
 */
const rankUsageSeed: Array<{ appId: string; callCount: number; tokenCount: number; satisfaction: number | null }> = [
  { appId: 'as1', callCount: 842, tokenCount: 712400, satisfaction: 94.2 },
  { appId: 'as2', callCount: 615, tokenCount: 401200, satisfaction: 89.6 },
  { appId: 'as3', callCount: 388, tokenCount: 256800, satisfaction: null },
  { appId: 'as4', callCount: 274, tokenCount: 168300, satisfaction: 86.1 },
  { appId: 'as5', callCount: 156, tokenCount: 97400, satisfaction: 92.0 }
]

/** 应用名从 assistants 取；查不到时显式给 '—'，不编一个名字 */
const rankSeed: AssistantRankItem[] = rankUsageSeed.map((seed) => ({
  ...seed,
  name: assistants.find((a) => a.id === seed.appId)?.name ?? '—'
}))

export function rankBetween(from: string, to: string, limit = 10): AssistantRankItem[] {
  const days = Math.max(1, dayList(from, to).length)
  const scale = days / 7
  return rankSeed
    .map((a) => ({
      ...a,
      callCount: Math.round(a.callCount * scale),
      tokenCount: Math.round(a.tokenCount * scale)
    }))
    .sort((a, b) => b.callCount - a.callCount)
    .slice(0, limit)
}

export function qualityBetween(from: string, to: string): QualityMetrics {
  const days = Math.max(1, dayList(from, to).length)
  const scale = days / 7
  return {
    avgRefCount: 2.4,
    firstTokenLatencyP50: 1860,
    /** 0–1 比率。MVP 只埋点，V1.1 起考核 */
    artifactFailureRate: 0.032,
    upCount: Math.round(156 * scale),
    downCount: Math.round(23 * scale),
    /** 与时间范围无关的存量待办 */
    pendingFeedbackCount: 7
  }
}
