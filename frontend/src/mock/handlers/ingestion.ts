import { ApiError, ErrorCode } from '@/api/types'
import type {
  DocumentItem,
  ImportDocumentsBody,
  PreviewSegmentationBody,
  PreviewSegmentationResult,
  Segment,
  SegmentationPlanItem,
  UploadPolicy,
  UploadReceipt
} from '@/api/ingestion'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { documents, knowledgeBases, segments } from '../fixtures/knowledge'
import { PERM } from '@/constants/permissions'
import { currentSubjects, requireVisible, visibleTo, type SubjectSet } from '../policy'
// ★ `#150`：指定责任部门时校验"这个部门真的存在"（与真后端同一条）。
import { orgs as mockOrgs } from './orgs'

/**
 * 文档 / 分段**没有自己的归属字段**（07 §8.1 的三类资源是知识库 / 应用 / 会话），
 * 所以它们的可见性**继承所属知识库** —— 这正是 §8.4 说的「加一类资源 = 复用同一判定」：
 * 判定函数一行没改，只是换了个入口问同一个问题。
 */
const kbIdOfDoc = (docId: string) => documents.find((d) => d.id === docId)?.kbId

/** 密级序数：声明顺序即高低（labels.ts / 13 §6）—— ceiling 判定用 */
const CLASS_ORDER: Record<string, number> = {
  PUBLIC: 0,
  INTERNAL: 1,
  SECRET: 2,
  CONFIDENTIAL: 3
}
const CLASS_LABEL: Record<string, string> = {
  PUBLIC: '公开',
  INTERNAL: '内部',
  SECRET: '秘密',
  CONFIDENTIAL: '机密'
}

/**
 * 建档必填三项 + ceiling 校验（12 §5.1 / 13 §6）。
 * 文案与后端 `DocumentService` 一字不差 —— 界面展示的是后端可读原因原文，mock 不得另编。
 */
function assertDocMeta(body: {
  docType?: string
  orgId?: string
  classification?: string
}): void {
  if (!body.docType?.trim()) {
    throw new ApiError(
      400,
      '文种必填',
      '选择这份文档的文种（合同/制度/报销规定…）—— 它决定召回前缀，不能有默认值（12 §5.1）'
    )
  }
  if (!body.orgId?.trim()) {
    throw new ApiError(
      400,
      '责任部门必填',
      '填写这份文档的责任部门 —— 它决定召回前缀，不能有默认值（12 §5.1）'
    )
  }
  if (!body.classification) {
    throw new ApiError(
      400,
      '密级必填',
      '为这份文档选择密级（公开/内部/秘密/机密）—— 它进安全过滤，不能有默认值（12 §5.1）'
    )
  }
}

/** ceiling 校验：文档密级 > 库上限 ⇒ 拒绝 + 可读原因（含两个中文密级名） */
function assertWithinCeiling(
  kbId: string,
  classification: string
): void {
  const kb = knowledgeBases.find((k) => k.id === kbId)
  const ceiling = kb?.maxClassification
  if (!ceiling) return
  const docOrd = CLASS_ORDER[classification]
  const ceilOrd = CLASS_ORDER[ceiling]
  if (docOrd == null || ceilOrd == null) return
  if (docOrd > ceilOrd) {
    throw new ApiError(
      400,
      `文档密级（${CLASS_LABEL[classification]}）高于知识库的密级上限（${CLASS_LABEL[ceiling]}）—— 请提高库的上限，或改用更合适的库`,
      '库的密级上限是入库校验的依据（13 §6）：只有不高于上限的文档才能进这个库'
    )
  }
}

const kbIdOfSeg = (segmentId: string) => {
  const seg = segments.find((s) => s.id === segmentId)
  return seg ? kbIdOfDoc(seg.documentId) : undefined
}

/**
 * 读写都先问一次同一个判定（§3.3 硬规矩 1）。报错用**子资源**的 label / id：
 * 让调用方知道"这个文档/分段不存在"，而不是反过来暴露"某个知识库存在但你看不见"。
 */
const gateKb = (
  kbId: string | undefined,
  token: string,
  action: string,
  label: string,
  id: string
) =>
  requireVisible(
    kbId ? knowledgeBases.find((k) => k.id === kbId) : undefined,
    currentSubjects(token),
    action,
    label,
    id
  )

/** 分段列表不带 documentId 时也要过滤：否则"列全部"会横跨我看不见的知识库 */
const visibleSegments = (subjects: SubjectSet) =>
  segments.filter((s) => {
    const kbId = kbIdOfDoc(s.documentId)
    const kb = kbId ? knowledgeBases.find((k) => k.id === kbId) : undefined
    return !!kb && visibleTo(kb, subjects, PERM.knowledgeView)
  })

/** 向量化节拍：每隔一段推进若干分段，让「196/340」这种行内进度真的会动 */
const EMBED_TICK_MS = 800
const EMBED_STEP = 24

/** 解析/分段阶段的模拟耗时 */
const PARSE_MS = 1200
const SPLIT_MS = 1600

/** 向量模型分段长度上限（SEG-I1 的判据） */
const MODEL_LIMIT = 1200

