import type { MockRoute } from '../dispatch'
import type {
  ErrorLogEntry,
  ErrorSource,
  LoginLogEntry,
  LoginResult,
  RiskItem
} from '@/api/ops-zone'

/**
 * 「审计与风险」专区的 mock（登录日志 / 风险提示 / 错误日志，`#354`）。
 *
 * ★ 三个夹具都**故意不是全绿**：
 * 登录日志里有被拒与验证码错（否则"结果列"永远只有一种值，看不出它有没有起作用），
 * 风险提示里留一条 WARN，错误日志里给两条真实形状的失败原因。
 * 只造干净数据的 mock 会让"空态与告警样式"在 mock 模式下永远走不到 ——
 * 而这三页的重点恰恰是"出问题时长什么样"。
 */

const now = Date.now()
const minute = 60_000

const loginLogs: LoginLogEntry[] = [
  entry(1, 'admin', '10000000-0000-0000-0000-000000000001', 'SUCCESS', '10.20.3.18'),
  entry(6, 'chenjun', '10000000-0000-0000-0000-000000000004', 'SUCCESS', '10.20.7.44'),
  entry(9, 'chenjun', '10000000-0000-0000-0000-000000000004', 'BAD_CREDENTIALS', '10.20.7.44'),
  entry(11, 'chenjun', '10000000-0000-0000-0000-000000000004', 'BAD_CREDENTIALS', '10.20.7.44'),
  entry(12, 'chenjun', '10000000-0000-0000-0000-000000000004', 'LOCKED', '10.20.7.44'),
  entry(40, 'zhangyun', '10000000-0000-0000-0000-000000000002', 'SUCCESS', '10.20.5.9'),
  entry(95, 'root', null, 'BAD_CREDENTIALS', '203.0.113.77'),
  entry(96, 'test', null, 'BAD_CREDENTIALS', '203.0.113.77'),
  entry(150, 'liwei', '10000000-0000-0000-0000-000000000003', 'CAPTCHA', '10.20.6.21'),
  entry(220, 'liwei', '10000000-0000-0000-0000-000000000003', 'SUCCESS', '10.20.6.21'),
  entry(1_500, 'chenjun', '10000000-0000-0000-0000-000000000004', 'DISABLED', '10.20.7.44'),
  entry(2_900, 'admin', '10000000-0000-0000-0000-000000000001', 'SUCCESS', '10.20.3.18')
]

function entry(
  minutesAgo: number,
  username: string,
  userId: string | null,
  result: LoginResult,
  ip: string
): LoginLogEntry {
  return {
    id: `ll-${minutesAgo}`,
    at: new Date(now - minutesAgo * minute).toISOString(),
    username,
    userId,
    result,
    ip,
    userAgent:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'
  }
}

const risk: RiskItem[] = [
  {
    key: 'disk',
    label: '磁盘',
    level: 'OK',
    value: '62.3%',
    detail: '可用 148.2 GB / 共 393.1 GB（D:\\IdeaProjects\\ai-plantform\\data）',
    hint: null
  },
  {
    key: 'heap',
    label: '内存（JVM 堆）',
    level: 'OK',
    value: '41.8%',
    detail: '已用 1712 MB / 上限 4096 MB',
    hint: null
  },
  {
    key: 'slow-answers',
    label: '慢回答（近 24 小时）',
    level: 'WARN',
    value: '7 条',
    detail: '耗时超过 10 秒的回答',
    hint: '先看重排窗口（默认 10）与检索模式：重排窗口 10 在全量读数里是 8.5 秒'
  },
  {
    key: 'failed-answers',
    label: '失败回答（近 24 小时）',
    level: 'OK',
    value: '0 条',
    detail: '状态不是「成功」的回答',
    hint: null
  }
]

const errorLogs: ErrorLogEntry[] = [
  {
    source: 'DOCUMENT' as ErrorSource,
    at: new Date(now - 18 * minute).toISOString(),
    title: 'structured.docx',
    reason: '向量维度与模型登记不符：模型「bge-m3」（74b51c54-…）登记 768 维，这一批返回的是 1024 维',
    hint: '向量按维度分表存放（vec_embedding_<维度>），写进另一张表之后这批向量与已有向量永远互相查不到',
    ref: '9f1c2a5e-3b47-4c88-9d10-6a2f0e5b7c31',
    rowId: '9f1c2a5e-3b47-4c88-9d10-6a2f0e5b7c31'
  },
  {
    source: 'CHAT' as ErrorSource,
    at: new Date(now - 3 * 60 * minute).toISOString(),
    title: '回答失败',
    reason: '上游模型超时（60s）',
    hint: '检查模型服务商连通性；连续出现时先看上游配额',
    ref: 'c9471e4a-bcc3-4851-b1a4-cf1aa7763b70',
    rowId: 'm-7781'
  }
]

export const opsZoneRoutes: MockRoute[] = [
  [
    'GET',
    '/login-logs',
    (ctx) => {
      const days = Number(ctx.query.days ?? 7)
      const keyword = String(ctx.query.keyword ?? '').trim().toLowerCase()
      const result = String(ctx.query.result ?? '').trim().toUpperCase()
      const page = Math.max(1, Number(ctx.query.page ?? 1))
      const size = Math.max(1, Number(ctx.query.size ?? 20))
      const since = now - days * 24 * 60 * minute
      const filtered = loginLogs.filter((one) => {
        if (new Date(one.at).getTime() < since) return false
        if (result && one.result !== result) return false
        if (keyword && !one.username.toLowerCase().includes(keyword)) return false
        return true
      })
      const start = (page - 1) * size
      return {
        list: filtered.slice(start, start + size),
        total: filtered.length,
        counts: countResults(loginLogs.filter((one) => new Date(one.at).getTime() >= since))
      }
    }
  ],
  ['GET', '/ops-zone/risk', () => ({ at: new Date().toISOString(), items: risk })],
  [
    'GET',
    '/ops-zone/error-logs',
    (ctx) => {
      const days = Number(ctx.query.days ?? 7)
      const source = String(ctx.query.source ?? '').trim().toUpperCase()
      const page = Math.max(1, Number(ctx.query.page ?? 1))
      const size = Math.max(1, Number(ctx.query.size ?? 20))
      const since = now - days * 24 * 60 * minute
      const filtered = errorLogs.filter((one) => {
        if (new Date(one.at).getTime() < since) return false
        if (source && one.source !== source) return false
        return true
      })
      const start = (page - 1) * size
      return {
        list: filtered.slice(start, start + size),
        total: filtered.length,
        bySource: countSources(filtered)
      }
    }
  ]
]

/** 按结果计数。★ 与下面的 bySource 分开写：并成一个泛型函数就得用 `as never` 糊类型，读的人反而更慢。 */
function countResults(rows: LoginLogEntry[]): { result: LoginResult; count: number }[] {
  const buckets = new Map<LoginResult, number>()
  for (const row of rows) {
    buckets.set(row.result, (buckets.get(row.result) ?? 0) + 1)
  }
  return [...buckets.entries()]
    .map(([result, count]) => ({ result, count }))
    .sort((a, b) => b.count - a.count)
}

/** 按来源计数（口径 = 当前筛选后的那批）。 */
function countSources(rows: ErrorLogEntry[]): { source: ErrorSource; count: number }[] {
  const buckets = new Map<ErrorSource, number>()
  for (const row of rows) {
    buckets.set(row.source, (buckets.get(row.source) ?? 0) + 1)
  }
  return [...buckets.entries()]
    .map(([source, count]) => ({ source, count }))
    .sort((a, b) => b.count - a.count)
}
