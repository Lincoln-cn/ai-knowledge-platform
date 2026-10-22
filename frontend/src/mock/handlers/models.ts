import { ApiError, ErrorCode } from '@/api/types'
import type { BaseModelOption, ModelCapability, ModelInfo, UpsertModelBody } from '@/api/models'
import type { ProbeBaseModelBody } from '@/api/models'
import type { MockRoute } from '../dispatch'
import { MockEnvelope, paginate } from '../dispatch'
import { baseModels, externalBaseModels, models, probedCapabilities } from '../fixtures/models'

/** 加载是异步的：先置「加载中」，若干秒后转「已加载」。与真实后端行为一致 */
const LOAD_MS = 4000

const findModel = (id: string) => {
  const model = models.find((m) => m.id === id)
  if (!model) throw new ApiError(ErrorCode.NOT_FOUND, '模型不存在')
  return model
}

/** 两套上游目录合成一个查找表（指定 apiBase 时模型在外套里，不指定在默认套里） */
const allBaseModels = (): BaseModelOption[] => [...baseModels, ...externalBaseModels]

const findBaseModel = (name: string) => allBaseModels().find((b) => b.baseModel === name)

/** mock 侧只认识预置的 JAiRouter；新建服务商后模型卡片显示 id 兜底 */
const providerNameOf = (id: string) =>
  id === 'provider-jairouter' ? 'JAiRouter（本机）' : id

/** 本地 ONNX（进程内）状态；warmup 后置 ready，供管理页联调 #191 */
let localOnnxReady = false
const localOnnxStatus = () => ({
  enabled: true,
  mode: 'local-onnx',
  model: 'multilingual-e5-small',
  modelPath: 'models/multilingual-e5-small/model_quantized.onnx',
  tokenizerPath: 'models/multilingual-e5-small/tokenizer.json',
  filesPresent: true,
  ready: localOnnxReady,
  reason: localOnnxReady ? null : '尚未使用过（懒加载未触发）'
})