/** P1 上传策略：前端据此做大小/数量校验 */
const UPLOAD_POLICY: UploadPolicy = {
  maxFileSizeMB: 20,
  maxFileCount: 10,
  acceptedExtensions: ['.pdf', '.docx', '.doc', '.md', '.txt']
}

/**
 * #11 暂存上传。只落文件元数据与可识别的正文占位，**不建文档**——
 * 预览与导入共用这批 `uploadIds`，用户在预览里改的切分计划才会被真正采纳。
 */
interface StagedUpload {
  id: string
  kbId: string
  name: string
  size: number
  text: string
}
const stagedUploads = new Map<string, StagedUpload>()

/** 从请求体里抽文件元数据。真 FormData 优先；兼容测试直传的 { files: [{name,size}] } */
function extractFiles(body: unknown): Array<{ name: string; size: number }> {
  if (body && typeof body === 'object' && typeof (body as FormData).entries === 'function') {
    const out: Array<{ name: string; size: number }> = []
    for (const [, value] of (body as FormData).entries()) {
      if (value && typeof value === 'object' && 'name' in value && 'size' in value) {
        const f = value as File
        out.push({ name: String(f.name || '未命名文件'), size: Number(f.size) || 0 })
      }
    }
    return out
  }
  const raw = body as { files?: Array<{ name: string; size?: number }> } | null
  if (raw?.files && Array.isArray(raw.files)) {
    return raw.files.map((f) => ({ name: String(f.name ?? '未命名文件'), size: Number(f.size) || 0 }))
  }
  return []
}

/** 暂存文件的正文占位：按文件名生成可切分的文本，让预览真的"基于用户文件" */
function stagedText(upload: StagedUpload): string {
  if (upload.text) return upload.text
  const base = upload.name.replace(/\.[^.]+$/, '')
  return (
    `「${base}」的内容提要。` +
    `本文件共约 ${upload.size} 字节，按段落与语义边界切分后进入知识库。` +
    `第一条说明适用范围与职责分工。第二条给出操作流程与质量要求。` +
    `第三条约定例外情形的审批路径。第四条明确归档与保存期限。` +
    `第五条说明本文件的解释权与生效日期。`
  ).repeat(3)
}

/**
 * #11 把用户确认过的切分计划落成真实分段。
 * 这是"预览里的可编辑生效"的唯一通道 —— 不再只改本地数组。
 */
function applySegmentationPlan(doc: DocumentItem, plan: SegmentationPlanItem[]) {
  doc.segmentCount = plan.length
  doc.processedSegments = 0
  doc.status = 'PENDING_EMBEDDING'
  doc.charCount = plan.reduce((s, p) => s + p.content.length, 0)
  // 用户确认的切分计划 = 解析正文（预览里看到的即解析产物）
  doc.parsedContent = plan.map((p) => (p.title ? `## ${p.title}\n\n` : '') + p.content).join('\n\n')
  for (const p of plan) {
    const seg: Segment = {
      id: `seg_${doc.id}_${p.ordinal}`,
      documentId: doc.id,
      ordinal: p.ordinal,
      // ★ ordinal 是 0 起的数据口径，展示给人看时 +1（见 api/ingestion.ts 的口径注释）
      title: p.title || `${doc.name.replace(/\.[^.]+$/, '')} · 第 ${p.ordinal + 1} 段`,
      content: p.content,
      charCount: p.content.length,
      enabled: true,
      embedding: 'PENDING_EMBEDDING'
    }
    const idx = segments.findIndex((s) => s.id === seg.id)
    if (idx >= 0) segments.splice(idx, 1, seg)
    else segments.push(seg)
  }
  doc.updatedAt = new Date().toISOString()
  touchKbCounters(doc.kbId)
}

const findDoc = (id: string) => {
  const doc = documents.find((d) => d.id === id)
  if (!doc) throw new ApiError(ErrorCode.NOT_FOUND, `文档「${id}」不存在`)
  return doc
}

const findSeg = (id: string) => {
  const seg = segments.find((s) => s.id === id)
  if (!seg) throw new ApiError(ErrorCode.NOT_FOUND, `分段「${id}」不存在`)
  return seg
}

const touchKbCounters = (kbId: string) => {
  const kb = knowledgeBases.find((k) => k.id === kbId)
  if (!kb) return
  const docs = documents.filter((d) => d.kbId === kbId)
  kb.documentCount = docs.length
  kb.segmentCount = docs.reduce((sum, d) => sum + d.segmentCount, 0)
  kb.charCount = docs.reduce((sum, d) => sum + d.charCount, 0)
  // 有向量化内容 → 锁定向量模型
  if (kb.segmentCount > 0) kb.embeddingLocked = true
}

/** 进行中的向量化定时器，按文档 id 记录，避免重复启动 */
const embedTimers = new Map<string, ReturnType<typeof setInterval>>()

const stopEmbedTimer = (id: string) => {
  const t = embedTimers.get(id)
  if (t) clearInterval(t)
  embedTimers.delete(id)
}

