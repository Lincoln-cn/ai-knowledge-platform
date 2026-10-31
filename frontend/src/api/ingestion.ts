import { request } from '@/lib/http'
import type { ClassificationLevel, PageQuery, PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

/**
 * 文档七态。进度的唯一真相源是后端：
 * `EMBEDDING` 必须带 processedSegments/segmentCount，前端不做百分比猜测。
 */
export type DocumentStatus =
  | 'PENDING_PARSE'
  | 'SEGMENTING'
  | 'PENDING_EMBEDDING'
  | 'EMBEDDING'
  | 'SUCCEEDED'
  | 'SEGMENT_FAILED'
  | 'EMBEDDING_FAILED'

export interface DocumentItem {
  id: string
  kbId: string
  name: string
  source: string
  status: DocumentStatus
  segmentCount: number
  /** 已处理分段数。status 为 `EMBEDDING` 时有意义 */
  processedSegments: number
  charCount: number
  enabled: boolean
  /** 失败原因（已可读化）。前端必须展示，不允许静默失败 */
  failReason: string | null
  /** 失败时的下一步建议 */
  failHint: string | null
  createdAt: string
  updatedAt: string
  /**
   * 文种（合同/制度/报销规定…）。★ 建档必填三项之一（12 §5.1），
   * 冻结契约只加可选字段，故此处 `?`。
   */
  docType?: string
  /**
   * 责任部门。★ 建档必填三项之一。
   *
   * <p>★ 传的是**组织机构的 id（`org_id`）**，不是展示名 —— 展示名可变、可重名，而它要进召回前缀
   * （`07 §十一`）。传 name 会被后端 400 拒（要的是 UUID）。
   */
  orgId?: string
  /** 密级。★ 建档必填三项之一，且必须 ≤ 所属库的 `maxClassification`（ceiling，13 §6） */
  classification?: ClassificationLevel
  /**
   * 解析出的 Markdown 正文（V14 列，0.2.1）。★ 冻结契约只加可选字段，故 `?`。
   * 失败时可能是**部分内容**（handler 已缓冲片段）—— 与 `failReason` 并存，
   * 不许把「解析失败」做成整篇丢弃（12 §2.1）。
   */
  parsedContent?: string
  /**
   * 需要 OCR 的页数（第 9 周 · 0.3.1）—— 「已识别 N/M 页」里的 **M**。
   * ★ 由一次关掉 OCR 的快扫得出（约 0.3 秒），所以上传后**立刻**就有值；`0` = 不涉及 OCR
   *   （非 PDF / 纯文本 PDF）⇒ 界面**不显示**进度行，而不是显示一条恒为 0/0 的噪声。
   * ★ 扫描件的解析要跑分钟级（实测每页约 3.6 秒）⇒ 没有这个数用户就是面对几分钟空白。
   */
  ocrTotalPages?: number
  /** 已完成 OCR 的页数 —— 「已识别 N/M 页」里的 **N**。逐页回写，刷新页面不重置。 */
  ocrDonePages?: number
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

/** 分段向量化状态（2.4 补录）。「EMBEDDING」是编辑后重新向量化时的过渡态（#10） */
export type SegmentEmbeddingStatus = 'PENDING_EMBEDDING' | 'EMBEDDING' | 'EMBEDDED' | 'FAILED'

export interface Segment {
  id: string
  documentId: string
  /**
   * 文档内序号。★ **从 0 起**（数据/契约口径，与后端领域层的 chunk 序号一致）。
   * 界面要展示「第 N 段」时用 `ordinal + 1` —— 两套基准混用会得到整体差 1 的错位编号。
   */
  ordinal: number
  title: string
  content: string
  charCount: number
  enabled: boolean
  /** 「EMBEDDING」是编辑后重新向量化时的过渡态（#10） */
  embedding: SegmentEmbeddingStatus
}

export interface DocumentQuery extends PageQuery {
  status?: DocumentStatus
  /**
   * 按**责任部门归属状态**过滤（`#56`）。
   *
   * - `UNASSIGNED`：只列**待补归属**的文档 —— `V27` 把责任部门从自由字符串换成 `org_id` 时
   *   按名字回填，**匹配不上的原值留在 `department_legacy`**（不可更新的存档列），而它们此前
   *   **没有任何界面入口能列出来**（`#56`：开发库 112 篇里 3 篇）。指定归属后即从这一档消失。
   * - `ASSIGNED`：只列已有归属的。不传：不过滤（既有行为）。
   *
   * ★ 用状态名而不是布尔（`?unassigned=true`）：调用点上要能读出意图。
   */
  orgAssignment?: OrgAssignment
}

/** 责任部门归属过滤的三态（与后端 `OrgAssignment` 同源，`07 §11.4 ③`） */
export type OrgAssignment = 'ANY' | 'ASSIGNED' | 'UNASSIGNED'

export interface SegmentQuery extends PageQuery {
  documentId?: string
  /** 只看超长分段（SEG-I1） */
  onlyOversized?: boolean
  /**
   * 限定知识库（P1 跨库批量删除）。不传时后端按数据范围返回**所有可见库**的分段 ——
   * 批量操作前必须传，否则会跨库误删。
   */
  kbId?: string
}

/** 切分计划的一段。预览里用户可编辑，编辑结果作为 `segmentationPlan` 提交 */
export interface SegmentationPlanItem {
  ordinal: number
  title?: string
  content: string
}

/** 切分预览请求：向导第二步的核心 */
export interface PreviewSegmentationBody {
  /** 已有文档 id；上传前预览时传 null */
  documentId: string | null
  /** 分段长度上限 */
  segmentLength: number
  /** 重叠长度 */
  overlap: number
  /** 切分策略（2.4 补录） */
  strategy: 'AUTO' | 'CUSTOM'
  /** #11 暂存上传的文件 id；与 `documentId` 二选一，预览走**用户自己的文件** */
  uploadIds?: string[]
  /** #11 用户确认过的切分计划（含预览编辑）。不传则按规则自动切 */
  segmentationPlan?: SegmentationPlanItem[]
}

export interface PreviewSegmentationResult {
  segmentCount: number
  /** 预估字符数 */
  charCount: number
  /** 当前向量模型的分段长度上限，用于界面左侧实时提示 */
  modelLimit: number
  /**
   * 分段列表。★ `ordinal` 从 **0** 起（同 `Segment.ordinal`）；界面展示用 `ordinal + 1`。
   * `oversized` 对应后端 `oversizedRisk`（兜底层硬切的标记），不是「字数超上限」——
   * 超上限的分段根本进不了库（SEG-I1 会拒绝）。
   */
  segments: Array<{ ordinal: number; content: string; charCount: number; oversized: boolean }>
  /**
   * 噪声剥离掉的行数（第 9 周 · 0.3.1，`12 §2.4`）：页眉 / 页脚 / 页码 / 目录。
   * ★ 必须显示出来 —— 不显示的话「为什么这份文档少了一页内容」没人能解释
   * （删掉的东西要看得见，与「失败样本留存」同一条纪律）。0 表示这份文档没有噪声。
   */
  removedNoiseLines?: number
}

/** #11 暂存上传回执 */
export interface StageUploadsResult {
  uploadIds: string[]
}

/**
 * #11/#P1 导入请求。★ 两套形状并存，口径如下（后端 `RegisterDocumentsBody` 同步）：
 *
 * - `names` = **轻量建档**（只登记文档名，不带文件）—— 当前后端唯一消费的形态
 * - `uploadIds` = **暂存上传后的导入**（`stageUploads` 的产物）—— **后端尚未消费**，属欠账
 *
 * ★ 至少给一种；**同时给时以 `names` 为准**。
 * 只传 `uploadIds` / `segmentationPlan` 而不传 `names` ⇒ 400（禁止「收下但不用」的静默忽略）。
 */
export interface ImportDocumentsBody {
  /**
   * 轻量建档：文件名清单。当前后端唯一消费的建档入参。
   */
  names?: string[]
  /**
   * 暂存上传后的导入（`stageUploads` 产物）。★ 后端尚未消费 —— 只传它不传 `names` 会 400；
   * 与 `names` 同传时以 `names` 为准。保留字段等上传链落地，**不代表它已生效**。
   */
  uploadIds?: string[]
  /**
   * 用户确认过的切分计划。同属暂存导入形态：后端尚未消费，只传它不传 `names` 会 400。
   */
  segmentationPlan?: SegmentationPlanItem[]
  /**
   * 建档必填三项（12 §5.1）—— **批级**：一次导入给这批文档同一套标签。
   * ★ 逐篇覆盖是后续（等单篇编辑端点落地），本批不做。
   */
  docType?: string
  /** ★ 责任部门 = 组织机构的 id（见上文 uploadDocuments 的说明）。 */
  orgId?: string
  classification?: ClassificationLevel
}

/** P1 上传回执：部分失败必须逐条给原因，不许只回一个总数 */
export interface UploadReceipt {
  accepted: DocumentItem[]
  rejected: Array<{ name: string; reason: string }>
}

/** P1 上传策略：前端据此做大小/数量校验，而不是等后端拒了才知道 */
export interface UploadPolicy {
  maxFileSizeMB: number
  maxFileCount: number
  acceptedExtensions: string[]
}

/** #10 库级重新向量化回执 */
export interface ReembedKbResult {
  queued: number
}

/** P1 批量删除回执 */
export interface BatchDeleteSegmentsResult {
  deleted: number
  failed: Array<{ id: string; reason: string }>
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listDocuments = (kbId: string, query: DocumentQuery = {}) =>
  request<PageResult<DocumentItem>>({
    method: 'GET',
    url: '/knowledge-bases/:kbId/documents',
    path: { kbId },
    query
  })

/**
 * ★★ 指定/变更某篇文档的**责任部门**（契约 `assignDocumentOrg`；`#150`）。
 *
 * 它是「待补归属」那一档（`listDocuments` 的 `orgAssignment: 'UNASSIGNED'`，`#56`）真正的出口：
 * 在那之前责任部门**只能在导入向导里设**，建档之后没有修改入口 ⇒ 待补的文档列得出来却处理不掉
 * （唯一的绕法是删档重建，而重建会丢掉 `department_legacy` 那个存档值）。
 *
 * ★ 只改归属：密级 / 文种 / 分段 / 向量一概不动，`department_legacy` 也不许清（它是"当初没匹配上"的证据）。
 * ★ 部门不存在/已停用 ⇒ 400；文档不在这个库 ⇒ 404（不是 403 —— 那会确认 id 存在）。
 */
export const assignDocumentOrg = (kbId: string, documentId: string, orgId: string) =>
  request<DocumentItem>({
    method: 'PATCH',
    url: '/knowledge-bases/:kbId/documents/:documentId/org',
    path: { kbId, documentId },
    body: { orgId }
  })

/** 上传文档。走 multipart，与其它接口共用同一出口 */export const uploadDocuments = (
  kbId: string,
  files: File[],
  meta?: { docType: string; orgId: string; classification: ClassificationLevel }
) => {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  // ★ 建档必填三项随文件一起走（multipart 的普通字段）—— 后端**建档即解析**，
  //   解析出的 Markdown 进 parsedContent；缺任一仍会被后端 400 拒（前端先拦一道）。
  if (meta) {
    form.append('docType', meta.docType)
    form.append('orgId', meta.orgId)
    form.append('classification', meta.classification)
  }
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

/**
 * 变更文档密级（#247）。
 *
 * ★ 与「知识库的密级上限」不是一回事：`maxClassification` 是库允许装的最高密级（ceiling），
 *   这里改的是某一份文档自己的密级。两者都会被后端校验（文档密级不得高于库上限）。
 *
 * ★ 升密时后端会顺手把引用过这份文档的会话水位抬到新值（同一事务，#27 / 13 §2.2）；
 *   降密则不动水位 —— 会话密级是单调的（只升不降），这是安全的严格方向。
 */
export const changeDocumentClassification = (
  id: string,
  classification: ClassificationLevel
) =>
  request<DocumentItem>({
    method: 'PATCH',
    url: '/documents/:id/classification',
    path: { id },
    body: { classification }
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

/* ────────────── #10 重向量化 · #11 暂存上传 · P1 批量与策略 ────────────── */

/** #10 单段重新向量化。编辑后 `embedding` 置「待向量化」，这里把它推进到「已向量化」 */
export const reembedSegment = (id: string) =>
  request<Segment>({ method: 'POST', url: '/segments/:id/embedding', path: { id } })

/** #10 文档级重新向量化：该文档下所有分段重跑 */
export const reembedDocument = (id: string) =>
  request<DocumentItem>({ method: 'POST', url: '/documents/:id/reembed', path: { id } })

/** #10 库级重新向量化：整库排队，回执只给 queued 数 */
export const reembedKnowledgeBase = (kbId: string) =>
  request<ReembedKbResult>({
    method: 'POST',
    url: '/knowledge-bases/:kbId/reembed',
    path: { kbId }
  })

/** #11 暂存上传：只落文件、不建文档，预览与导入共用这批 `uploadIds` */
export const stageUploads = (kbId: string, files: File[]) => {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  return request<StageUploadsResult>({
    method: 'POST',
    url: '/knowledge-bases/:kbId/uploads',
    path: { kbId },
    body: form
  })
}

/**
 * #11 导入建档。★ 当前后端只消费 `names`（轻量建档）；`uploadIds` / `segmentationPlan`
 * 是暂存导入形态的欠账 —— 只传它们不传 `names` 会被 400 拒绝，同时给时以 `names` 为准。
 * 响应带部分失败回执（P1），失败的文件逐条给原因。
 */
export const importDocuments = (kbId: string, body: ImportDocumentsBody) =>
  request<UploadReceipt>({
    method: 'POST',
    url: '/knowledge-bases/:kbId/documents',
    path: { kbId },
    body
  })

/** P1 批量删除：一次请求，回执区分 deleted 与 failed */
export const batchDeleteSegments = (ids: string[]) =>
  request<BatchDeleteSegmentsResult>({
    method: 'DELETE',
    url: '/segments/batch',
    body: { ids }
  })

/** P1 上传策略：前端上传前校验大小与数量 */
export const getUploadPolicy = (kbId: string) =>
  request<UploadPolicy>({
    method: 'GET',
    url: '/knowledge-bases/:kbId/upload-policy',
    path: { kbId }
  })
