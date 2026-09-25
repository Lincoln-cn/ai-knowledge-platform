import { ApiError } from '@/api/types'
import type { HttpMethod, RequestOptions } from '@/lib/http'

export interface MockContext {
  /** 路径占位参数，如 /knowledge-bases/:id → { id: 'kb1' } */
  path: Record<string, string>
  query: Record<string, unknown>
  body: unknown
  /**
   * 当前令牌。真实后端从 Authorization 头识别用户，
   * mock 也必须这样做 —— 否则刷新页面就「丢失登录态」，与真实行为不一致。
   */
  token: string
}

export type MockHandler = (ctx: MockContext) => unknown | Promise<unknown>

export type MockRoute = [method: HttpMethod, pattern: string, handler: MockHandler]

/**
 * 自动发现 `handlers/` 下所有导出的 `*Routes` 数组。
 *
 * 为什么不用集中 import + ROUTES 数组：那是「导入却忘了挂上」这类漏的温床
 * （文件在、类型对、页面能打开，但每次请求都 404）。改成按文件发现后，
 * 新增领域只加一个文件，不需要改本文件 —— 也不会有多个模块并行开发时
 * 争抢同一个注册表的问题。
 */
const discovered = import.meta.glob<Record<string, unknown>>('./handlers/*.ts', { eager: true })

function collectRoutes(): MockRoute[] {
  const routes: MockRoute[] = []
  for (const mod of Object.values(discovered)) {
    for (const [name, value] of Object.entries(mod)) {
      if (!name.endsWith('Routes') || !Array.isArray(value)) continue
      routes.push(...(value as MockRoute[]))
    }
  }
  return routes
}

const ROUTES: MockRoute[] = collectRoutes()

/** 模拟网络延迟，让骨架屏与 loading 态真的能被看到 */
const LATENCY_MS = 180

function match(pattern: string, actual: string): Record<string, string> | null {
  const p = pattern.split('/').filter(Boolean)
  const a = actual.split('/').filter(Boolean)
  if (p.length !== a.length) return null

  const params: Record<string, string> = {}
  for (let i = 0; i < p.length; i += 1) {
    const seg = p[i]
    if (seg.startsWith(':')) {
      params[seg.slice(1)] = decodeURIComponent(a[i])
    } else if (seg !== a[i]) {
      return null
    }
  }
  return params
}

/**
 * mock 传输层。
 *
 * 与真实后端是同一份契约的两种实现：本文件只负责「按 method + path 找到处理器」，
 * 业务数据在 handlers/ 与 fixtures/ 里。命中不到路由时抛 404 而不是静默返回 undefined，
 * 避免把「接口没实现」伪装成「接口返回空」。
 */
export async function dispatchMock<T>(options: RequestOptions): Promise<T> {
  const method = options.method ?? 'GET'
  const url = options.url.split('?')[0]

  for (const [routeMethod, pattern, handler] of ROUTES) {
    if (routeMethod !== method) continue
    const params = match(pattern, url)
    if (!params) continue

    if (LATENCY_MS) await new Promise((resolve) => setTimeout(resolve, LATENCY_MS))
    const data = await handler({
      path: params,
      query: (options.query ?? {}) as Record<string, unknown>,
      body: options.body,
      token: options.token ?? ''
    })
    return data as T
  }

  throw new ApiError(404, `mock 未实现该接口：${method} ${url}`)
}

/** 供 handlers 复用的分页切片 */
export function paginate<T>(list: T[], query: Record<string, unknown>) {
  const page = Number(query.page ?? 1)
  const size = Number(query.size ?? 20)
  const start = (page - 1) * size
  return { list: list.slice(start, start + size), total: list.length, page, size }
}