/** 模拟向量化：从 processedSegments 推进到 segmentCount */
const runEmbedding = (doc: DocumentItem) => {
  stopEmbedTimer(doc.id)
  doc.status = 'EMBEDDING'
  doc.failReason = null
  doc.failHint = null

  const timer = setInterval(() => {
    doc.processedSegments = Math.min(
      doc.segmentCount,
      doc.processedSegments + EMBED_STEP
    )
    doc.updatedAt = new Date().toISOString()

    if (doc.processedSegments >= doc.segmentCount) {
      stopEmbedTimer(doc.id)
      doc.status = 'SUCCEEDED'
      for (const seg of segments.filter((s) => s.documentId === doc.id)) {
        seg.embedding = 'EMBEDDED'
      }
      touchKbCounters(doc.kbId)
    }
  }, EMBED_TICK_MS)
  embedTimers.set(doc.id, timer)
}

/** #10 单段重新向量化：先置「向量化中」，一拍后落「已向量化」 */
const segEmbedTimers = new Map<string, ReturnType<typeof setTimeout>>()

const runSegmentEmbedding = (seg: Segment) => {
  const prev = segEmbedTimers.get(seg.id)
  if (prev) clearTimeout(prev)
  seg.embedding = 'EMBEDDING'
  const timer = setTimeout(() => {
    seg.embedding = 'EMBEDDED'
    segEmbedTimers.delete(seg.id)
  }, EMBED_TICK_MS * 2)
  segEmbedTimers.set(seg.id, timer)
}

/**
 * #10 文档级重新向量化：该文档下所有分段重跑。
 * 回到「向量化中」而不是直接成功 —— 界面要能看到真实过渡态。
 */
const runDocumentReembed = (doc: DocumentItem) => {
  const segs = segments.filter((s) => s.documentId === doc.id)
  for (const s of segs) runSegmentEmbedding(s)
  doc.segmentCount = segs.length
  doc.processedSegments = 0
  doc.status = 'EMBEDDING'
  doc.failReason = null
  doc.failHint = null
  doc.updatedAt = new Date().toISOString()
  stopEmbedTimer(doc.id)
  const timer = setInterval(() => {
    doc.processedSegments = Math.min(doc.segmentCount, doc.processedSegments + EMBED_STEP)
    doc.updatedAt = new Date().toISOString()
    if (doc.processedSegments >= doc.segmentCount) {
      stopEmbedTimer(doc.id)
      doc.status = 'SUCCEEDED'
      for (const s of segments.filter((x) => x.documentId === doc.id)) {
        s.embedding = 'EMBEDDED'
      }
      touchKbCounters(doc.kbId)
    }
  }, EMBED_TICK_MS)
  embedTimers.set(doc.id, timer)
}

/** 模拟解析 + 切分：待解析 → 分段中 → 待向量化 */
const runParse = (doc: DocumentItem, opts?: { failSplit?: boolean }) => {
  doc.status = 'PENDING_PARSE'
  doc.failReason = null
  doc.failHint = null
  // 解析开始前清空上次正文 —— 「重试」后不能把旧结果冒充新解析
  doc.parsedContent = ''

  setTimeout(() => {
    doc.status = 'SEGMENTING'
    doc.updatedAt = new Date().toISOString()

    setTimeout(() => {
      if (opts?.failSplit) {
        doc.status = 'SEGMENT_FAILED'
        doc.segmentCount = 0
        doc.processedSegments = 0
        doc.failReason = '该 PDF 为扫描件，未检测到文本层，无法提取内容。'
        doc.failHint = '建议：转为可检索 PDF 或改用 Markdown 格式后重新上传。'
        // 扫描件：解析正文为空串（与 V14 口径一致，「连半个字都没解出来」）
        doc.parsedContent = ''
        doc.charCount = 0
      } else {
        // 按文件大小粗算分段数，制造真实感
        const count = Math.max(3, Math.round(doc.charCount / 450))
        doc.segmentCount = count
        doc.processedSegments = 0
        doc.status = 'PENDING_EMBEDDING'
        // ★ 解析成功：写入带标题/列表/表格的 Markdown，供「解析结果」按结构渲染
        doc.parsedContent = [
          `# ${doc.name.replace(/\.[^.]+$/, '')}`,
          '',
          '## 正文摘录',
          '',
          `这是从「${doc.name}」解析出的正文，用于验证解析结果视图的结构化渲染。`,
          '',
          '### 要点',
          '',
          '- 要点一：解析链产物是 Markdown',
          '- 要点二：标题 / 列表 / 表格按结构渲染',
          '',
          '| 项目 | 说明 |',
          '| --- | --- |',
          '| 解析器 | Tika → Markdown |',
          '| 展示 | MdPreview 结构渲染 |',
          '',
          '1. 上传文档',
          '2. 查看解析结果',
          '3. 确认后进入分段'
        ].join('\n')
        for (let i = 0; i < Math.min(count, 4); i += 1) {
          const seg: Segment = {
            id: `seg_${doc.id}_${i}`,
            documentId: doc.id,
            // ★ 0 起（与真后端入库即分块一致）
            ordinal: i,
            title: `${doc.name.replace(/\.[^.]+$/, '')} · 第 ${i + 1} 段`,
            content: `这是从「${doc.name}」切分出的第 ${i + 1} 段内容，用于验证入库流水线的分段阶段。`,
            charCount: 40 + i * 8,
            enabled: true,
            embedding: 'PENDING_EMBEDDING'
          }
          if (!segments.some((s) => s.id === seg.id)) segments.push(seg)
        }
      }
      doc.updatedAt = new Date().toISOString()
      touchKbCounters(doc.kbId)
    }, SPLIT_MS)
  }, PARSE_MS)
}

