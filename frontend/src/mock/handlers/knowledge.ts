import { ApiError, ErrorCode } from '@/api/types'
import type { CreateKnowledgeBaseBody, KnowledgeBase } from '@/api/knowledge'
import type { Grant } from '@/api/types'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { knowledgeBases, knowledgeCategories } from '../fixtures/knowledge'
import { roles, users } from '../fixtures/identity'
import { models } from '../fixtures/models'
import { findShareableOrg } from './orgs'
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

/**
 * #12 幂等建库：同一 `clientRequestId` 重复创建返回**既有库**。
 * 没有这张表，「建库成功但上传失败后重试」会被「名称已存在」卡死，并留下孤儿库。
 */
const createRequestIdToKbId = new Map<string, string>()

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

      // #12 幂等：同一 clientRequestId 重试直接回既有库，不再撞「名称已存在」
      const requestId = input.clientRequestId?.trim()
      if (requestId) {
        const existingId = createRequestIdToKbId.get(requestId)
        if (existingId) {
          const existing = knowledgeBases.find((k) => k.id === existingId)
          if (existing) return existing
        }
      }

      if (!input.name?.trim()) throw new ApiError(3001, '知识库名称不能为空')
      if (knowledgeBases.some((k) => k.name === input.name.trim())) {
        throw new ApiError(3002, `知识库名称「${input.name}」已存在`)
      }
      // ★ ceiling 必填（13 §6）：缺了它「文档密级 ≤ ceiling」就没有依据 —— 与后端同文案
      if (!input.maxClassification) {
        throw new ApiError(
          400,
          '库的密级上限必填',
          '为这个知识库选择允许装入的最高密级（公开/内部/秘密/机密）—— 它是入库校验的依据（13 §6）'
        )
      }
      if (!input.embeddingModelId) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '必须指定向量模型 —— 维度与向量表绑定，创建后不可随意更换')
      }
      const embeddingModel = models.find((m) => m.id === input.embeddingModelId && m.kind === 'EMBEDDING')
      if (!embeddingModel) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `向量模型「${input.embeddingModelId}」不存在或不是向量模型`)
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
        // ceiling（13 §6）：创建时必填，存下来供建档校验用
        maxClassification: input.maxClassification,
        // 归属由"后端"写：ownerId 从主体集合取（**绝不从请求体取**，否则就是客户端自封归属），
        // 默认可见性 private（07 §7 第 4 问的建议 4(a)：安全默认，要共享得显式动作）
        ownerId: selfId(subjects),
        visibility: 'PRIVATE'
      }
      knowledgeBases.unshift(created)
      if (requestId) createRequestIdToKbId.set(requestId, created.id)
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
    'PUT',
    '/knowledge-bases/:id/grants',
    ({ path, body, token }) => {
      const kb = findKb(path.id, token, PERM.knowledgeManage)
      const { grants } = body as { grants: Grant[] }
      if (!Array.isArray(grants)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, 'grants 必须是数组')
      }
      // 主体必须真实存在：写错 id 的授权等于静默没共享出去（对方看不到，自己以为共享了）
      for (const g of grants) {
        const kind = g.subject?.kind
        const id = g.subject?.id
        if (!id || (kind !== 'USER' && kind !== 'ROLE' && kind !== 'ORG')) {
          throw new ApiError(ErrorCode.BAD_REQUEST, '共享主体只支持账号（USER）/ 角色（ROLE）/ 部门（ORG）')
        }
        // ★ 三种 kind 的取值口径**不一致**（与后端 GrantSubjectPort 同）：USER/ORG 是 uuid、ROLE 是 code
        const exists =
          kind === 'USER'
            ? users.some((u) => u.id === id)
            : kind === 'ROLE'
              ? roles.some((r) => r.code === id)
              : findShareableOrg(id) !== undefined
        if (!exists) {
          const label = kind === 'USER' ? '账号' : kind === 'ROLE' ? '角色' : '部门'
          throw new ApiError(
            ErrorCode.BAD_REQUEST,
            `共享主体不存在：${label} ${id}`,
            '重新选择一个主体再保存。★ 写错 id 的授权不会报错，但对方永远看不到这个库；已停用的部门也不能接受新的共享'
          )
        }
        if (!Array.isArray(g.actions) || g.actions.length === 0) {
          throw new ApiError(ErrorCode.BAD_REQUEST, '每条共享授权至少要有一个动作')
        }
      }
      // ★ 覆盖式（不是增量），且可见性跟着授权走：空清单 = 收回全部共享 ⇒ 回到 private
      //   （★ 机器码是 SCREAMING_SNAKE —— 这里写小写会被 `ResourceVisibility` 的类型拦下，实测过一次）
      kb.grants = grants.map((g) => ({
        subject: { ...g.subject },
        actions: [...g.actions],
        expiresAt: g.expiresAt ?? null,
        canDelegate: g.canDelegate ?? false
      }))
      kb.visibility = kb.grants.length ? 'SHARED' : 'PRIVATE'
      return kb
    }
  ],

  [
    'DELETE',
    '/knowledge-bases/:id',
    ({ path, token }) => {
      const kb = findKb(path.id, token, PERM.knowledgeManage)
      knowledgeBases.splice(knowledgeBases.indexOf(kb), 1)
      // 幂等键一并清掉：库没了，旧 clientRequestId 不该再解析到幽灵 id
      for (const [rid, kid] of createRequestIdToKbId) {
        if (kid === kb.id) createRequestIdToKbId.delete(rid)
      }
      refreshCategoryCounts()
      return undefined
    }
  ]
]
