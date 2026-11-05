// ★ 运行时 import 用相对路径 + .ts 后缀（node:test 不解析 `@/` 别名，见 dispatch.ts 头注）
import { ApiError, ErrorCode } from '../../api/types.ts'
import type { ModelProviderInfo, ProviderKind } from '@/api/providers'
import type { ModelReference } from '@/api/models'
import { toPublicProvider } from '../../lib/providerPublic.ts'
import type { MockRoute } from '../dispatch.ts'
import { EMPTY_MODEL_REFERENCE, modelReferences, models } from '../fixtures/models.ts'
import { summaryOf } from './models.ts'

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

/**
 * 内部记录 → 公开响应。
 *
 * ★ Issue #264：以前这里是 `{ ...p }`，会把下面的明文 `apiKey` 一起下发。
 *   现在改走 `toPublicProvider`（显式挑字段，白名单），内部字段照旧保留
 *   —— 夹具/种子里的 `apiKey` 仍供 mock 内部模拟调用，只是不出这个门。
 */
const toPublic = (p: MockProvider): ModelProviderInfo =>
  toPublicProvider(p, models.filter((m) => m.providerId === p.id).length)

const modelsOf = (providerId: string) => models.filter((m) => m.providerId === providerId)

/**
 * 服务商的引用面（#301），与后端 `JdbcModelReferencePort.ofProvider` 同形：
 * `models`/`modelNames` = 它下面的模型；apps/kbs/documents/defaultKeys = **这些模型的下游聚合**
 * （语义是"删掉这个服务商会弄坏什么"，不是"谁引用了服务商"）。
 */
const referencesOfProvider = (providerId: string): ModelReference => {
  const owned = modelsOf(providerId)
  const refs: ModelReference = {
    apps: [],
    knowledgeBases: [],
    documents: 0,
    defaultKeys: [],
    models: owned.length,
    // 后端 order by name（MAX_NAMES=20）；mock 数据量小，直接按名排全给
    modelNames: [...owned].map((m) => m.name).sort()
  }
  for (const m of owned) {
    const r = modelReferences[m.id] ?? EMPTY_MODEL_REFERENCE
    for (const item of r.apps) {
      if (!refs.apps.some((x) => x.id === item.id)) refs.apps.push(item)
    }
    for (const item of r.knowledgeBases) {
      if (!refs.knowledgeBases.some((x) => x.id === item.id)) refs.knowledgeBases.push(item)
    }
    refs.documents += r.documents
    for (const key of r.defaultKeys) {
      if (!refs.defaultKeys.includes(key)) refs.defaultKeys.push(key)
    }
  }
  return refs
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
    // ★ #301：与后端 `ModelProviderService.delete` 同形（400 → 409 + 引用面明细 + force 语义）：
    //   mock 与真栈对"删服务商被拒"必须给同一个读数（同族 #277）。
    'DELETE',
    '/model-providers/:id',
    ({ path, query }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      const refs = referencesOfProvider(p.id)
      const force = query.force === true || query.force === 'true'
      if (refs.models > 0 && !force) {
        throw new ApiError(
          ErrorCode.CONFLICT,
          `服务商「${p.name}」下还有 ${refs.models} 个模型，无法删除`,
          '请先把这些模型改绑到其它服务商，或删除它们后再删服务商；确认要连模型一起删，请用「强制删除」',
          refs
        )
      }
      const modelsStillUsed =
        refs.apps.length > 0 || refs.knowledgeBases.length > 0 || refs.documents > 0 || refs.defaultKeys.length > 0
      if (modelsStillUsed) {
        // ★★ force 也拦（与后端同口径）：删服务商会连带删模型，而这些模型正被应用/知识库用着 ——
        //    那是"别人的功能坏了"，不是"你删了一个服务商"。
        throw new ApiError(
          ErrorCode.CONFLICT,
          `服务商「${p.name}」下的 ${refs.models} 个模型仍被引用：${summaryOf(refs)}，不能连它们一起强制删除`,
          '请先把这些应用/知识库/平台默认键改绑到别的模型，再回来删服务商（改绑不是删除，数据不会丢）',
          refs
        )
      }
      // force=true：连模型一起删（后端还留 log.warn 审计；mock 是无状态内存，删干净即可）
      for (const m of modelsOf(p.id)) {
        models.splice(models.indexOf(m), 1)
      }
      providers.splice(providers.indexOf(p), 1)
      return undefined
    }
  ],

  [
    // ★ 与后端 `GET /model-providers/:id/references` 同形：删除弹框在动手指之前就读它
    'GET',
    '/model-providers/:id/references',
    ({ path }) => {
      const p = providers.find((x) => x.id === path.id)
      if (!p) throw new ApiError(ErrorCode.NOT_FOUND, '服务商不存在')
      return referencesOfProvider(p.id)
    }
  ]
]
