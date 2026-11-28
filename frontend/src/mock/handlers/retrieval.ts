import { ApiError, ErrorCode } from '@/api/types'
import type {
  HitItem,
  HitTestRequest,
  HitTestResult,
  HitTestTrace,
  RetrievalEngineSettingsUpdate,
  RetrievalMode,
  RetrievalParams
} from '@/api/retrieval'
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

/** 查询理解的默认值。必须与后端同款（10-RAG-检索策略设计 §8.1 / §6.2）：
    两边不一致时，界面显示 3 而实际按别的值检索，调参会得出错误结论 */
const QUERY_UNDERSTANDING_DEFAULTS: Required<
  Pick<RetrievalParams, 'queryCount' | 'decomposeEnabled' | 'maxSubQuestions'>
> = {
  queryCount: 3,
  decomposeEnabled: true,
  maxSubQuestions: 3
}

/** 多跳轮数的硬顶（10-RAG §6.2 第 5 项 / §3.4.2）—— 与后端的越界校验逐字一致 */
const MAX_HOPS_CAP = 3

/** 多跳的触发条件之一：命中数 < 3（与后端 `RetrievalService.HOP_MIN_HITS` 逐字一致） */
const HOP_MIN_HITS = 3

/**
 * 检索引擎的**全局**运行参数（`/settings/retrieval`）。
 *
 * ★ 与按库的 {@link retrievalParams} 分开放：那几条描述"一个库怎么检索"，这个描述"这台机器怎么算"。
 * ★ 初值与后端 `application.yml` 的默认值逐字一致 —— mock 里写别的值，界面会显示一套不生效的数字。
 */
const engineSettings = {
  queryUnderstandingEnabled: true,
  queryUnderstandingRetry: true,
  rerankEnabled: true,
  rerankTopK: 10,
  rerankForwardBatch: 5,
  rerankMaxLength: 512,
  listwiseEnabled: false,
  listwiseTopK: 5,
  updatedAt: null as string | null
}

/** 全局权限门（与 `dictionaries.ts` 的 `requireManage` 同一形状：全局配置没有"哪一条"可判）。 */
function requirePerm(token: string, action: string, label: string): void {
  if (currentSubjects(token).permissions.has(action)) return
  throw new ApiError(ErrorCode.FORBIDDEN, `需要「${label}」权限`)
}

const findParams = (kbId: string): RetrievalParams => {
  const p = retrievalParams[kbId]
  // P1 新库检索参数：回**默认值**而不是 404 —— 否则设置面板刚建好的库会弹「不存在」
  if (!p) {
    const defaults: RetrievalParams = {
      mode: 'HYBRID',
      threshold: 0.6,
      topN: 5,
      noRefStrategy: 'REFUSE',
      ...QUERY_UNDERSTANDING_DEFAULTS
    }
    retrievalParams[kbId] = defaults
    return defaults
  }
  // 老 fixture 没有这三个字段：GET 要回默认值而不是省略（§8.1 要求 —— 前端已经有过
  // 「回落到硬编码值而被悄悄带偏」的教训），因此在这里补齐并写回，全程只有一种形状
  p.queryCount ??= QUERY_UNDERSTANDING_DEFAULTS.queryCount
  p.decomposeEnabled ??= QUERY_UNDERSTANDING_DEFAULTS.decomposeEnabled
  p.maxSubQuestions ??= QUERY_UNDERSTANDING_DEFAULTS.maxSubQuestions
  return p
}

/** 按问题关键字选语料；命中不到关键字时回落到第一条，避免界面空荡荡 */
const pickCorpus = (question: string) => {
  const q = question.toLowerCase()
  return (
    hitCorpus.find((c) => c.keywords.some((k) => q.includes(k.toLowerCase()))) ?? hitCorpus[0]
  )
}

/**
 * mock 不含模型，因此如实表现为降级 —— D4、原始问题单路，
 * 与真后端「查询理解完全不可用」落到同一档（§6.3 的底线）。
 * 这里刻意不回一个好看的数字：mock 说「我拆了 2 个子问题」就是在编造埋点。
 */
const STUB_TRACE: HitTestTrace = {
  subQuestionCount: 1,
  queryCount: 1,
  pathCount: 1,
  degradeLevel: 4,
  degradeReason: '未配置对话模型',
  truncated: false
}

