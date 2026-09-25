import type { MockContext, MockRoute } from '../dispatch'
import { ApiError } from '@/api/types'
import type {
  DebugWorkflowResult,
  WorkflowGraph,
  WorkflowVersion
} from '@/api/workflow'
import {
  disabledKbIds,
  getWorkflowDraft,
  knownKbIds,
  knownModelIds,
  nodeTypeCatalog,
  setWorkflowDraft,
  sampleInvalidGraph,
  sampleValidGraph,
  workflowRecords,
  workflowVersions
} from '../fixtures/workflow'
import { hasBlockingError, validateGraph } from '@/pages/workflow/lib/validate'

/**
 * 编排（画布）mock。
 *
 * 真相源在 `fixtures/workflow.ts`；校验规则在 `pages/workflow/lib/validate`（与界面共用同一份）。
 *
 * 两条本文件必须守住的约定：
 *  1. **路由是元组 `[method, pattern, handler]`**，不是对象 —— `dispatchMock` 会遍历
 *     `ROUTES` 解构，写错形状不是"这条不生效"，而是**全站每个 mock 请求都抛 TypeError**。
 *  2. **处理器直接返回负载**（不包 `{code, data}`）；失败一律 `throw new ApiError(code, msg)`。
 *     响应包由传输层负责，处理器里再包一层就会让页面拿到包而不是数据。
 *
 * 路由顺序约定：**纯静态路径必须排在带 :param 路径之前**，否则会被参数路由吞掉。
 */

const ctxFor = (): Parameters<typeof validateGraph>[1] => ({
  modelIds: new Set(knownModelIds),
  kbIds: new Set(knownKbIds),
  disabledKbIds: new Set(disabledKbIds)
})

function cloneGraph(g: WorkflowGraph): WorkflowGraph {
  return {
    nodes: g.nodes.map((n) => ({ ...n, position: { ...n.position }, config: { ...n.config } })),
    edges: g.edges.map((e) => ({ ...e }))
  }
}

/**
 * 版本响应**不含 `graph`**：版本列表只用来展示与选择，
 * 带上每版的完整流程图会让列表接口随历史增长而线性膨胀。
 * 回滚需要的那份图留在 mock 内部（`workflowVersions` 的记录里）。
 */
const toPublicVersion = (v: WorkflowVersion): WorkflowVersion => ({
  id: v.id,
  version: v.version,
  publishedAt: v.publishedAt,
  publishedBy: v.publishedBy,
  note: v.note,
  nodeCount: v.nodeCount
})

/** 线性调试：从 start 沿唯一出边走到底；遇到条件节点走兜底（无兜底取第一个有出边的分支） */
function debugLinear(graph: WorkflowGraph): DebugWorkflowResult | { error: string } {
  const starts = graph.nodes.filter((n) => n.type === 'start-node')
  if (starts.length !== 1) {
    return { error: '调试仅支持线性流程，且需要恰好一个开始节点。条件分支的真实判定属后端执行引擎（V1.0）。' }
  }
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  const outEdges = new Map<string, typeof graph.edges>()
  for (const e of graph.edges) {
    const list = outEdges.get(e.source) ?? []
    list.push(e)
    outEdges.set(e.source, list)
  }

  const startedAt = Date.now()
  const trail: DebugWorkflowResult['nodeTrail'] = []
  const seen = new Set<string>([starts[0]!.id])
  let cur: string = starts[0]!.id
  let guard = 0

  const record = (id: string, elapsedMs: number) => {
    const node = byId.get(id)
    if (!node) return
    trail.push({
      nodeId: id,
      type: node.type,
      title: node.title,
      status: '成功',
      enteredAt: new Date().toISOString(),
      elapsedMs
    })
  }

  record(cur, 0)
  while (guard++ < 32) {
    const outs = outEdges.get(cur) ?? []
    if (outs.length === 0) break
    let nextEdge = outs[0]
    const node = byId.get(cur)
    if (node?.type === 'condition-node') {
      // 调试只走兜底分支：真实条件判定属后端执行引擎
      const cfg = node.config as { fallbackBranchId: string | null }
      const preferred = outs.find((e) => e.sourceHandle === cfg.fallbackBranchId)
      if (!preferred) {
        return { error: '条件节点未配置可走的兜底分支，调试无法继续（真实判定属后端执行引擎 V1.0）。' }
      }
      nextEdge = preferred
    }
    if (!nextEdge) break
    const nextId = nextEdge.target
    if (seen.has(nextId)) {
      // 成环时中止，避免 mock 死循环
      break
    }
    seen.add(nextId)
    record(nextId, 120)
    cur = nextId
    if (byId.get(cur)?.type === 'reply-node') break
  }

  const last = byId.get(cur)
  const finalOutput =
    last?.type === 'reply-node'
      ? String((last.config as { content: string }).content || '').slice(0, 200) || '（回复内容为空）'
      : '（线性调试未走到指定回复节点，仅展示轨迹）'

  return { finalOutput, nodeTrail: trail, elapsedMs: Date.now() - startedAt + trail.length * 120 }
}

/** 图来自请求体就用请求体（发布前可能尚未保存），否则取草稿 */
function graphFromBody(ctx: MockContext, appId: string): WorkflowGraph {
  const body = ctx.body as { graph?: WorkflowGraph; nodes?: unknown[]; edges?: unknown[] } | undefined
  if (body?.graph && Array.isArray(body.graph.nodes) && Array.isArray(body.graph.edges)) {
    return body.graph
  }
  if (Array.isArray(body?.nodes) && Array.isArray(body?.edges)) {
    return body as unknown as WorkflowGraph
  }
  return getWorkflowDraft(appId)
}

