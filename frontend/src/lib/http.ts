import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { API_PREFIX, USE_MOCK } from '@/config/env'
import { ApiError, ErrorCode, type ApiResult } from '@/api/types'
import { clearTokens, getToken, getRefreshToken, setTokens } from '@/lib/token'
import { dispatchMock } from '@/mock/dispatch'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface RequestOptions {
  method?: HttpMethod
  /** 相对 API_PREFIX 的路径，支持 :param 占位，如 '/knowledge-bases/:id/documents' */
  url: string
  /** 路径占位参数 */
  path?: Record<string, string | number>
  /** 查询参数。允许直接传具体 interface（如 ModelQuery），不必为类型加索引签名 */
  query?: object
  body?: unknown
  /** 登录与刷新令牌接口自身调用时置 true，避免 401 递归刷新 */
  skipAuthRetry?: boolean
  /**
   * 成功包络里的可读 hint（如空目录时的排查提示）。
   * ★ 成功响应也可能带 hint —— 只在错误分支读它会漏掉「200 + 空列表 + hint」这种形状。
   */
  onHint?: (hint?: string) => void
  /**
   * 仅 mock 传输层使用。真实实现从 Authorization 头取令牌（见下方拦截器），
   * mock 拿到它才能像真实后端一样按令牌识别用户。
   */
  token?: string
}

/** 把 /knowledge-bases/:id 与 { id } 合成 /knowledge-bases/kb1 */
export function resolvePath(url: string, path?: Record<string, string | number>): string {
  if (!path) return url
  return url.replace(/:([a-zA-Z_]+)/g, (raw, key: string) =>
    key in path ? encodeURIComponent(String(path[key])) : raw
  )
}

const client = axios.create({
  baseURL: API_PREFIX,
  timeout: 60_000,
  headers: { 'Content-Type': 'application/json' }
})

client.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

/** 刷新令牌只允许并发一次，其余请求排队等结果 —— 避免刷新风暴 */
let refreshing: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  if (!refreshing) {
    refreshing = axios
      .post<ApiResult<{ accessToken: string; refreshToken: string }>>(
        `${API_PREFIX}/auth/refresh`,
        { refreshToken: getRefreshToken() }
      )
      .then((res) => {
        const { accessToken, refreshToken } = res.data.data
        setTokens(accessToken, refreshToken)
        return accessToken
      })
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

client.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResult<unknown>
    // 非包体（如文件流）直接透传
    if (!payload || typeof payload !== 'object' || !('code' in payload)) return response
    if (payload.code !== ErrorCode.OK) {
      // ★ hint 必须透传 —— 后端包络里带了可行动的排查提示，丢了就只剩干巴巴的 message
      return Promise.reject(new ApiError(payload.code, payload.message, payload.hint))
    }
    return response
  },
  async (error: AxiosError<ApiResult<unknown>>) => {
    const config = error.config as AxiosRequestConfig & { _retried?: boolean; skipAuthRetry?: boolean }
    const status = error.response?.status

    // 401 静默刷新一次；失败即清登录态，交给路由守卫跳登录
    if (status === 401 && config && !config._retried && !config.skipAuthRetry) {
      config._retried = true
      try {
        const token = await refreshAccessToken()
        config.headers = { ...config.headers, Authorization: `Bearer ${token}` }
        return client.request(config)
      } catch {
        clearTokens()
        return Promise.reject(new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录'))
      }
    }

    const body = error.response?.data
    // 无业务包体时（后端未通、网关 502、路由不存在），错误必须可行动，
    // 不能只把 axios 的 "status code 501" 抛给用户
    const fallback = status
      ? `接口返回 HTTP ${status}${
          status === 404 || status === 501
            ? '（接口不存在，请检查 VITE_API_BASE 与后端路由）'
            : '（后端异常，请稍后重试）'
        }`
      : '无法连接后端（请检查网络与后端是否启动）'

    return Promise.reject(
      new ApiError(body?.code ?? status ?? -1, body?.message || fallback, body?.hint)
    )
  }
)

function viaHttp<T>(options: RequestOptions): Promise<T> {
  const { method = 'GET', url, path, query, body, skipAuthRetry, onHint } = options
  const config: AxiosRequestConfig & { skipAuthRetry?: boolean } = {
    method,
    url: resolvePath(url, path),
    params: query,
    data: body,
    skipAuthRetry
  }
  return client.request<ApiResult<T>>(config).then((res) => {
    // 成功包络也可能带 hint（如「空目录 + 为什么空」）—— 不能只在错误分支读
    onHint?.(res.data.hint)
    return res.data.data
  })
}

/**
 * 全平台唯一的请求出口。
 *
 * mock 与真实后端是同一契约的两种实现：USE_MOCK 决定走哪条，
 * 调用方（src/api/**）与页面代码完全无感。
 */
export function request<T>(options: RequestOptions): Promise<T> {
  const resolved: RequestOptions = {
    ...options,
    url: resolvePath(options.url, options.path),
    token: getToken()
  }
  return USE_MOCK ? dispatchMock<T>(resolved) : viaHttp<T>(options)
}
