// ★ 相对路径 + .ts 后缀：node:test（--experimental-strip-types）不解析 `@/` 别名
//   也不做无扩展名补全；vite / vue-tsc 两者都吃（tsconfig 已开 allowImportingTsExtensions）。
import { ApiError } from '../api/types.ts'
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

/**
 * 带包络 hint 的 mock 结果。
 *
 * 真实后端的 `ApiResponse` 在**成功**时也可能带 hint（如「空目录 + 为什么空」），
 * mock 不表达这一形状就会让「界面显示 hint」这条路径在 mock 模式下永远走不到。
 */
export class MockEnvelope<T> {
  constructor(
    readonly data: T,
    readonly hint?: string
  ) {}
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
 *
 * ★★★ 这一行**必须**写成 `import.meta.glob(<字面量>)(...)` 的直接调用形式。
 *   它是 vite 的**编译期宏**：vite 在静态分析阶段把这一处替换成"文件路径 → 模块"的映射。
 *   一旦改成间接调用（`const f = import.meta.glob; f('./handlers/*.ts', …)`），
 *   静态分析认不出这是宏 ⇒ **不替换** ⇒ 运行时拿到的是未定义的函数 ⇒ 路由表为空
 *   ⇒ **所有接口都变成「mock 未实现该接口」**（页面能打开、登录点不动、12 个浏览器闸门全红）。
 *   2026-11-05 真的这么坏过一次（`#301` 为了照顾 node:test 把直接调用改成了间接调用）。
 *
 * ★ 但它**不能放在模块顶层执行**：`handlers/*.ts` 自己会 `import { MockEnvelope, paginate } from '../dispatch.ts'`
 *   （`handlers/models.ts`、`handlers/providers.ts` 都这么写），于是 `node --test` 加载任一 handler
 *   都会连带加载本文件 —— 那个环境里 `import.meta.glob` 是 `undefined`，顶层直接调用会抛
 *   `TypeError: (intermediate value).glob is not a function`（2026-11-05 真坏过一次）。
 *   ⇒ 包进 `discovered()` 惰性求值：vite 里第一次 dispatch 时才展开（宏照样能替换，它是 AST 级的、
 *   与位置无关）；`node --test` 里没人调用 `discovered()`，那句永不执行。
 */
let discoveredCache: Record<string, Record<string, unknown>> | null = null

function discovered(): Record<string, Record<string, unknown>> {
  if (discoveredCache === null) {
    discoveredCache = import.meta.glob<Record<string, unknown>>('./handlers/*.ts', { eager: true })
  }
  return discoveredCache
}

function collectRoutes(): MockRoute[] {
  const routes: MockRoute[] = []
  for (const mod of Object.values(discovered())) {
    for (const [name, value] of Object.entries(mod)) {
      if (!name.endsWith('Routes') || !Array.isArray(value)) continue
      routes.push(...(value as MockRoute[]))
    }
  }
  return routes
}

// ★ 路由表也必须惰性求值：它在模块顶层调用 collectRoutes() → discovered() → import.meta.glob，
//   于是「加载本文件」就等于「执行那个宏」。node --test 里加载 handlers/*.ts 会连带加载本文件，
//   而那个环境没有这个宏 ⇒ 必须等到真的 dispatch 时才建表。
let routesCache: MockRoute[] | null = null

function routes(): MockRoute[] {
  if (routesCache === null) {
    routesCache = collectRoutes()
  }
  return routesCache
}

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

  for (const [routeMethod, pattern, handler] of routes()) {
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
    // 带 hint 的成功包络：把 hint 交出去，数据本体照常返回
    if (data instanceof MockEnvelope) {
      options.onHint?.(data.hint)
      return data.data as T
    }
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