export const workflowRoutes: MockRoute[] = [
  // ---- 纯静态路径（必须排在 :param 之前） ----
  ['GET', '/workflow/node-types', () => nodeTypeCatalog],

  // ---- 带参路径 ----
  [
    'GET',
    '/assistants/:appId/workflow/draft',
    ({ path }) => cloneGraph(getWorkflowDraft(path.appId as string))
  ],

  [
    'PUT',
    '/assistants/:appId/workflow/draft',
    ({ path, body }) => {
      const appId = path.appId as string
      const input = body as { graph?: WorkflowGraph } | undefined
      const graph = input?.graph
      if (!graph || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) {
        throw new ApiError(4003, '请求体需包含 graph.nodes 与 graph.edges 数组')
      }
      // 草稿允许不合规：这里不做校验，原样落盘（保存与发布是两回事）
      setWorkflowDraft(appId, graph)
      return cloneGraph(getWorkflowDraft(appId))
    }
  ],

  [
    'POST',
    '/assistants/:appId/workflow/validate',
    (ctx) => {
      const issues = validateGraph(graphFromBody(ctx, ctx.path.appId as string), ctxFor())
      return { passed: !hasBlockingError(issues), issues }
    }
  ],

  [
    'POST',
    '/assistants/:appId/workflow/publish',
    (ctx) => {
      const appId = ctx.path.appId as string
      const body = ctx.body as { note?: string } | undefined
      const note = (body?.note ?? '').trim()
      if (!note) throw new ApiError(4003, '发布说明必填')

      const graph = graphFromBody(ctx, appId)
      const issues = validateGraph(graph, ctxFor())
      if (hasBlockingError(issues)) {
        const count = issues.filter((i) => i.level === '错误').length
        throw new ApiError(6001, `存在 ${count} 个阻断错误，无法发布`)
      }

      const list = workflowVersions[appId] ?? (workflowVersions[appId] = [])
      const nextVersion = (list[0]?.version ?? 0) + 1
      const record = {
        id: `wv-${appId}-${nextVersion}`,
        version: nextVersion,
        note,
        publishedAt: new Date().toISOString(),
        publishedBy: '当前用户',
        nodeCount: graph.nodes.length,
        graph: cloneGraph(graph)
      }
      list.unshift(record)
      // 发布后草稿与线上一致
      setWorkflowDraft(appId, graph)
      return toPublicVersion(record)
    }
  ],

  [
    'GET',
    '/assistants/:appId/workflow/versions',
    ({ path }) => (workflowVersions[path.appId as string] ?? []).map(toPublicVersion)
  ],

  // 单版图：5 段，与下面的 rollback（6 段）段数不同，不会互相吞
  [
    'GET',
    '/assistants/:appId/workflow/versions/:version',
    ({ path }) => {
      const hit = (workflowVersions[path.appId as string] ?? []).find(
        (v) => v.version === Number(path.version)
      )
      if (!hit) throw new ApiError(404, `版本 v${path.version} 不存在`)
      return cloneGraph(hit.graph)
    }
  ],

  [
    'POST',
    '/assistants/:appId/workflow/versions/:version/rollback',
    ({ path }) => {
      const appId = path.appId as string
      const version = Number(path.version)
      const hit = (workflowVersions[appId] ?? []).find((v) => v.version === version)
      if (!hit) throw new ApiError(404, `版本 v${version} 不存在`)
      // 回滚 = 生成新草稿，**不改动历史版本**
      setWorkflowDraft(appId, cloneGraph(hit.graph))
      return cloneGraph(hit.graph)
    }
  ],

  [
    'POST',
    '/assistants/:appId/workflow/debug',
    ({ path, body }) => {
      const appId = path.appId as string
      const input = ((body as { input?: string } | undefined)?.input ?? '').trim()
      if (!input) throw new ApiError(4003, '调试输入不能为空')

      const result = debugLinear(getWorkflowDraft(appId))
      if ('error' in result) throw new ApiError(4004, result.error)
      // 调试不产生正式会话记录：不写 workflowRecords
      return result
    }
  ],

  [
    'GET',
    '/assistants/:appId/workflow/records',
    ({ path }) => workflowRecords[path.appId as string] ?? []
  ],

  // ---- 演示辅助：载入示例图（便于校验拦截演示） ----
  ['GET', '/workflow/demo/invalid-graph', () => cloneGraph(sampleInvalidGraph)],
  ['GET', '/workflow/demo/valid-graph', () => cloneGraph(sampleValidGraph)]
]

/** 供应用壳 publish-check 复用：只判「有没有一张能跑的图」，不重复图级校验 */
export function workflowHasRunnableGraph(appId: string): {
  passed: boolean
  issues: Array<{ code: string; message: string; hint: string }>
} {
  const graph = getWorkflowDraft(appId)
  const nodes = graph.nodes
  const issues: Array<{ code: string; message: string; hint: string }> = []
  const hasStart = nodes.some((n) => n.type === 'start-node')
  const hasReply = nodes.some((n) => n.type === 'reply-node')
  const connected = graph.edges.length > 0
  const passed = nodes.length >= 2 && hasStart && hasReply && connected

  if (!hasStart) {
    issues.push({
      code: 'workflow-missing-start',
      message: '编排图缺少开始节点',
      hint: '进入「编排」添加一个开始节点'
    })
  }
  if (!hasReply) {
    issues.push({
      code: 'workflow-missing-reply',
      message: '编排图缺少指定回复节点，无法产出答复',
      hint: '进入「编排」添加一个指定回复节点'
    })
  }
  if (!connected) {
    issues.push({
      code: 'workflow-not-connected',
      message: '编排图节点尚未连线',
      hint: '进入「编排」把节点连成流程'
    })
  }
  return { passed, issues }
}
