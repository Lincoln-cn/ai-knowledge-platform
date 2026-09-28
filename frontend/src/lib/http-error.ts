/**
 * HTTP 层错误文案的纯逻辑 —— 从 http.ts 拦截器拆出，便于单测。
 *
 * ★ 无业务包络时的文案必须指向「后端未起 / 被代理拦下」，不能说成「后端异常」：
 *   dev 代理在上游不可达时也会回一个 500（vite 的 http proxy error），
 *   真实原因是「后端进程没了」，说成「后端异常」会把排查引向「代码崩了」。
 */

/** 我们的业务包络：必须同时有 code（number）与 message（string）。缺一或非 JSON 都不算 */
export function isApiEnvelope(
  body: unknown
): body is { code: number; message: string; hint?: string } {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return false
  const b = body as { code?: unknown; message?: unknown }
  return typeof b.code === 'number' && typeof b.message === 'string'
}

/** 无业务包络时的兜底文案（有包络时用后端的 message + hint，不用这里的） */
export function httpFallbackMessage(status?: number): string {
  if (!status) return '无法连接后端（请检查网络与后端是否启动）'
  if (status === 404 || status === 501) {
    return `接口返回 HTTP ${status}（接口不存在，请检查 VITE_API_BASE 与后端路由）`
  }
  return `未得到后端的正常响应（后端可能未启动，或被代理/网关拦下）：HTTP ${status}`
}
