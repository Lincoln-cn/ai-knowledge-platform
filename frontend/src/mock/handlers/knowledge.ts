import { ApiError, ErrorCode } from '@/api/types'
import type { CreateKnowledgeBaseBody, KnowledgeBase } from '@/api/knowledge'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { knowledgeBases, knowledgeCategories } from '../fixtures/knowledge'
import { models } from '../fixtures/models'

const findKb = (id: string) => {
  const kb = knowledgeBases.find((k) => k.id === id)
  if (!kb) throw new ApiError(ErrorCode.NOT_FOUND, `知识库「${id}」不存在`)
  return kb
}

const refreshCategoryCounts = () => {
  for (const cat of knowledgeCategories) {
    cat.baseCount = knowledgeBases.filter((k) => k.category === cat.name).length
  }
}

export const knowledgeRoutes: MockRoute[] = [
  [
    'GET',
    '/knowledge-bases',
    ({ query }) => {
      const keyword = String(query.keyword ?? '')
      const category = query.category ? String(query.category) : ''
      const hasEnabledFilter = query.enabled !== undefined && query.enabled !== ''
      const enabled = query.enabled === true || query.enabled === 'true'

      const list = knowledgeBases
        .filter((k) => !keyword || k.name.includes(keyword) || k.description.includes(keyword))
        .filter((k) => !category || k.category === category)
        .filter((k) => !hasEnabledFilter || k.enabled === enabled)

      return paginate(list, query)
    }
  ],

  // 分类必须挂在 /knowledge-bases/:id 之前，否则会被 :id 吃掉
  [
    'GET',
    '/knowledge-bases/categories',
    () => {
      refreshCategoryCounts()
      return knowledgeCategories
    }
  ],

  ['GET', '/knowledge-bases/:id', ({ path }) => findKb(path.id)],

  [
    'POST',
    '/knowledge-bases',
    ({ body }) => {
      const input = body as CreateKnowledgeBaseBody
      if (!input.name?.trim()) throw new ApiError(3001, '知识库名称不能为空')
      if (knowledgeBases.some((k) => k.name === input.name.trim())) {
        throw new ApiError(3002, `知识库名称「${input.name}」已存在`)
      }
      if (!input.embeddingModelId) {
        throw new ApiError(3003, '必须指定向量模型 —— 维度与向量表绑定，创建后不可随意更换')
      }
      const embeddingModel = models.find((m) => m.id === input.embeddingModelId && m.kind === 'embedding')
      if (!embeddingModel) {
        throw new ApiError(3004, `向量模型「${input.embeddingModelId}」不存在或不是向量模型`)
      }

      const created: KnowledgeBase = {
        id: `kb${Date.now()}`,
        name: input.name.trim(),
        category: input.category || '其他',
        description: input.description ?? '',
        icon: input.icon || '📘',
        importType: input.importType,
        embeddingModelId: embeddingModel.id,
        embeddingModelName: embeddingModel.name,
        embeddingLocked: false,
        enabled: true,
        documentCount: 0,
        segmentCount: 0,
        charCount: 0,
        createdAt: new Date().toISOString()
      }
      knowledgeBases.unshift(created)
      refreshCategoryCounts()
      return created
    }
  ],

  [
    'PUT',
    '/knowledge-bases/:id',
    ({ path, body }) => {
      const kb = findKb(path.id)
      const input = body as Partial<CreateKnowledgeBaseBody>

      if (input.name && input.name !== kb.name && knowledgeBases.some((k) => k.name === input.name)) {
        throw new ApiError(3002, `知识库名称「${input.name}」已存在`)
      }
      // KB-I4：已有向量化内容时拒绝换向量模型
      if (input.embeddingModelId && input.embeddingModelId !== kb.embeddingModelId && kb.embeddingLocked) {
        throw new ApiError(
          ErrorCode.EMBEDDING_LOCKED,
          '该知识库已有向量化内容，向量模型已锁定',
          '如需更换向量模型，请新建知识库并重新导入文档'
        )
      }

      if (input.name) kb.name = input.name
      if (input.description !== undefined) kb.description = input.description
      if (input.icon) kb.icon = input.icon
      if (input.category) kb.category = input.category
      if (input.embeddingModelId && !kb.embeddingLocked) {
        const embeddingModel = models.find((m) => m.id === input.embeddingModelId)
        kb.embeddingModelId = input.embeddingModelId
        kb.embeddingModelName = embeddingModel?.name ?? input.embeddingModelId
      }
      refreshCategoryCounts()
      return kb
    }
  ],

  [
    'PATCH',
    '/knowledge-bases/:id/enabled',
    ({ path, body }) => {
      const kb = findKb(path.id)
      const { enabled } = body as { enabled: boolean }
      kb.enabled = Boolean(enabled)
      return kb
    }
  ],

  [
    'DELETE',
    '/knowledge-bases/:id',
    ({ path }) => {
      const kb = findKb(path.id)
      knowledgeBases.splice(knowledgeBases.indexOf(kb), 1)
      refreshCategoryCounts()
      return undefined
    }
  ]
]
