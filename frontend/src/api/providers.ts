import { request } from '@/lib/http'
// ★ 类型只在编译期存在（`import type`），不产生运行时依赖：models.ts 也 type-import 了本文件的 ProviderKind，
//   两个方向都是类型 ⇒ 没有循环 require 的问题。
import type { ModelReference } from './models'

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

/**
 * 删除服务商（`#300`：先查后删）。
 *
 * ★ 下面还有模型时后端返回 **409**，`ApiError.details` 是引用面（模型数 + 模型名 + 这些模型各自被谁引用）。
 * ★ `force=true` = 连模型一起删；但若这些模型**自己**还被应用/知识库/平台默认键用着，后端**仍然 409**
 * —— 点"删服务商"不该把别人的应用弄坏。
 *
 * @param id 服务商 id
 * @param force 明知它下面还有模型仍要删（一并删模型）
 */
export const deleteProvider = (id: string, force = false) =>
  request<void>({
    method: 'DELETE',
    url: '/model-providers/:id',
    path: { id },
    query: force ? { force: true } : undefined
  })

/** 读服务商的引用面（`#300`）：它下面有哪些模型、这些模型被谁用着。 */
export const getProviderReferences = (id: string) =>
  request<ModelReference>({ method: 'GET', url: '/model-providers/:id/references', path: { id } })
