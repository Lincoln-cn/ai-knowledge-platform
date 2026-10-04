import { ApiError, ErrorCode } from '@/api/types'
import type { HitItem, HitTestRequest, HitTestResult, RetrievalMode, RetrievalParams } from '@/api/retrieval'
import type { MockRoute } from '../dispatch'
import { hitCorpus, knowledgeBases, retrievalParams } from '../fixtures/knowledge'
import { PERM } from '@/constants/permissions'
import { currentSubjects, requireVisible } from '../policy'

/** 检索参数是知识库的一部分：读与写都先问同一个判定（07 §3.3 硬规矩 1） */
const gateKb = (kbId: string, token: string, action: string) =>
  requireVisible(
    knowledgeBases.find((k) => k.id === kbId),
    currentSubjects(token),
    action,
    '知识库',
    kbId
  )

/** 不同检索模式的分数偏移与截断策略 —— 让「对比模式」真的能看出差异 */
const MODE_PROFILE: Record<RetrievalMode, { scoreBoost: number; cutRatio: number; elapsed: number }> = {
  VECTOR: { scoreBoost: 0.0, cutRatio: 1.0, elapsed: 42 },
  FULLTEXT: { scoreBoost: -0.06, cutRatio: 0.8, elapsed: 18 },
  HYBRID: { scoreBoost: 0.03, cutRatio: 1.0, elapsed: 56 }
}

const findParams = (kbId: string): RetrievalParams => {
  const p = retrievalParams[kbId]
  // P1 新库检索参数：回**默认值**而不是 404 —— 否则设置面板刚建好的库会弹「不存在」
  if (!p) {
    const defaults: RetrievalParams = {
      mode: 'HYBRID',
      threshold: 0.6,
      topN: 5,
      noRefStrategy: 'REFUSE'
    }
    retrievalParams[kbId] = defaults
    return defaults
  }
  return p
}

/** 按问题关键字选语料；命中不到关键字时回落到第一条，避免界面空荡荡 */
const pickCorpus = (question: string) => {
  const q = question.toLowerCase()
  return (
    hitCorpus.find((c) => c.keywords.some((k) => q.includes(k.toLowerCase()))) ?? hitCorpus[0]
  )
}

const runHitTest = (body: HitTestRequest): HitTestResult => {
  const profile = MODE_PROFILE[body.mode] ?? MODE_PROFILE['HYBRID']
  const corpus = pickCorpus(body.question)

  // 先过滤阈值，再取 TopN —— 顺序反了会把噪声带进上下文
  const scored = corpus.items
    .map((item) => ({
      ...item,
      score: Number(Math.min(0.99, Math.max(0.01, item.base + profile.scoreBoost)).toFixed(2))
    }))
    .filter((item) => item.score >= body.threshold)
    .slice(0, Math.max(1, body.topN))
    .slice(0, Math.max(1, Math.round(corpus.items.length * profile.cutRatio)))

  const items: HitItem[] = scored.map((item, index) => ({
    rank: index + 1,
    segmentId: item.segmentId,
    title: item.title,
    docName: item.docName,
    kbName: item.kbName,
    score: item.score,
    content: item.content
  }))

  return {
    mode: body.mode,
    items,
    elapsedMs: profile.elapsed + Math.floor(Math.random() * 20)
  }
}

export const retrievalRoutes: MockRoute[] = [
  [
    'POST',
    '/retrieval/test',
    ({ body }) => {
      const input = body as HitTestRequest
      if (!input.question?.trim()) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '请输入测试问题')
      }
      if (input.threshold < 0 || input.threshold > 1) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '相似度阈值应在 0～1 之间')
      }
      if (!input.topN || input.topN < 1) {
        throw new ApiError(ErrorCode.BAD_REQUEST, 'TopN 至少为 1')
      }
      return runHitTest(input)
    }
  ],

  // 与 /knowledge-bases/:id 的 GET 同路径不同方法/后缀，放在 :id 通用路由之前无冲突
  [
    'GET',
    '/knowledge-bases/:kbId/retrieval',
    ({ path, token }) => {
      gateKb(path.kbId, token, PERM.knowledgeView)
      return findParams(path.kbId)
    }
  ],

  [
    'PUT',
    '/knowledge-bases/:kbId/retrieval',
    ({ path, body, token }) => {
      gateKb(path.kbId, token, PERM.knowledgeManage)
      const current = findParams(path.kbId)
      const input = body as Partial<RetrievalParams>
      const next: RetrievalParams = {
        mode: input.mode ?? current.mode,
        threshold: input.threshold ?? current.threshold,
        topN: input.topN ?? current.topN,
        noRefStrategy: input.noRefStrategy ?? current.noRefStrategy
      }
      if (next.threshold < 0 || next.threshold > 1) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '相似度阈值应在 0～1 之间')
      }
      if (next.topN < 1 || next.topN > 20) {
        throw new ApiError(ErrorCode.BAD_REQUEST, 'TopN 应在 1～20 之间')
      }
      retrievalParams[path.kbId] = next
      return next
    }
  ]
]