/**
 * 多跳那一层的埋点（★ 与后端 {@code RetrievalService.runMultiHop} 同口径）。
 *
 * mock 不含模型 ⇒ 一旦多跳真的触发，判定必然走"拿不到模型"那条路（`JUDGE_FAILED`）——
 * 与真后端「没配置对话模型」落到同一档，这里如实表现，也顺便让界面能看见"开了但判定不可用"。
 *
 * ★ 刻意**不编造**"第 2 轮命中了新分段"：那需要模型给出一条有意义的下一轮查询，mock 造不出来就是造不出来。
 * 真栈那一路由 `check_multihop_e2e.py` 用桩的脚本化回复造出来。
 */
const multiHopTrace = (
  body: HitTestRequest,
  scored: Array<{ score: number }>
): Partial<HitTestTrace> => {
  if (!body.multiHopEnabled) {
    return {
      hopTriggered: false,
      hopRounds: 1,
      hopStopReason: 'NOT_ENABLED：未开启多跳',
      hopNewSegments: 0
    }
  }
  // 触发是**规则**判定（最高分 < 阈值 或 命中数 < 3），不额外调模型 —— 与后端同一个表达式
  const best = scored.length ? Math.max(...scored.map((item) => item.score)) : 0
  if (!(best < body.threshold || scored.length < HOP_MIN_HITS)) {
    return {
      hopTriggered: false,
      hopRounds: 1,
      hopStopReason: 'NOT_TRIGGERED：首轮命中已足够，未触发多跳',
      hopNewSegments: 0
    }
  }
  return {
    hopTriggered: true,
    hopRounds: 1,
    hopStopReason: 'JUDGE_FAILED：多跳判定不可用，改用已有结果',
    hopNewSegments: 0
  }
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
    content: item.content,
    // ★ 渠道：向量模式只有 vector、全文模式只有 text、混合模式两路都标上；
    //   再按问句抽出的实体给最后两条挂 entity / relation —— 界面要能演示
    //   「这条只被关系路捞上来」，否则那个徽标永远只有灰的两种。
    channels: channelsOf(body, index, scored.length)
  }))

  return {
    mode: body.mode,
    items,
    elapsedMs: profile.elapsed + Math.floor(Math.random() * 20),
    trace: { ...STUB_TRACE, ...multiHopTrace(body, scored) },
    detected: detectedOf(body, scored)
  }
}

/** 渠道：真后端由四路候选求并集得到，这里按模式与位次造一条合理的分布。 */
const channelsOf = (body: HitTestRequest, index: number, total: number): string[] => {
  const base: string[] = []
  if (body.mode === 'VECTOR' || body.mode === 'HYBRID') base.push('vector')
  if (body.mode === 'FULLTEXT' || body.mode === 'HYBRID') base.push('text')
  // 最后一条只由扩召回捞上来 —— 这是命中测试最想让人看见的情形
  if (index === total - 1 && total > 2) return ['relation']
  if (index === total - 2 && total > 3) return [...base.filter((c) => c === 'vector'), 'entity']
  return base
}

/**
 * 诊断块：认出了哪些实体、命中了哪几条关系。
 *
 * ★ mock 里也有三档 note（没认出实体 / 认出了没关系 / 有东西可念），与后端的四档一一对应 ——
 * 假数据只给"有东西"的那一种，界面就永远看不到空态长什么样。
 */
