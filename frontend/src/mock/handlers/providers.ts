import { ApiError, ErrorCode } from '@/api/types'
import type { ModelProviderInfo, ProviderKind } from '@/api/providers'
import type { MockRoute } from '../dispatch'
import { models } from '../fixtures/models'

interface MockProvider extends ModelProviderInfo {
  apiKey?: string
}

let seq = 0
const providers: MockProvider[] = [
  {
    id: 'provider-jairouter',
    name: 'JAiRouter（本机）',
    kind: 'GATEWAY',
    apiBase: 'http://localhost:8080/v1',
    authStyle: 'X_API_KEY',
    egress: 'EXTERNAL',
    enabled: true,
    hasApiKey: false,
    remark: '本地网关；是否出网取决于网关后面的真实上游',
    modelCount: 0
  }
]

const toPublic = (p: MockProvider): ModelProviderInfo => {
  const count = models.filter((m) => m.providerId === p.id).length
  return { ...p, modelCount: count, hasApiKey: !!p.apiKey }
}

export const providerRoutes: MockRoute[] = [
  [
    'GET',
    '/model-providers',
    () => providers.map(toPublic)
  ],

  [
    // ★ 必须排在 /:id 之前，否则 quick-bind 会被当作 id
    'POST',
    '/model-providers/quick-bind',
    ({ body }) => {
      const input = (body ?? {}) as { apiBase?: string; apiKey?: string }
      const apiBase = String(input.apiBase ?? '').trim() || 'http://localhost:8080/v1'
      const existing = providers.find((p) => p.kind === 'GATEWAY' && p.apiBase === apiBase)
      if (existing) {
        if (input.apiKey) existing.apiKey = input.apiKey
        return {
          provider: toPublic(existing),
          created: false,
          modelCount: 4,
          sampleModels: ['deepseek-chat', 'qwen2.5-7b-instruct', 'bge-m3', 'text-embedding-3-small']
        }
      }
      if (/dead|unreachable|127\.0\.0\.1:1/i.test(apiBase)) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          '网关探活失败',
          '请确认 JAiRouter 已启动，且地址填到 /v1 这一层'
        )
      }
      const created: MockProvider = {
        id: `provider-${++seq}`,
        name: 'JAiRouter（本机）',
        kind: 'GATEWAY',
        apiBase,
        authStyle: 'X_API_KEY',
        egress: 'EXTERNAL',
        enabled: true,
        hasApiKey: !!input.apiKey,
        apiKey: input.apiKey,
        remark: '一键绑定',
        modelCount: 0
      }
      providers.push(created)
      return {
        provider: toPublic(created),
        created: true,
        modelCount: 4,
        sampleModels: ['deepseek-chat', 'qwen2.5-7b-instruct', 'bge-m3', 'text-embedding-3-small']
      }
    }
  ],

  [
    'POST',
    '/model-providers',
    ({ body }) => {
      const input = (body ?? {}) as {
        name?: string
        kind?: ProviderKind
        apiBase?: string
        authStyle?: ModelProviderInfo['authStyle']
        egress?: ModelProviderInfo['egress']
        apiKey?: string
        remark?: string
      }
      if (!input.name?.trim()) throw new ApiError(ErrorCode.BAD_REQUEST, '服务商名称不能为空')
      if (providers.some((p) => p.name === input.name!.trim())) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `服务商名称「${input.name}」已存在`)
      }
      const created: MockProvider = {
        id: `provider-${++seq}`,
        name: input.name.trim(),
        kind: input.kind ?? 'OPENAI',
        apiBase: input.apiBase?.trim() ?? '',
        authStyle: input.authStyle ?? 'BEARER',
        egress: input.egress ?? 'EXTERNAL',
        enabled: true,
        hasApiKey: !!input.apiKey,
        apiKey: input.apiKey,
        remark: input.remark,
        modelCount: 0
      }
      providers.push(created)
      return toPublic(created)
    }
  ],

  [
    'GET',
    '/model-providers/:id',
    ({ path }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      return toPublic(p)
    }
  ],

  [
    'PUT',
    '/model-providers/:id',
    ({ path, body }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      const input = (body ?? {}) as Record<string, unknown>
      if (typeof input.name === 'string') p.name = input.name
      if (typeof input.apiBase === 'string') p.apiBase = input.apiBase
      if (typeof input.apiKey === 'string' && input.apiKey) p.apiKey = input.apiKey
      if (typeof input.remark === 'string') p.remark = input.remark
      if (typeof input.kind === 'string') p.kind = input.kind as ProviderKind
      if (typeof input.authStyle === 'string') p.authStyle = input.authStyle as ModelProviderInfo['authStyle']
      if (typeof input.egress === 'string') p.egress = input.egress as ModelProviderInfo['egress']
      return toPublic(p)
    }
  ],

  [
    'PATCH',
    '/model-providers/:id/enabled',
    ({ path, body }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      p.enabled = !!(body as { enabled?: boolean })?.enabled
      return toPublic(p)
    }
  ],

  [
    'POST',
    '/model-providers/:id/key',
    ({ path, body }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      const key = String((body as { apiKey?: string })?.apiKey ?? '').trim()
      if (!key) throw new ApiError(ErrorCode.BAD_REQUEST, '密钥不能为空')
      p.apiKey = key
      return toPublic(p)
    }
  ],

  [
    'DELETE',
    '/model-providers/:id/key',
    ({ path }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      p.apiKey = undefined
      return toPublic(p)
    }
  ],

  [
    'POST',
    '/model-providers/:id/test',
    ({ path }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      if (/dead|unreachable|127\.0\.0\.1:1/i.test(p.apiBase)) {
        return { ok: false, modelCount: 0, sampleModels: [], elapsedMs: 32, error: '连接被拒绝' }
      }
      return {
        ok: true,
        modelCount: 4,
        sampleModels: ['deepseek-chat', 'qwen2.5-7b-instruct', 'bge-m3'],
        elapsedMs: 18
      }
    }
  ],

  [
    'DELETE',
    '/model-providers/:id',
    ({ path }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      const used = models.filter((m) => m.providerId === p.id).length
      if (used > 0) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `该服务商还有 ${used} 个模型引用，不能删除`,
          '先把这些模型改绑到别的服务商，或删除模型，再删服务商'
        )
      }
      const idx = providers.indexOf(p)
      providers.splice(idx, 1)
      return undefined
    }
  ]
]
