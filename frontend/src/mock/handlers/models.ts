import { ApiError, ErrorCode } from '@/api/types'
import type { ModelCapability, ModelInfo, UpsertModelBody } from '@/api/models'
import type { ProbeBaseModelBody } from '@/api/models'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { assistants } from '../fixtures/assistants'
import { knowledgeBases } from '../fixtures/knowledge'
import { baseModels, models, probedCapabilities } from '../fixtures/models'

/** 加载是异步的：先置「加载中」，若干秒后转「已加载」。与真实后端行为一致 */
const LOAD_MS = 4000

const findModel = (id: string) => {
  const model = models.find((m) => m.id === id)
  if (!model) throw new ApiError(ErrorCode.NOT_FOUND, '模型不存在')
  return model
}

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

  [
    'GET',
    '/models/base-models',
    ({ query }) => {
      const supplier = query.supplier ? String(query.supplier) : ''
      const kind = query.kind ? String(query.kind) : ''
      const keyword = String(query.keyword ?? '')

      return baseModels
        .filter((b) => !supplier || b.supplier === supplier)
        .filter((b) => !kind || b.kind === kind)
        .filter((b) => !keyword || b.baseModel.toLowerCase().includes(keyword.toLowerCase()))
    }
  ],

  [
    'POST',
    '/models/probe',
    ({ body }) => {
      const input = body as ProbeBaseModelBody
      const base = baseModels.find((b) => b.baseModel === input.baseModel)
      if (!base) throw new ApiError(4003, `基础模型「${input.baseModel}」不在可选目录中`)

      const probed = probedCapabilities[input.baseModel]
      // 探测不到时明确告知并让用户手工声明 —— 不静默塞一个默认值
      if (!probed) {
        throw new ApiError(
          4009,
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
        throw new ApiError(4002, `模型名称「${input.name}」已存在`)
      }

      const base = baseModels.find((b) => b.baseModel === input.baseModel)
      if (!base) throw new ApiError(4003, `基础模型「${input.baseModel}」不在可选目录中`)

      // 迁移自旧实现的「供应商与平台不匹配」校验：选的基础模型必须属于所选供应商
      if (base.supplier !== input.supplier) {
        throw new ApiError(4003, `基础模型「${base.baseModel}」属于「${base.supplier}」，与所选供应商不一致`)
      }
      // 模型类型必须与基础模型一致，否则向量维度/上下文窗口会张冠李戴
      if (base.kind !== input.kind) {
        throw new ApiError(4003, `基础模型「${base.baseModel}」是${base.kind === 'CHAT' ? '对话' : '向量'}模型，与所选类型不一致`)
      }
      if (input.kind === 'EMBEDDING' && !input.dimension) {
        throw new ApiError(4004, '向量模型必须显式声明向量维度 —— 维度与向量表绑定，不能靠猜')
      }

      const created: ModelInfo = {
        id: `m${Date.now()}`,
        name: input.name,
        kind: input.kind,
        supplier: input.supplier,
        baseModel: input.baseModel,
        status: 'NOT_LOADED',
        contextWindow: base.contextWindow,
        dimension: input.kind === 'EMBEDDING' ? (input.dimension ?? base.dimension) : null,
        thinking: input.kind === 'CHAT' ? (input.thinking ?? 'NONE') : 'NONE',
        tools: input.kind === 'CHAT' ? (input.tools ?? 'UNSUPPORTED') : 'UNSUPPORTED',
        builtin: false,
        remark: input.remark
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
        throw new ApiError(4002, `模型名称「${input.name}」已存在`)
      }
      // 基础模型不允许改：改它等于换了一个模型，应新建
      if (input.baseModel && input.baseModel !== model.baseModel) {
        throw new ApiError(4005, '基础模型不可修改；如需更换请新建模型')
      }
      if (model.kind === 'EMBEDDING' && input.dimension && input.dimension !== model.dimension) {
        throw new ApiError(
          ErrorCode.EMBEDDING_LOCKED,
          '向量维度不可修改 —— 维度与向量表绑定，改则需重建向量表并全量重灌'
        )
      }

      if (input.name) model.name = input.name
      if (input.remark !== undefined) model.remark = input.remark
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
        throw new ApiError(4006, `「${model.name}」是内置模型，不可删除`)
      }

      // 门禁是「被谁引用」，不是「当前加载态」：契约里只有 load 没有 unload，
      // 拿加载态当门禁会让加载过的模型永远删不掉。删除即停用，不必先卸载。
      const appNames = assistants.filter((a) => a.modelId === model.id).map((a) => a.name)
      const kbNames = knowledgeBases.filter((k) => k.embeddingModelId === model.id).map((k) => k.name)
      if (appNames.length || kbNames.length) {
        const brief = (names: string[], unit: string) =>
          `${names.length} 个${unit}（${names.slice(0, 2).join('、')}${names.length > 2 ? ' 等' : ''}）`
        const refs = [
          appNames.length ? brief(appNames, '应用') : '',
          kbNames.length ? brief(kbNames, '知识库') : ''
        ]
          .filter(Boolean)
          .join('、')
        throw new ApiError(4007, `该模型被 ${refs} 引用，无法删除；请先在这些引用处更换模型`)
      }

      models.splice(models.indexOf(model), 1)
      return undefined
    }
  ],

  [
    'POST',
    '/models/:id/load',
    ({ path }) => {
      const model = findModel(path.id)
      if (model.status === 'LOADING') throw new ApiError(4008, '该模型正在加载中，请勿重复提交')

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
          4009,
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
