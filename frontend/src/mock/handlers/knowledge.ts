import { ApiError, ErrorCode } from '@/api/types'
import type { CreateKnowledgeBaseBody, KnowledgeBase } from '@/api/knowledge'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { knowledgeBases, knowledgeCategories } from '../fixtures/knowledge'
import { models } from '../fixtures/models'
import { currentSubjects, requireVisible, selfId, visiblePredicate } from '../policy'

/**
 * 单条取数与**写操作前置**共用同一个判定（07 §3.3 硬规矩 1：写操作也要走，
 * 否则「看不见但能改」这种越权最难发现）。
 * 看不见一律 404（硬规矩 3）—— 所以这里不区分"不存在"与"看不见"。
 */
const findKb = (id: string, token: string, action: string = PERM.knowledgeView) =>
  requireVisible(
    knowledgeBases.find((k) => k.id === id),
    currentSubjects(token),
    action,
    '知识库',
    id
  )

const refreshCategoryCounts = () => {
  for (const cat of knowledgeCategories) {
    cat.baseCount = knowledgeBases.filter((k) => k.category === cat.name).length
  }
}

export const knowledgeRoutes: MockRoute[] = [
  [
    'GET',
    '/knowledge-bases',
    ({ query, token }) => {
      const subjects = currentSubjects(token)
      const keyword = String(query.keyword ?? '')
      const category = query.category ? String(query.category) : ''
      const hasEnabledFilter = query.enabled !== undefined && query.enabled !== ''
      const enabled = query.enabled === true || query.enabled === 'true'
      // mine=true 只看自己名下的（07 §六 阶段 1 的唯一新增查询参数）
      const mine = query.mine === true || query.mine === 'true'

      const list = knowledgeBases
        // 先在数据层过滤再分页：`total` 必须是"我看得见的条数"，否则分页会翻出空页
        .filter(visiblePredicate(subjects, PERM.knowledgeView, { mine }))
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
    ({ token }) => {
      const subjects = currentSubjects(token)
      const visible = knowledgeBases.filter(visiblePredicate(subjects, PERM.knowledgeView))
      // 计数只统计"我看得见的库"：否则计数本身就会泄漏"存在一个我看不见的库"
      return knowledgeCategories.map((cat) => ({
        ...cat,
        baseCount: visible.filter((k) => k.category === cat.name).length
      }))
    }
  ],

  ['GET', '/knowledge-bases/:id', ({ path, token }) => findKb(path.id, token)],

  [
    'POST',
    '/knowledge-bases',
    ({ body, token }) => {
      const subjects = currentSubjects(token)
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
        createdAt: new Date().toISOString(),
        // 归属由"后端"写：ownerId 从主体集合取（**绝不从请求体取**，否则就是客户端自封归属），
        // 默认可见性 private（07 §7 第 4 问的建议 4(a)：安全默认，要共享得显式动作）
        ownerId: selfId(subjects),
        visibility: 'private'
      }
      knowledgeBases.unshift(created)
      refreshCategoryCounts()
      return created
    }
  ],

  [
    'PUT',
    '/knowledge-bases/:id',
    ({ path, body, token }) => {
      const kb = findKb(path.id, token, PERM.knowledgeManage)
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
      // 归属不可变（07 §3.1）：请求体里根本没有 ownerId / visibility，这里也不写这两个字段
      return kb
    }
  ],

  [
    'PATCH',
    '/knowledge-bases/:id/enabled',
    ({ path, body, token }) => {
      const kb = findKb(path.id, token, PERM.knowledgeManage)
      const { enabled } = body as { enabled: boolean }
      kb.enabled = Boolean(enabled)
      return kb
    }
  ],

  [
    'DELETE',
    '/knowledge-bases/:id',
    ({ path, token }) => {
      const kb = findKb(path.id, token, PERM.knowledgeManage)
      knowledgeBases.splice(knowledgeBases.indexOf(kb), 1)
      refreshCategoryCounts()
      return undefined
    }
  ]
]
