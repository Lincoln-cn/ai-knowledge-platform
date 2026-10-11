import { request } from '@/lib/http'
import type { Grant, PageQuery, PageResult, ResourceVisibility } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 导入类型在创建时选定，之后不可更改（BR-03-01） */
export type ImportType = 'LOCAL' | 'ONLINE'

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
  /**
   * 库的密级上限（ceiling，13 §6）：这个库**允许装**的最高密级。
   * ★ 与「谁能看这个库」（Role.maxClassification）正交，不要混。
   * 服务端必填校验：创建时缺失 ⇒ 400「库的密级上限必填」。
   */
  maxClassification?: string
  /**
   * 归属与可见性（07 §3.1 的 `OwnedResource`）。**由后端写入**：创建时自动写
   * `ownerId = 当前用户` / `visibility = 'PRIVATE'`，所以契约里没有对应的请求字段。
   * `ownerId` 不可变 —— 转让是显式动作，不是改个字段。
   */
  ownerId?: string
  visibility?: ResourceVisibility
  grants?: Grant[]
}

export interface KnowledgeCategory {
  id: string
  name: string
  baseCount: number
}

export interface KnowledgeBaseQuery extends PageQuery {
  category?: string
  enabled?: boolean
  /** 只看自己名下的（07 §六 阶段 1 的唯一新增查询参数）。不传 = 按数据范围给全部可见的 */
  mine?: boolean
}

export interface CreateKnowledgeBaseBody {
  name: string
  category: string
  description: string
  icon: string
  importType: ImportType
  embeddingModelId: string
  /**
   * 库的密级上限（ceiling，13 §6）。★ **服务端必填**（缺失 ⇒ 400「库的密级上限必填」），
   * 契约上仍声明为可选以守住「只加可选字段」的冻结约定。
   */
  maxClassification?: string
  /**
   * 幂等键（#12）。同一 `clientRequestId` 重复创建返回**既有库**而不是「名称已存在」——
   * 建库成功但后续上传失败后，重试「开始导入」不应被卡死，也不该留下孤儿库。
   */
  clientRequestId?: string
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

/**
 * 覆盖式写入共享授权（`07 §8.1`；与助手的 `setAssistantGrants` 同形状，前端可复用同一套对话框逻辑）。
 *
 * - 主体形状见 `Grant`：`USER` / `ORG` 的 `id` 是 uuid，`ROLE` 的 `id` 是角色 **code**（`kind` 与 `id` 一起比）
 * - ★ 传**空数组 = 收回全部共享**（库回到 `private`），后端据此把可见性从 `shared` 收回
 * - ★ 共享给**部门**（`kind: 'ORG'`）的含义是"该部门**及下级**都看得见"——含下级由判定侧（`ScopeFilter`）负责，写入口不必知道
 */
export const setKnowledgeBaseGrants = (id: string, grants: Grant[]) =>
  request<KnowledgeBase>({
    method: 'PUT',
    url: '/knowledge-bases/:id/grants',
    path: { id },
    body: { grants }
  })

/** 删除会连同文档与分段一并移除。这是不可逆操作，前端必须先二次确认 */
export const deleteKnowledgeBase = (id: string) =>
  request<void>({ method: 'DELETE', url: '/knowledge-bases/:id', path: { id } })

export const listKnowledgeCategories = () =>
  request<KnowledgeCategory[]>({ method: 'GET', url: '/knowledge-bases/categories' })