export const modelRoutes: MockRoute[] = [
  [
    'GET',
    '/models',
    ({ query }) => {
      const keyword = String(query.keyword ?? '')
      const kind = query.kind ? String(query.kind) : ''
      const status = query.status ? String(query.status) : ''

      const list = models
        .filter((m) => !keyword || m.name.includes(keyword) || m.baseModel.includes(keyword))
        .filter((m) => !kind || m.kind === kind)
        .filter((m) => !status || m.status === status)

      return paginate(list, query)
    }
  ],

  ['GET', '/models/local-onnx', () => localOnnxStatus()],

  [
    'POST',
    '/models/local-onnx/warmup',
    () => {
      localOnnxReady = true
      return localOnnxStatus()
    }
  ],

  [
    // ★ 契约已由 GET 改成 POST（apiKey 不能进查询串）；入参走 body，按 apiBase 分支上游
    'POST',
    '/models/base-models',
    ({ body }) => {
      const input = (body ?? {}) as {
        supplier?: string
        kind?: string
        keyword?: string
        apiBase?: string
        apiKey?: string
      }
      const supplierRaw = input.supplier ? String(input.supplier) : ''
      const kind = input.kind ? String(input.kind) : ''
      const keyword = String(input.keyword ?? '')
      const apiBase = String(input.apiBase ?? '').trim()

      // 模拟「上游不可达」：空列表 + 可读 hint（与后端 BaseModelCatalog.empty 同形状）
      if (apiBase && /dead|unreachable|127\.0\.0\.1:1/i.test(apiBase)) {
        return new MockEnvelope(
          [] as BaseModelOption[],
          '未从上游拉到模型：请检查服务地址是否正确、服务是否已启动'
        )
      }

      // ★ 目录跟着上游走：给了 apiBase 就拉那一套（外部 API），没给走默认（本地推理）
      const catalog = apiBase ? externalBaseModels : baseModels

      // 与后端 toBaseOption 同口径：supplier 由用户选定后**盖**到每一条上，不是拿来过滤的
      const resolvedSupplier = (supplierRaw || 'LOCAL_INFERENCE') as BaseModelOption['supplier']

      const list = catalog
        .map((b) => ({ ...b, supplier: resolvedSupplier }))
        .filter((b) => !kind || b.kind === kind)
        .filter((b) => !keyword || b.baseModel.toLowerCase().includes(keyword.toLowerCase()))

      if (!catalog.length) {
        return new MockEnvelope(
          [] as BaseModelOption[],
          '上游返回了空的模型清单：请确认服务上已部署模型'
        )
      }
      return list
    }
  ],

  [
    'POST',
    '/models/probe',
    ({ body }) => {
      const input = body as ProbeBaseModelBody
      const base = findBaseModel(input.baseModel)
      if (!base) throw new ApiError(ErrorCode.BAD_REQUEST, `基础模型「${input.baseModel}」不在可选目录中`)

      const probed = probedCapabilities[input.baseModel]
      // 探测不到时明确告知并让用户手工声明 —— 不静默塞一个默认值
      if (!probed) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `无法自动探测「${input.baseModel}」的能力`,
          '该基础模型不在探测支持列表内，请手工声明思考模式与工具调用能力'
        )
      }

      const capability: ModelCapability = {
        thinking: input.kind === 'CHAT' ? probed.thinking : 'NONE',
        tools: input.kind === 'CHAT' ? probed.tools : 'UNSUPPORTED',
        contextWindow: base.contextWindow,
        dimension: base.dimension,
        source: 'PROBED'
      }
      return capability
    }
  ],

  [
    'POST',
    '/models',
    ({ body }) => {
      const input = body as UpsertModelBody
      if (models.some((m) => m.name === input.name)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `模型名称「${input.name}」已存在`)
      }

      // 外部 API 必须带 apiBase —— 绑定服务商时由服务商持有地址，不再要求表单填
      if (input.supplier === 'EXTERNAL_API' && !input.providerId && !input.apiBase?.trim()) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          '外部 API 模型必须提供 apiBase',
          '外部 API 的推理服务地址是必填项 —— 本地推理才可以留空（用默认上游）'
        )
      }

      // ★ 与后端一致：baseModel 不校验，真伪留到 load。
      //   后端 create 把它当字符串直接存，load 时才对上游清单验真伪；
      //   mock 若拒「不在可选目录中」，会掩盖真后端的宽容行为。
      //   contextWindow / dimension 能从目录带出就带出，带不出就 null ——
      //   与后端 lookupContextWindow 的 best-effort 同口径（拉不到不阻断注册）。
      const base = findBaseModel(input.baseModel)

      // 不适用的字段一律拒绝，不静默丢 —— 与后端 create/update 同口径
      if (input.kind === 'CHAT' && input.dimension != null) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '对话模型不产出向量，dimension 必须为空')
      }
      if (input.kind === 'EMBEDDING' && (input.thinking || input.tools)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '能力声明只对对话模型有意义：向量模型不接受 thinking / tools')
      }
      if (input.kind === 'EMBEDDING' && !input.dimension) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '向量模型必须显式声明向量维度 —— 维度与向量表绑定，不能靠猜')
      }

      const created: ModelInfo = {
        id: `m${Date.now()}`,
        name: input.name,
        kind: input.kind,
        supplier: input.supplier,
        baseModel: input.baseModel,
        status: 'NOT_LOADED',
        contextWindow: input.kind === 'CHAT' ? (base?.contextWindow ?? null) : null,
        dimension: input.kind === 'EMBEDDING' ? (input.dimension ?? base?.dimension ?? null) : null,
        thinking: input.kind === 'CHAT' ? (input.thinking ?? 'NONE') : 'NONE',
        tools: input.kind === 'CHAT' ? (input.tools ?? 'UNSUPPORTED') : 'UNSUPPORTED',
        builtin: false,
        remark: input.remark,
        providerId: input.providerId ?? null,
        providerName: input.providerId ? providerNameOf(input.providerId) : undefined,
        legacyDirect: !input.providerId
      }
      models.unshift(created)
      return created
    }
  ],

  [
    'PUT',
    '/models/:id',
    ({ path, body }) => {
      const model = findModel(path.id)
      const input = body as Partial<UpsertModelBody>

      if (input.name && input.name !== model.name && models.some((m) => m.name === input.name)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `模型名称「${input.name}」已存在`)
      }
      // 基础模型不允许改：改它等于换了一个模型，应新建
      if (input.baseModel && input.baseModel !== model.baseModel) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '基础模型不可修改；如需更换请新建模型')
      }
      if (model.kind === 'EMBEDDING' && input.dimension && input.dimension !== model.dimension) {
        throw new ApiError(
          ErrorCode.EMBEDDING_LOCKED,
          '向量维度不可修改 —— 维度与向量表绑定，改则需重建向量表并全量重灌'
        )
      }
      // 不适用的字段一律拒绝，不静默丢 —— 与后端 create/update 同口径
      if (model.kind === 'CHAT' && input.dimension != null) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '对话模型不产出向量，dimension 必须为空')
      }
      if (model.kind === 'EMBEDDING' && (input.thinking || input.tools)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '能力声明只对对话模型有意义：向量模型不接受 thinking / tools')
      }

      if (input.name) model.name = input.name
      if (input.remark !== undefined) model.remark = input.remark
      if (input.providerId !== undefined) {
        model.providerId = input.providerId || null
        model.providerName = input.providerId ? providerNameOf(input.providerId) : undefined
        model.legacyDirect = !input.providerId
      }
      if (model.kind === 'CHAT') {
        if (input.thinking) model.thinking = input.thinking
        if (input.tools) model.tools = input.tools
      }
      return model
    }
  ],

  [
    'DELETE',
    '/models/:id',
    ({ path }) => {
      const model = findModel(path.id)
      if (model.builtin) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `「${model.name}」是内置模型，不可删除`)
      }

      // 与后端当前行为一致：`ModelService.delete` 的「被应用/知识库引用」检查只是钩子，
      // 后端还没有引用方查询；mock 若单方面拒绝，会让「mock 点不动、真栈能删」重演（#22）。
      // 等后端补上引用计数后，两边同批加回。
      models.splice(models.indexOf(model), 1)
      return undefined
    }
  ],

  [
    'POST',
    '/models/:id/load',
    ({ path }) => {
      const model = findModel(path.id)
      // 与后端 `ModelService.load` 一致：任意态都可发起，重复点也返回 200；
      // mock 不再单方面抛「正在加载中」（#22）。
      model.status = 'LOADING'
      // 异步转「已加载」。界面靠刷新/轮询看到变化，与真实后端一致
      setTimeout(() => {
        model.status = 'LOADED'
      }, LOAD_MS)

      return model
    }
  ],

  [
    'POST',
    '/models/:id/probe',
    ({ path }) => {
      const model = findModel(path.id)
      const probed = probedCapabilities[model.baseModel]

      // 探测不到时明确告知，并让用户在界面上手工声明 —— 不静默给个默认值
      if (!probed) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `无法自动探测「${model.baseModel}」的能力`,
          '该基础模型不在探测支持列表内，请在表单中手工声明思考模式与工具调用能力'
        )
      }

      const capability: ModelCapability = {
        thinking: probed.thinking,
        tools: probed.tools,
        contextWindow: model.contextWindow,
        dimension: model.dimension,
        source: 'PROBED'
      }
      return capability
    }
  ]
]
