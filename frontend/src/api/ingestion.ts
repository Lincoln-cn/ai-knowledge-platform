import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/**
 * 文档六态。进度的唯一真相源是后端：
 * 「向量化中」必须带 processedSegments/segmentCount，前端不做百分比猜测。
 */
export type DocumentStatus =
  | '待解析'
  | '分段中'
  | '待向量化'
  | '向量化中'
  | '成功'
  | '分段失败'
  | '向量化失败'

export interface DocumentItem {
  id: string
  kbId: string
  name: string
  source: string
  status: DocumentStatus
  segmentCount: number
  /** 已处理分段数。status 为「向量化中」时有意义 */
  processedSegments: number
  charCount: number
  enabled: boolean
  /** 失败原因（已可读化）。前端必须展示，不允许静默失败 */
  failReason: string | null
  /** 失败时的下一步建议 */
  failHint: string | null
  createdAt: string
  updatedAt: string
}

/** 库级进度聚合：阶段 + 计数，用于页面顶部进度条 */
export interface IngestionProgress {
  kbId: string
  total: number
  done: number
  failed: number
  /** 按阶段计数，与 DocumentStatus 对应 */
  stages: Array<{ status: DocumentStatus; count: number }>
  /** 有进行中任务时前端才轮询；全部完成即停 */
  running: boolean
}

export interface Segment {
  id: string
  documentId: string
  ordinal: number
  title: string
  content: string
  charCount: number
  enabled: boolean
  embedding: '待向量化' | '已向量化' | '失败'
}

export interface DocumentQuery extends PageQuery {
  status?: DocumentStatus
}

export interface SegmentQuery extends PageQuery {
  documentId?: string
  /** 只看超长分段（SEG-I1） */
  onlyOversized?: boolean
}

/** 切分预览请求：向导第二步的核心 */
export interface PreviewSegmentationBody {
  /** 已有文档 id；上传前预览时传 null */
  documentId: string | null
  /** 分段长度上限 */
  segmentLength: number
  /** 重叠长度 */
  overlap: number
  /** 切分策略 */
  strategy: 'auto' | 'custom'
}

export interface PreviewSegmentationResult {
  segmentCount: number
  /** 预估字符数 */
  charCount: number
  /** 当前向量模型的分段长度上限，用于界面左侧实时提示 */
  modelLimit: number
  segments: Array<{ ordinal: number; content: string; charCount: number; oversized: boolean }>
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listDocuments = (kbId: string, query: DocumentQuery = {}) =>
  request<PageResult<DocumentItem>>({
    method: 'GET',
    url: '/knowledge-bases/:kbId/documents',
    path: { kbId },
    query
  })

/** 上传文档。走 multipart，与其它接口共用同一出口 */
export const uploadDocuments = (kbId: string, files: File[]) => {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  return request<DocumentItem[]>({
    method: 'POST',
    url: '/knowledge-bases/:kbId/documents',
    path: { kbId },
    body: form
  })
}

/**
 * 库级真实进度。
 * 前端只在 progress.running 为 true 时轮询，且页面不可见时暂停。
 */
export const getIngestionProgress = (kbId: string) =>
  request<IngestionProgress>({
    method: 'GET',
    url: '/knowledge-bases/:kbId/ingestion-progress',
    path: { kbId }
  })

export const renameDocument = (id: string, name: string) =>
  request<DocumentItem>({ method: 'PATCH', url: '/documents/:id', path: { id }, body: { name } })

export const setDocumentEnabled = (id: string, enabled: boolean) =>
  request<DocumentItem>({
    method: 'PATCH',
    url: '/documents/:id/enabled',
    path: { id },
    body: { enabled }
  })

export const deleteDocument = (id: string) =>
  request<void>({ method: 'DELETE', url: '/documents/:id', path: { id } })

/** 从失败阶段重试。分段失败与向量化失败走同一条接口，由后端判断起点 */
export const retryDocument = (id: string) =>
  request<DocumentItem>({ method: 'POST', url: '/documents/:id/retry', path: { id } })

/** 开始向量化。用户显式动作，后端不自动推进 */
export const startEmbedding = (id: string) =>
  request<DocumentItem>({ method: 'POST', url: '/documents/:id/embedding', path: { id } })

export const previewSegmentation = (body: PreviewSegmentationBody) =>
  request<PreviewSegmentationResult>({ method: 'POST', url: '/segmentation/preview', body })

export const listSegments = (query: SegmentQuery = {}) =>
  request<PageResult<Segment>>({ method: 'GET', url: '/segments', query })

export const updateSegment = (id: string, body: { title?: string; content?: string }) =>
  request<Segment>({ method: 'PUT', url: '/segments/:id', path: { id }, body })

export const setSegmentEnabled = (id: string, enabled: boolean) =>
  request<Segment>({
    method: 'PATCH',
    url: '/segments/:id/enabled',
    path: { id },
    body: { enabled }
  })

export const deleteSegment = (id: string) =>
  request<void>({ method: 'DELETE', url: '/segments/:id', path: { id } })

/** 批量迁移：搬移语义（源删除 + 目标写入） */
export const batchMoveSegments = (body: { segmentIds: string[]; targetDocumentId: string }) =>
  request<{ moved: number }>({ method: 'POST', url: '/segments/batch-move', body })