/** 切分预览：按 segmentLength / overlap 生成可编辑预览，超长段标 oversized */
const buildPreview = (
  body: PreviewSegmentationBody,
  sourceText: string
): PreviewSegmentationResult => {
  const limit = body.segmentLength > 0 ? body.segmentLength : 500

  // ★ 与真后端同口径：超上限 / 非法重叠必须**明确拒绝**，绝不静默夹紧。
  //   （静默夹紧 = 用户以为按他的参数切了、实际不是 —— 与「收下但不用」同族的病）
  if (limit > MODEL_LIMIT) {
    throw new ApiError(
      400,
      `分段长度 ${limit} 超过当前向量模型上限 ${MODEL_LIMIT} 字`,
      `请调小分段长度至 ${MODEL_LIMIT} 字以内，或切换至长上下文向量模型`
    )
  }
  if (body.overlap < 0 || body.overlap >= limit) {
    throw new ApiError(
      400,
      `重叠必须满足 0 ≤ overlap < segmentLength，当前 overlap=${body.overlap}、segmentLength=${limit}`
    )
  }
  const overlap = body.overlap

  // ★ 切的就是调用方给的正文（真文档的解析产物 / 暂存文件）——
  //   不再拿内置语料兜底：那会让 mock 的「预览」与真栈根本不在比同一条链（调用方已保证非空）
  const text = sourceText

  // 按句号/换行粗切，再按 limit 合并 —— 足够演示「可编辑 + 超长标红」
  const sentences = text.split(/(?<=[。！？；\n])/).filter((s) => s.trim())
  const chunks: string[] = []
  let buf = ''
  for (const s of sentences) {
    if (buf && buf.length + s.length > limit) {
      chunks.push(buf)
      // 重叠：把尾部一小段带到下一块
      buf = overlap > 0 ? buf.slice(-overlap) + s : s
    } else {
      buf += s
    }
  }
  if (buf.trim()) chunks.push(buf)

  // ★ 与真后端同口径：ordinal 从 **0** 起（数据/契约口径），界面展示用 ordinal + 1
  const result = chunks.map((content, index) => {
    const charCount = content.length
    return {
      ordinal: index,
      content,
      charCount,
      // ★ 契约 oversized 对应真后端的 oversizedRisk（L4 硬切标记）：
      //   这里"单句就超限、只能整句硬塞进一块"正是同一种情形 —— 语义边界没能守住。
      //   用 limit（而不是 MODEL_LIMIT）判，才与「切不动了」这件事对应。
      oversized: charCount > limit
    }
  })

  return {
    segmentCount: result.length,
    charCount: text.length,
    modelLimit: MODEL_LIMIT,
    segments: result,
    // ★ T249.2（12 §2.4）：噪声剥离的行数。mock 里给一个稳定的非 0 值，
    //   让「已剔除 N 行页眉 / 页脚 / 页码 / 目录」这条渲染路径在 mock 下也能被验到
    //   （真栈上它是后端算的：跨页重复行 ≥3 次且 <60 字、独立页码、连续目录行）。
    removedNoiseLines: 3
  }
}