const detectedOf = (body: HitTestRequest, scored: Array<{ segmentId: string }>) => {
  const mentionsOrg = /部|中心|科|公司|局/.test(body.question)
  // ★ 问答对这一路与实体无关：它按问法全文匹配，不按"问句里有没有机构名"。
  //   这里给两条 —— 一条挂了出处分段、一条没有 —— 因为这两种在界面上长得必须不一样，
  //   只放假的那一条，界面就永远看不到"无出处"的形态。
  const qaMatches = scored.length
    ? [
        {
          id: 'qa-1',
          question: '财务资产部负责哪些预算事项',
          answer: '财务资产部负责年度预算的编制、调整与执行监督，具体事项包括部门预算汇总与预算执行分析。',
          segmentId: scored[0].segmentId,
          directAnswer: false,
          score: 0.92
        },
        {
          id: 'qa-2',
          question: '预算调整要走什么流程',
          answer: '预算调整由需求部门提出，财务资产部审核后报分管领导审批；超过年度预算 10% 的还需上会。',
          segmentId: null,
          directAnswer: true,
          score: 0.61
        }
      ]
    : []
  if (!mentionsOrg) {
    return {
      entities: [],
      relations: [],
      qaMatches: [],
      note: '问句里没有识别到实体，两路扩召回都没有起点'
    }
  }
  const entities = [
    { name: '财务资产部', type: 'ORG', mentions: 6 },
    { name: '预算科', type: 'ORG', mentions: 3 }
  ]
  if (!scored.length) {
    return {
      entities,
      relations: [],
      qaMatches,
      note: '认出 2 个实体，但它们在这些文档里没有关系陈述（抽取后才有边，见知识库「实体与关系」）'
    }
  }
  return {
    entities,
    relations: [
      { type: 'BELONGS_TO', from: '预算科', to: '财务资产部', segmentId: scored[0].segmentId }
    ],
    qaMatches,
    note: null
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
      // 越界不许"收下但按别的值跑"（与其它参数同口径；文案与后端 validate 逐字一致）
      if (input.maxHops !== undefined && (input.maxHops < 1 || input.maxHops > MAX_HOPS_CAP)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '多跳轮数应在 1～3 之间')
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
        noRefStrategy: input.noRefStrategy ?? current.noRefStrategy,
        // 未传的字段回落 current（findParams 已保证这三个字段在 current 上一定有值）——
        // 写成 undefined 就等于 PUT 一次把默认值抹掉，前端再 GET 到的是空值
        queryCount: input.queryCount ?? current.queryCount,
        decomposeEnabled: input.decomposeEnabled ?? current.decomposeEnabled,
        maxSubQuestions: input.maxSubQuestions ?? current.maxSubQuestions
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
  ],

  // ── 检索引擎的**全局**运行参数（`/settings/retrieval`）──────────────────────────────
  // ★ 与上面按库的三条是两件不同的事：那几条描述"一个库怎么检索"，这两条描述"这台机器怎么算"。
  //   mock 里也照这个分法放，避免"按库一份、全局一份"在夹具层就混成一个接口。
  ['GET', '/retrieval/settings', ({ token }) => {
    requirePerm(token, PERM.knowledgeView, '查看知识库')
    return engineSettings
  }],

  ['PUT', '/retrieval/settings', ({ body, token }) => {
    requirePerm(token, PERM.retrievalManage, '检索引擎调参')
    const input = body as Partial<RetrievalEngineSettingsUpdate>
    const next: RetrievalEngineSettingsUpdate = {
      queryUnderstandingEnabled: input.queryUnderstandingEnabled ?? engineSettings.queryUnderstandingEnabled,
      queryUnderstandingRetry: input.queryUnderstandingRetry ?? engineSettings.queryUnderstandingRetry,
      rerankEnabled: input.rerankEnabled ?? engineSettings.rerankEnabled,
      rerankTopK: input.rerankTopK ?? engineSettings.rerankTopK,
      rerankForwardBatch: input.rerankForwardBatch ?? engineSettings.rerankForwardBatch,
      rerankMaxLength: input.rerankMaxLength ?? engineSettings.rerankMaxLength,
      listwiseEnabled: input.listwiseEnabled ?? engineSettings.listwiseEnabled,
      listwiseTopK: input.listwiseTopK ?? engineSettings.listwiseTopK
    }
    // 四个上限与后端 `RetrievalEngineSettings.checked()` 逐字一致
    if (next.rerankTopK < 1 || next.rerankTopK > 50) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '重排窗口要在 1～50 之间，现在写的是 ' + next.rerankTopK)
    }
    if (next.rerankMaxLength < 32 || next.rerankMaxLength > 2048) {
      throw new ApiError(
        ErrorCode.BAD_REQUEST,
        '重排截断长度要在 32～2048 个 token 之间，现在写的是 ' + next.rerankMaxLength
      )
    }
    if (next.rerankForwardBatch < 0 || next.rerankForwardBatch > 100) {
      throw new ApiError(
        ErrorCode.BAD_REQUEST,
        '前向批大小要在 0～100 之间（0 = 整个窗口一次前向），现在写的是 ' + next.rerankForwardBatch
      )
    }
    if (next.listwiseTopK < 1 || next.listwiseTopK > 20) {
      throw new ApiError(ErrorCode.BAD_REQUEST, 'listwise 条数要在 1～20 之间，现在写的是 ' + next.listwiseTopK)
    }
    if (!next.queryUnderstandingEnabled && next.queryUnderstandingRetry) {
      throw new ApiError(
        ErrorCode.BAD_REQUEST,
        '关闭查询理解时「解析失败重试」无意义 —— 请一并关掉（后端同样会拒绝这个组合）'
      )
    }
    Object.assign(engineSettings, next, { updatedAt: new Date().toISOString() })
    return engineSettings
  }]
]
