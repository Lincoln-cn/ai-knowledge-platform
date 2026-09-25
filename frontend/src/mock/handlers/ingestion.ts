import { ApiError, ErrorCode } from '@/api/types'
import type {
  DocumentItem,
  PreviewSegmentationBody,
  PreviewSegmentationResult,
  Segment
} from '@/api/ingestion'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { documents, knowledgeBases, segments } from '../fixtures/knowledge'
import { PERM } from '@/constants/permissions'
import { currentSubjects, requireVisible, visibleTo, type SubjectSet } from '../policy'

/**
 * 文档 / 分段**没有自己的归属字段**（07 §8.1 的三类资源是知识库 / 应用 / 会话），
 * 所以它们的可见性**继承所属知识库** —— 这正是 §8.4 说的「加一类资源 = 复用同一判定」：
 * 判定函数一行没改，只是换了个入口问同一个问题。
 */
const kbIdOfDoc = (docId: string) => documents.find((d) => d.id === docId)?.kbId

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
  doc.status = '向量化中'
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
      doc.status = '成功'
      for (const seg of segments.filter((s) => s.documentId === doc.id)) {
        seg.embedding = '已向量化'
      }
      touchKbCounters(doc.kbId)
    }
  }, EMBED_TICK_MS)
  embedTimers.set(doc.id, timer)
}

