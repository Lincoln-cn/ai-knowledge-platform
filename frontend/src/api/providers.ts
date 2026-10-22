import { request } from '@/lib/http'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 服务商类型（与后端 ProviderKind 一字不差） */
export type ProviderKind =
  | 'OPENAI'
  | 'DEEPSEEK'
  | 'MIMO'
  | 'GLM'
  | 'OLLAMA'
  | 'GATEWAY'
  | 'OPENAI_COMPATIBLE'

/** 认证头方式：JAiRouter 是 X-API-Key，OpenAI 兼容是 Bearer */
export type AuthStyle = 'BEARER' | 'X_API_KEY' | 'NONE'

/** 是否出网：与厂家名无关（自建 vLLM 也用 OpenAI 协议，但它是 LOCAL） */
export type ProviderEgress = 'LOCAL' | 'EXTERNAL'

export interface ModelProviderInfo {
  id: string
  name: string
  kind: ProviderKind
  apiBase: string
  authStyle: AuthStyle
  egress: ProviderEgress
  enabled: boolean
  /** ★ 只回“有没有”，绝不回明文 */
  hasApiKey: boolean
  remark?: string
  /** 引用它的模型数（删除守卫与界面提示） */
  modelCount: number
}

export interface UpsertProviderBody {
  name: string
  kind?: ProviderKind
  apiBase?: string
  authStyle?: AuthStyle
  egress?: ProviderEgress
  /** 只在创建 / 快速绑定时使用；更新走 setProviderKey */
  apiKey?: string
  remark?: string
}

export interface ProviderTestResult {
  ok: boolean
  modelCount: number
  sampleModels: string[]
  elapsedMs: number
  error?: string
}

export interface QuickBindResult {
  provider: ModelProviderInfo
  created: boolean
  modelCount: number
  sampleModels: string[]
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listProviders = () =>
  request<ModelProviderInfo[]>({ method: 'GET', url: '/model-providers' })

export const getProvider = (id: string) =>
  request<ModelProviderInfo>({ method: 'GET', url: '/model-providers/:id', path: { id } })

export const createProvider = (body: UpsertProviderBody) =>
  request<ModelProviderInfo>({ method: 'POST', url: '/model-providers', body })

export const updateProvider = (id: string, body: UpsertProviderBody) =>
  request<ModelProviderInfo>({
    method: 'PUT',
    url: '/model-providers/:id',
    path: { id },
    body
  })

export const setProviderEnabled = (id: string, enabled: boolean) =>
  request<ModelProviderInfo>({
    method: 'PATCH',
    url: '/model-providers/:id/enabled',
    path: { id },
    body: { enabled }
  })

export const setProviderKey = (id: string, apiKey: string) =>
  request<ModelProviderInfo>({
    method: 'POST',
    url: '/model-providers/:id/key',
    path: { id },
    body: { apiKey }
  })

export const clearProviderKey = (id: string) =>
  request<ModelProviderInfo>({
    method: 'DELETE',
    url: '/model-providers/:id/key',
    path: { id }
  })

export const testProvider = (id: string) =>
  request<ProviderTestResult>({
    method: 'POST',
    url: '/model-providers/:id/test',
    path: { id }
  })

/** 一键绑定 JAiRouter：两个字段都可空（地址走默认，key 看本地网关是否校验） */
export const quickBindJairouter = (body: { apiBase?: string; apiKey?: string } = {}) =>
  request<QuickBindResult>({ method: 'POST', url: '/model-providers/quick-bind', body })

export const deleteProvider = (id: string) =>
  request<void>({ method: 'DELETE', url: '/model-providers/:id', path: { id } })
