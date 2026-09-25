import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 导入类型在创建时选定，之后不可更改（BR-03-01） */
export type ImportType = 'local' | 'online'

export interface KnowledgeBase {
  id: string
  name: string
  category: string
  description: string
  icon: string
  importType: ImportType
  /** 绑定的向量模型。已有向量化内容时后端拒绝更换（KB-I4） */
  embeddingModelId: string
  embeddingModelName: string
  /** 已有向量化内容 → 向量模型被锁定 */
  embeddingLocked: boolean
  enabled: boolean
  documentCount: number
  segmentCount: number
  charCount: number
  createdAt: string
}

export interface KnowledgeCategory {
  id: string
  name: string
  baseCount: number
}

export interface KnowledgeBaseQuery extends PageQuery {
  category?: string
  enabled?: boolean
}

export interface CreateKnowledgeBaseBody {
  name: string
  category: string
  description: string
  icon: string
  importType: ImportType
  embeddingModelId: string
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listKnowledgeBases = (query: KnowledgeBaseQuery = {}) =>
  request<PageResult<KnowledgeBase>>({ method: 'GET', url: '/knowledge-bases', query })

export const getKnowledgeBase = (id: string) =>
  request<KnowledgeBase>({ method: 'GET', url: '/knowledge-bases/:id', path: { id } })

export const createKnowledgeBase = (body: CreateKnowledgeBaseBody) =>
  request<KnowledgeBase>({ method: 'POST', url: '/knowledge-bases', body })

export const updateKnowledgeBase = (id: string, body: Partial<CreateKnowledgeBaseBody>) =>
  request<KnowledgeBase>({ method: 'PUT', url: '/knowledge-bases/:id', path: { id }, body })

export const setKnowledgeBaseEnabled = (id: string, enabled: boolean) =>
  request<KnowledgeBase>({
    method: 'PATCH',
    url: '/knowledge-bases/:id/enabled',
    path: { id },
    body: { enabled }
  })

/** 删除会连同文档与分段一并移除。这是不可逆操作，前端必须先二次确认 */
export const deleteKnowledgeBase = (id: string) =>
  request<void>({ method: 'DELETE', url: '/knowledge-bases/:id', path: { id } })

export const listKnowledgeCategories = () =>
  request<KnowledgeCategory[]>({ method: 'GET', url: '/knowledge-bases/categories' })