/** 模拟解析 + 切分：待解析 → 分段中 → 待向量化 */
const runParse = (doc: DocumentItem, opts?: { failSplit?: boolean }) => {
  doc.status = '待解析'
  doc.failReason = null
  doc.failHint = null

  setTimeout(() => {
    doc.status = '分段中'
    doc.updatedAt = new Date().toISOString()

    setTimeout(() => {
      if (opts?.failSplit) {
        doc.status = '分段失败'
        doc.segmentCount = 0
        doc.processedSegments = 0
        doc.failReason = '该 PDF 为扫描件，未检测到文本层，无法提取内容。'
        doc.failHint = '建议：转为可检索 PDF 或改用 Markdown 格式后重新上传。'
      } else {
        // 按文件大小粗算分段数，制造真实感
        const count = Math.max(3, Math.round(doc.charCount / 450))
        doc.segmentCount = count
        doc.processedSegments = 0
        doc.status = '待向量化'
        for (let i = 0; i < Math.min(count, 4); i += 1) {
          const seg: Segment = {
            id: `seg_${doc.id}_${i + 1}`,
            documentId: doc.id,
            ordinal: i + 1,
            title: `${doc.name.replace(/\.[^.]+$/, '')} · 第 ${i + 1} 段`,
            content: `这是从「${doc.name}」切分出的第 ${i + 1} 段内容，用于验证入库流水线的分段阶段。`,
            charCount: 40 + i * 8,
            enabled: true,
            embedding: '待向量化'
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
  const overlap = Math.max(0, Math.min(body.overlap, limit - 1))

  // 用固定语料做切分演示，保证同一参数下结果稳定
  const text =
    sourceText ||
    '为规范合同管理，防范经营风险，保障公司合法权益，根据国家有关法律法规及公司章程，制定本制度。' +
      '本制度适用于公司及所属各单位对外签订的各类合同。' +
      '合同订立前应完成对方主体资格审查，包括营业执照、资质证书、法定代表人身份证明等。' +
      '重大合同应当组织法律、财务、业务联合评审，并留存评审记录。' +
      '付款方式应明确约定付款比例、付款节点与发票要求。前期付款比例原则上不超过合同总额的百分之三十，尾款须在验收合格后支付。' +
      '付款节点应与履约进度挂钩，不得以单纯时间节点作为唯一付款依据。逾期付款应约定违约责任与计算方式。' +
      '合同金额超过五十万元的，应约定分期付款并设置质量保证金。付款账户必须是对公账户，不接受个人账户收款要求。' +
      '涉及外币结算的，应明确汇率确定方式与汇率波动分担机制。预付款项须提供等额预付款保函或公司认可的担保方式。' +
      '财务部门有权对付款条款的合规性提出异议并要求修改。验收标准应量化、可检测，避免「符合要求」等模糊表述。' +
      '争议解决方式优先协商，协商不成的，约定向公司所在地人民法院提起诉讼。' +
      '合同原件由档案管理部门统一保管，业务部门留存复印件。合同履行完毕后应及时归档，保存期限不少于十年。'

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

  // 自定义模式下故意制造一条超长段，让 SEG-I1 在预览里可见
  if (body.strategy === 'custom' && chunks.length > 0) {
    chunks[0] = chunks[0] + '　'.repeat(Math.max(0, MODEL_LIMIT - chunks[0].length + 200))
  }

  const result = chunks.map((content, index) => {
    const charCount = content.length
    return {
      ordinal: index + 1,
      content,
      charCount,
      oversized: charCount > MODEL_LIMIT
    }
  })

  return {
    segmentCount: result.length,
    charCount: text.length,
    modelLimit: MODEL_LIMIT,
    segments: result
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

      const list = documents
        .filter((d) => d.kbId === kbId)
        .filter((d) => !keyword || d.name.includes(keyword))
        .filter((d) => !status || d.status === status)

      return paginate(list, query)
    }
  ],

  [
    'POST',
    '/knowledge-bases/:kbId/documents',
    ({ path, body, token }) => {
      const kbId = path.kbId
      gateKb(kbId, token, PERM.documentUpload, '知识库', kbId)

      // multipart：mock 侧按 body 里的文件名列表构造 DocumentItem
      // 真实后端从 multipart 解析；mock 允许传 { files: [{ name, size }] } 或 FormData 形状
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
          status: '待解析',
          segmentCount: 0,
          processedSegments: 0,
          charCount: f.size ?? 10000,
          enabled: true,
          failReason: null,
          failHint: null,
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
        '待解析',
        '分段中',
        '待向量化',
        '向量化中',
        '成功',
        '分段失败',
        '向量化失败'
      ] as const).map((status) => ({
        status,
        count: docs.filter((d) => d.status === status).length
      }))

      const done = docs.filter((d) => d.status === '成功').length
      const failed = docs.filter((d) => d.status === '分段失败' || d.status === '向量化失败').length
      const running = docs.some(
        (d) => d.status === '待解析' || d.status === '分段中' || d.status === '向量化中'
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
      if (doc.status !== '分段失败' && doc.status !== '向量化失败') {
        throw new ApiError(3010, `文档当前状态为「${doc.status}」，无需重试`)
      }

      // 从失败阶段继续：分段失败 → 重新解析；向量化失败 → 从断点继续向量化
      if (doc.status === '分段失败') {
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
      if (doc.status !== '待向量化') {
        throw new ApiError(
          3011,
          `文档当前状态为「${doc.status}」，不能开始向量化`,
          '仅「待向量化」状态的文档可以启动向量化'
        )
      }
      if (doc.segmentCount <= 0) {
        throw new ApiError(3012, '该文档没有可向量化的分段')
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

      // 已有文档：取文档下已有分段拼接；上传前：用请求体 content 或内置语料
      let sourceText = input.content ?? ''
      if (input.documentId) {
        const doc = documents.find((d) => d.id === input.documentId)
        // 文档存在才判可见性：传了不存在的 documentId 时保持"纯计算预览"的旧行为
        if (doc) gateKb(doc.kbId, token, PERM.segmentEdit, '文档', doc.id)
        const segs = segments.filter((s) => s.documentId === input.documentId)
        sourceText = segs.map((s) => s.content).join('\n')
        if (!sourceText) {
          sourceText = doc ? `${doc.name} 的正文内容占位……`.repeat(20) : ''
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
      const onlyOversized = query.onlyOversized === true || query.onlyOversized === 'true'
      const keyword = String(query.keyword ?? '')

      const list = visibleSegments(currentSubjects(token))
        .filter((s) => !documentId || s.documentId === documentId)
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
        seg.embedding = '待向量化'
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
      if (!segmentIds?.length) throw new ApiError(3013, '请先选择要迁移的分段')

      let moved = 0
      for (const id of segmentIds) {
        const seg = segments.find((s) => s.id === id)
        if (!seg) continue
        gateKb(kbIdOfSeg(id), token, PERM.segmentEdit, '分段', id)
        const sourceDocId = seg.documentId
        seg.documentId = targetDocumentId
        seg.embedding = '待向量化'
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
  ]
]