export const ingestionRoutes: MockRoute[] = [
  [
    'GET',
    '/knowledge-bases/:kbId/documents',
    ({ path, query, token }) => {
      const kbId = path.kbId
      gateKb(kbId, token, PERM.knowledgeView, '知识库', kbId)
      const keyword = String(query.keyword ?? '')
      const status = query.status ? String(query.status) : ''
      // ★ `#56`：归属三态筛选 —— ★ 必须与真后端同语义（真后端是同一条谓词同时决定列表与 total）
      const orgAssignment = query.orgAssignment ? String(query.orgAssignment) : ''

      const list = documents
        .filter((d) => d.kbId === kbId)
        .filter((d) => !keyword || d.name.includes(keyword))
        .filter((d) => !status || d.status === status)
        .filter((d) => {
          if (orgAssignment === 'UNASSIGNED') return !d.orgId
          if (orgAssignment === 'ASSIGNED') return !!d.orgId
          return true
        })

      return paginate(list, query)
    }
  ],

  [
    // ★★ `#150` 指定/变更责任部门 —— ★ mock 是契约的第二种实现，少了它 `VITE_USE_MOCK=true` 下
    //   「指定」会变成一条**没人接的请求**（页面看起来点了、什么都没发生）。
    //   ★ 校验与真后端同口径：部门不存在 ⇒ 400（不是静默写进去）；文档不在这个库 ⇒ 404。
    'PATCH',
    '/knowledge-bases/:kbId/documents/:documentId/org',
    ({ path, body, token }) => {
      const kbId = path.kbId
      gateKb(kbId, token, PERM.documentUpload, '知识库', kbId)
      const documentId = path.documentId
      const target = documents.find((d) => d.id === documentId && d.kbId === kbId)
      if (!target) {
        throw new ApiError(ErrorCode.NOT_FOUND, '文档不存在', '它可能已被删除，或者不在这个知识库里')
      }
      const orgId = String((body as { orgId?: string } | null)?.orgId ?? '')
      if (!orgId) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '责任部门必填', '请从组织树选责任部门')
      }
      if (!mockOrgs.some((o) => o.id === orgId)) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `责任部门不存在：${orgId}`,
          '请从组织树重新选择 —— 它可能已被删除'
        )
      }
      target.orgId = orgId
      return target
    }
  ],

  [
    'POST',
    '/knowledge-bases/:kbId/documents',
    ({ path, body, token }) => {
      const kbId = path.kbId
      gateKb(kbId, token, PERM.documentUpload, '知识库', kbId)

      // #11 新路径：JSON 体带 uploadIds / segmentationPlan / names → 回执形态
      // ★ 真栈建档最小形态是 `names`（文件名清单）；mock 与它对齐，否则两侧契约会漂
      const importBody = body as ImportDocumentsBody | null
      const isImport =
        !!importBody &&
        typeof importBody === 'object' &&
        !(typeof (importBody as FormData).entries === 'function') &&
        (Array.isArray(importBody.uploadIds) ||
          Array.isArray(importBody.segmentationPlan) ||
          Array.isArray(importBody.names))

      if (isImport && importBody) {
        // ★ 建档必填三项 + ceiling（12 §5.1 / 13 §6）—— 与后端同文案
        assertDocMeta(importBody)
        assertWithinCeiling(kbId, String(importBody.classification))

        const accepted: DocumentItem[] = []
        const rejected: UploadReceipt['rejected'] = []

        // names = 轻量建档（当前唯一落地形态）；uploadIds / segmentationPlan = 暂存导入形态（后端尚未消费）
        const nameList = importBody.names ?? []
        const stagedShapeRequested = importBody.uploadIds != null || importBody.segmentationPlan != null

        // ★ 禁止静默忽略：暂存形态出现但 names 缺失 ⇒ 明确 400（与后端 RegisterDocumentsBody 同文案）
        if (stagedShapeRequested && !nameList.length) {
          throw new ApiError(
            400,
            '暂存上传尚未支持，请用 names 提供文档名',
            'uploadIds / segmentationPlan 是暂存导入形态（stageUploads 的产物），后端尚未消费——收下但不用会让人以为导入生效了。当前请用 names 提供文件名清单建档'
          )
        }

        if (!nameList.length) {
          throw new ApiError(ErrorCode.BAD_REQUEST, '没有可导入的暂存文件', '请先选择文件并暂存上传')
        }

        // ★ names 为准：同时给时只按 names 建档（uploadIds 后端/mock 都不消费，保留字段等上传链落地）
        const meta = {
          docType: String(importBody.docType ?? ''),
          orgId: String(importBody.orgId ?? ''),
          classification: importBody.classification
        }

        for (const name of nameList) {
          const doc: DocumentItem = {
            id: `doc${Date.now()}_n${accepted.length}`,
            kbId,
            name,
            source: 'local',
            status: 'PENDING_PARSE',
            segmentCount: 0,
            processedSegments: 0,
            charCount: 10000,
            enabled: true,
            failReason: null,
            failHint: null,
            parsedContent: '',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            docType: meta.docType,
            orgId: meta.orgId,
            classification: meta.classification
          }
          documents.unshift(doc)
          const isScan = /扫描|scan/i.test(name)
          if (isScan) {
            runParse(doc, { failSplit: true })
          } else if (importBody.segmentationPlan?.length) {
            // #11 用户在预览里确认/编辑过的切分计划被真正采纳
            applySegmentationPlan(doc, importBody.segmentationPlan)
          } else {
            runParse(doc, { failSplit: false })
          }
          accepted.push(doc)
        }

        touchKbCounters(kbId)
        return { accepted, rejected } satisfies UploadReceipt
      }

      // 旧路径：multipart 直传文件，响应仍是 DocumentItem[]（契约不改）
      const raw = body as { files?: Array<{ name: string; size?: number }> } | FormData | null
      let files: Array<{ name: string; size?: number }> = []
      if (raw && typeof raw === 'object' && 'files' in raw && Array.isArray((raw as { files: unknown }).files)) {
        files = (raw as { files: Array<{ name: string; size?: number }> }).files
      }

      if (!files.length) {
        // 浏览器 FormData 在 mock 里拿不到 File 对象时，给一条可识别的占位
        files = [{ name: `上传文档_${Date.now()}.md`, size: 12000 }]
      }

      const created: DocumentItem[] = files.map((f, i) => {
        const doc: DocumentItem = {
          id: `doc${Date.now()}_${i}`,
          kbId,
          name: f.name,
          source: 'local',
          status: 'PENDING_PARSE',
          segmentCount: 0,
          processedSegments: 0,
          charCount: f.size ?? 10000,
          enabled: true,
          failReason: null,
          failHint: null,
          parsedContent: '',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        }
        documents.unshift(doc)
        // 扫描件命名约定：构造一条分段失败路径，让「失败原因 + 重试」可被验证
        const isScan = /扫描|scan/i.test(f.name)
        runParse(doc, { failSplit: isScan })
        return doc
      })

      touchKbCounters(kbId)
      return created
    }
  ],

  [
    'GET',
    '/knowledge-bases/:kbId/ingestion-progress',
    ({ path, token }) => {
      const kbId = path.kbId
      gateKb(kbId, token, PERM.knowledgeView, '知识库', kbId)
      const docs = documents.filter((d) => d.kbId === kbId)

      const stages = ([
        'PENDING_PARSE',
        'SEGMENTING',
        'PENDING_EMBEDDING',
        'EMBEDDING',
        'SUCCEEDED',
        'SEGMENT_FAILED',
        'EMBEDDING_FAILED'
      ] as const).map((status) => ({
        status,
        count: docs.filter((d) => d.status === status).length
      }))

      const done = docs.filter((d) => d.status === 'SUCCEEDED').length
      const failed = docs.filter((d) => d.status === 'SEGMENT_FAILED' || d.status === 'EMBEDDING_FAILED').length
      const running = docs.some(
        (d) => d.status === 'PENDING_PARSE' || d.status === 'SEGMENTING' || d.status === 'EMBEDDING'
      )

      return {
        kbId,
        total: docs.length,
        done,
        failed,
        stages,
        running
      }
    }
  ],

  [
    'PATCH',
    '/documents/:id',
    ({ path, body, token }) => {
      const doc = findDoc(path.id)
      gateKb(doc.kbId, token, PERM.documentUpload, '文档', doc.id)
      const { name } = body as { name?: string }
      if (name?.trim()) doc.name = name.trim()
      doc.updatedAt = new Date().toISOString()
      return doc
    }
  ],

  [
    'PATCH',
    '/documents/:id/enabled',
    ({ path, body, token }) => {
      const doc = findDoc(path.id)
      gateKb(doc.kbId, token, PERM.documentUpload, '文档', doc.id)
      const { enabled } = body as { enabled: boolean }
      doc.enabled = Boolean(enabled)
      doc.updatedAt = new Date().toISOString()
      return doc
    }
  ],

  [
    'DELETE',
    '/documents/:id',
    ({ path, token }) => {
      const doc = findDoc(path.id)
      gateKb(doc.kbId, token, PERM.documentUpload, '文档', doc.id)
      stopEmbedTimer(doc.id)
      documents.splice(documents.indexOf(doc), 1)
      // 连带删掉该文档下的分段
      for (let i = segments.length - 1; i >= 0; i -= 1) {
        if (segments[i].documentId === doc.id) segments.splice(i, 1)
      }
      touchKbCounters(doc.kbId)
      return undefined
    }
  ],

  [
    'POST',
    '/documents/:id/retry',
    ({ path, token }) => {
      const doc = findDoc(path.id)
      gateKb(doc.kbId, token, PERM.documentUpload, '文档', doc.id)
      if (doc.status !== 'SEGMENT_FAILED' && doc.status !== 'EMBEDDING_FAILED') {
        throw new ApiError(ErrorCode.BAD_REQUEST, `文档当前状态为「${doc.status}」，无需重试`)
      }

      // 从失败阶段继续：分段失败 → 重新解析；向量化失败 → 从断点继续向量化
      if (doc.status === 'SEGMENT_FAILED') {
        // 重试后不再失败（用户已按建议处理过文件）
        runParse(doc, { failSplit: false })
      } else {
        runEmbedding(doc)
      }
      return doc
    }
  ],

  [
    'POST',
    '/documents/:id/embedding',
    ({ path, token }) => {
      const doc = findDoc(path.id)
      gateKb(doc.kbId, token, PERM.documentUpload, '文档', doc.id)
      if (doc.status !== 'PENDING_EMBEDDING') {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `文档当前状态为「${doc.status}」，不能开始向量化`,
          '仅「待向量化」状态的文档可以启动向量化'
        )
      }
      if (doc.segmentCount <= 0) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '该文档没有可向量化的分段')
      }
      runEmbedding(doc)
      return doc
    }
  ],

  [
    'POST',
    '/segmentation/preview',
    ({ body, token }) => {
      const input = body as PreviewSegmentationBody & { content?: string }

      // ★ 与真后端同口径：strategy 必须被**消费**（本版只实现 AUTO）。
      //   mock 若照旧接受 CUSTOM，闸门就是绿的、而真栈 400 —— 那正是「mock 宽松掩盖真后端严口径」
      if (input.strategy && input.strategy !== 'AUTO') {
        throw new ApiError(
          400,
          `不支持的切分策略：${input.strategy}`,
          'CUSTOM（按固定长度切分）本版尚未实现；请使用 AUTO（按段落与语义边界切分）'
        )
      }

      // #11 优先走暂存 uploadIds —— 预览必须基于**用户自己的文件**，不是内置语料
      let sourceText = input.content ?? ''
      if (input.uploadIds?.length) {
        const parts: string[] = []
        for (const uid of input.uploadIds) {
          const up = stagedUploads.get(uid)
          if (!up) throw new ApiError(ErrorCode.BAD_REQUEST, `暂存文件「${uid}」不存在或已过期`, '请重新选择文件后再试')
          parts.push(stagedText(up))
        }
        sourceText = parts.join('\n')
      } else if (input.documentId) {
        const doc = documents.find((d) => d.id === input.documentId)
        // ★ 与真后端同口径：不存在/不可见 ⇒ 404（不泄漏存在性），不再"保持纯计算预览"的旧行为
        if (!doc) throw new ApiError(404, '文档不存在', '它可能已被删除，或你没有访问权限')
        gateKb(doc.kbId, token, PERM.segmentEdit, '文档', doc.id)
        // ★ 与真后端同口径：预览读的是**该文档的解析产物**（parsedContent）——
        //   不是已有分段、更不是内置语料。否则 mock 的「预览」与真栈根本不在比同一条链。
        sourceText = doc.parsedContent ?? ''
        if (!sourceText.trim()) {
          throw new ApiError(
            400,
            '文档解析尚未完成，无法预览切分',
            '请等待文档解析完成后再试；若解析失败，请查看文档的失败原因并修复后重新上传'
          )
        }
      } else if (!input.segmentationPlan?.length) {
        // ★ 与真后端同口径：既没有文档、又没有计划 ⇒ 400（不是拿内置语料假装切了）
        throw new ApiError(
          400,
          '请提供 documentId 或 segmentationPlan',
          '预览需要已解析的文档正文（documentId），或用户确认过的切分计划（segmentationPlan）'
        )
      }

      // #11 用户已确认的切分计划（含预览编辑）优先于自动切分
      if (input.segmentationPlan?.length) {
        const result = input.segmentationPlan.map((p) => {
          const charCount = p.content.length
          // ★ 与真后端同口径：用户粘进超长内容必须**拒绝**（SEG-I1），不能只标红。
          //   ⚠️ 边界略松：真后端按 searchText（含结构化前缀）全长判，mock 这里只看正文长度。
          if (charCount > MODEL_LIMIT) {
            throw new ApiError(
              3002,
              `该内容 ${charCount} 字，超出当前向量模型上限 ${MODEL_LIMIT} 字。`,
              '建议按段落切分为多段，或切换至长上下文向量模型。'
            )
          }
          return {
            ordinal: p.ordinal,
            content: p.content,
            charCount,
            oversized: false
          }
        })
        return {
          segmentCount: result.length,
          charCount: result.reduce((s, x) => s + x.charCount, 0),
          modelLimit: MODEL_LIMIT,
          segments: result
        }
      }

      return buildPreview(input, sourceText)
    }
  ],

  [
    'GET',
    '/segments',
    ({ query, token }) => {
      const documentId = query.documentId ? String(query.documentId) : ''
      const kbId = query.kbId ? String(query.kbId) : ''
      const onlyOversized = query.onlyOversized === true || query.onlyOversized === 'true'
      const keyword = String(query.keyword ?? '')

      const list = visibleSegments(currentSubjects(token))
        .filter((s) => !documentId || s.documentId === documentId)
        // P1 跨库批量：限定知识库后不会扫到别的库的分段
        .filter((s) => {
          if (!kbId) return true
          const doc = documents.find((d) => d.id === s.documentId)
          return doc?.kbId === kbId
        })
        .filter((s) => !keyword || s.title.includes(keyword) || s.content.includes(keyword))
        // SEG-I1：超长 = 字数超过向量模型上限
        .filter((s) => !onlyOversized || s.charCount > MODEL_LIMIT)

      return paginate(list, query)
    }
  ],

  [
    'PUT',
    '/segments/:id',
    ({ path, body, token }) => {
      const seg = findSeg(path.id)
      gateKb(kbIdOfSeg(seg.id), token, PERM.segmentEdit, '分段', seg.id)
      const input = body as { title?: string; content?: string }
      if (input.title !== undefined) seg.title = input.title
      if (input.content !== undefined) {
        seg.content = input.content
        seg.charCount = input.content.length
        // 内容变了需要重新向量化
        seg.embedding = 'PENDING_EMBEDDING'
      }
      return seg
    }
  ],

  [
    'PATCH',
    '/segments/:id/enabled',
    ({ path, body, token }) => {
      const seg = findSeg(path.id)
      gateKb(kbIdOfSeg(seg.id), token, PERM.segmentEdit, '分段', seg.id)
      const { enabled } = body as { enabled: boolean }
      seg.enabled = Boolean(enabled)
      return seg
    }
  ],

  [
    'DELETE',
    '/segments/:id',
    ({ path, token }) => {
      const seg = findSeg(path.id)
      gateKb(kbIdOfSeg(seg.id), token, PERM.segmentEdit, '分段', seg.id)
      segments.splice(segments.indexOf(seg), 1)
      touchKbCounters(documents.find((d) => d.id === seg.documentId)?.kbId ?? '')
      return undefined
    }
  ],

  [
    'POST',
    '/segments/batch-move',
    ({ body, token }) => {
      const { segmentIds, targetDocumentId } = body as {
        segmentIds: string[]
        targetDocumentId: string
      }
      // 目标文档与每个源分段都要过判定：迁移会同时改两边
      const target = findDoc(targetDocumentId)
      gateKb(target.kbId, token, PERM.segmentEdit, '文档', target.id)
      if (!segmentIds?.length) throw new ApiError(ErrorCode.BAD_REQUEST, '请先选择要迁移的分段')

      let moved = 0
      for (const id of segmentIds) {
        const seg = segments.find((s) => s.id === id)
        if (!seg) continue
        gateKb(kbIdOfSeg(id), token, PERM.segmentEdit, '分段', id)
        const sourceDocId = seg.documentId
        seg.documentId = targetDocumentId
        seg.embedding = 'PENDING_EMBEDDING'
        moved += 1
        // 源文档分段数 -1，目标 +1
        const src = documents.find((d) => d.id === sourceDocId)
        if (src) {
          src.segmentCount = Math.max(0, src.segmentCount - 1)
          touchKbCounters(src.kbId)
        }
      }
      target.segmentCount = segments.filter((s) => s.documentId === targetDocumentId).length
      touchKbCounters(target.kbId)
      return { moved }
    }
  ],

  /* ── #11 暂存上传 · P1 上传策略 ── */

  [
    'POST',
    '/knowledge-bases/:kbId/uploads',
    ({ path, body, token }) => {
      const kbId = path.kbId
      gateKb(kbId, token, PERM.documentUpload, '知识库', kbId)

      const files = extractFiles(body)
      if (!files.length) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '未读到待上传的文件', '请重新选择文件后再试')
      }
      if (files.length > UPLOAD_POLICY.maxFileCount) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `一次最多上传 ${UPLOAD_POLICY.maxFileCount} 个文件`,
          '请分批上传'
        )
      }

      const uploadIds: string[] = []
      for (const f of files) {
        const ext = f.name.includes('.') ? f.name.slice(f.name.lastIndexOf('.')).toLowerCase() : ''
        if (UPLOAD_POLICY.acceptedExtensions.length && ext && !UPLOAD_POLICY.acceptedExtensions.includes(ext)) {
          throw new ApiError(
            ErrorCode.BAD_REQUEST,
            `不支持的文件类型「${ext}」`,
            `支持：${UPLOAD_POLICY.acceptedExtensions.join(' / ')}`
          )
        }
        if (f.size > UPLOAD_POLICY.maxFileSizeMB * 1024 * 1024) {
          throw new ApiError(
            ErrorCode.BAD_REQUEST,
            `文件「${f.name}」超过 ${UPLOAD_POLICY.maxFileSizeMB}MB 上限`,
            '请压缩或拆分后再传'
          )
        }
        const id = `up_${Date.now().toString(36)}_${uploadIds.length}`
        stagedUploads.set(id, { id, kbId, name: f.name, size: f.size, text: '' })
        uploadIds.push(id)
      }
      return { uploadIds }
    }
  ],

  [
    'GET',
    '/knowledge-bases/:kbId/upload-policy',
    ({ path, token }) => {
      gateKb(path.kbId, token, PERM.knowledgeView, '知识库', path.kbId)
      return UPLOAD_POLICY
    }
  ],

  /* ── #10 重向量化 ×3 ── */

  [
    'POST',
    '/segments/:id/embedding',
    ({ path, token }) => {
      const seg = findSeg(path.id)
      gateKb(kbIdOfSeg(seg.id), token, PERM.segmentEdit, '分段', seg.id)
      // 编辑后是「待向量化」；失败态也允许重跑。已向量化的强制重跑也放行（用户显式动作）
      if (seg.embedding === 'EMBEDDING') {
        throw new ApiError(ErrorCode.BAD_REQUEST, '该分段正在向量化中，无需重复提交')
      }
      runSegmentEmbedding(seg)
      return seg
    }
  ],

  [
    'POST',
    '/documents/:id/reembed',
    ({ path, token }) => {
      const doc = findDoc(path.id)
      gateKb(doc.kbId, token, PERM.documentUpload, '文档', doc.id)
      const segs = segments.filter((s) => s.documentId === doc.id)
      if (!segs.length) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '该文档没有可向量化的分段')
      }
      runDocumentReembed(doc)
      return doc
    }
  ],

  [
    'POST',
    '/knowledge-bases/:kbId/reembed',
    ({ path, token }) => {
      const kbId = path.kbId
      gateKb(kbId, token, PERM.documentUpload, '知识库', kbId)
      const docs = documents.filter((d) => d.kbId === kbId)
      let queued = 0
      for (const doc of docs) {
        const segs = segments.filter((s) => s.documentId === doc.id)
        if (!segs.length) continue
        runDocumentReembed(doc)
        queued += segs.length
      }
      return { queued }
    }
  ],

  /* ── P1 批量删除（回执区分 deleted / failed） ── */

  [
    'DELETE',
    '/segments/batch',
    ({ body, token }) => {
      const { ids } = body as { ids?: string[] }
      if (!ids?.length) throw new ApiError(ErrorCode.BAD_REQUEST, '请先选择要删除的分段')

      let deleted = 0
      const failed: Array<{ id: string; reason: string }> = []
      for (const id of ids) {
        const seg = segments.find((s) => s.id === id)
        if (!seg) {
          failed.push({ id, reason: '分段不存在' })
          continue
        }
        try {
          gateKb(kbIdOfSeg(id), token, PERM.segmentEdit, '分段', id)
        } catch (error) {
          failed.push({ id, reason: error instanceof ApiError ? error.message : '无权删除' })
          continue
        }
        segments.splice(segments.indexOf(seg), 1)
        deleted += 1
      }
      touchKbCounters('')
      // 逐个文档回刷计数（touchKbCounters('') 不够）
      for (const doc of documents) {
        doc.segmentCount = segments.filter((s) => s.documentId === doc.id).length
        touchKbCounters(doc.kbId)
      }
      return { deleted, failed }
    }
  ]
]
