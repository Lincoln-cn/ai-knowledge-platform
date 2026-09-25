import type {
  AiChatNodeConfig,
  ConditionNodeConfig,
  NumberConditionNodeConfig,
  QuestionNodeConfig,
  SearchDatasetNodeConfig,
  WorkflowEdge,
  WorkflowGraph,
  WorkflowNode,
  WorkflowValidationIssue
} from '@/api/workflow'

/**
 * 画布校验：纯函数，mock 与界面共用一套规则，避免两处漂移。
 *
 * 职责边界（与应用壳 publish-check 划清）：
 *  - 本文件负责「图级合法性」——唯一开始、成环、孤立/断链、判断节点分支出边与兜底、外部引用存在性。
 *  - 应用壳只查「有没有一张能跑的图」（有开始、有回复、已连线、节点数够），不重复这里的规则。
 *
 * 判断节点有两个类型，分支/兜底规则对两者同样生效：
 *  - `condition-node`（语义判断）与 `number-condition-node`（数值判断）。
 */

export interface ValidateContext {
  /** 存在的模型 id 集合 */
  modelIds: ReadonlySet<string>
  /** 存在的知识库 id 集合 */
  kbIds: ReadonlySet<string>
  /** 已停用的知识库 id 集合（仅告警） */
  disabledKbIds?: ReadonlySet<string>
}

/** 两个判断类型共用的分支/兜底配置形状 */
type BranchLike = { id: string; label: string }

const BRANCH_NODE_TYPES: ReadonlySet<string> = new Set(['condition-node', 'number-condition-node'])

/** 只取分支 id/label —— 两个判断类型的分支列表内部形状不同，出边/兜底检查只认这两项 */
function branchIdLabels(cfg: unknown): BranchLike[] {
  const branches = (cfg as { branches?: unknown } | null)?.branches
  if (!Array.isArray(branches)) return []
  return branches
    .map((b) => {
      const item = b as { id?: unknown; label?: unknown }
      return { id: String(item?.id ?? ''), label: String(item?.label ?? item?.id ?? '') }
    })
    .filter((b) => b.id)
}

/** 一元操作符：不需要填 value */
const UNARY_OPS: ReadonlySet<string> = new Set(['is_null', 'is_not_null'])

/** 可读 code → 中文原因 / 下一步。界面与报告都从这里取文案。 */
export const ISSUE_TEXT: Record<string, { message: string; hint: string }> = {
  'start-node-count': {
    message: '开始节点数量不是 1（有且仅有一个开始节点）',
    hint: '删除多余的开始节点，或补上唯一一个开始节点'
  },
  cycle: {
    message: '流程存在环路，无法确定执行顺序',
    hint: '断开造成回路的连线，让流程成为有向无环图'
  },
  'isolated-node': {
    message: '存在未连线的孤立节点',
    hint: '把该节点连入主流程，或删除它'
  },
  'unreachable-node': {
    message: '节点从开始节点不可达（断链）',
    hint: '从上游节点连一条边到该节点，或删除它'
  },
  'condition-branch-no-edge': {
    message: '判断节点（语义判断/数值判断）的分支没有出边，该分支命中后无处可去',
    hint: '从该分支的出点拖一条线到后续节点'
  },
  'condition-no-fallback': {
    message: '判断节点（语义判断/数值判断）未配置兜底分支',
    hint: '在判断节点配置里指定兜底分支，保证任意输入都有出口'
  },
  'condition-fallback-missing': {
    message: '兜底分支 id 在分支列表中不存在',
    hint: '把兜底分支指到已有分支，或先添加该分支'
  },
  'condition-empty-expression': {
    message: '语义判断分支的判定条件为空',
    hint: '用一句自然语言写清命中该分支的条件，由模型判定'
  },
  'condition-incomplete-item': {
    message: '数值判断分支缺少完整条件（变量未选、无操作符，或缺比较值）',
    hint: '每个分支至少配置一条「变量 + 操作符 + 值」条件（为空/不为空可不填值）'
  },
  'model-not-found': {
    message: '引用了不存在的模型',
    hint: '在模型管理中创建/加载该模型，或改选一个已存在的模型'
  },
  'kb-not-found': {
    message: '引用了不存在的知识库',
    hint: '在知识库管理中创建该知识库，或改选一个已存在的知识库'
  },
  'kb-disabled': {
    message: '引用了已停用的知识库',
    hint: '启用该知识库，或改绑启用中的知识库'
  },
  'empty-graph': {
    message: '画布为空，没有任何节点',
    hint: '从左侧节点目录拖入开始节点与回复节点，搭出最小流程'
  },
  'no-reply-node': {
    message: '流程中没有指定回复节点，无法产出最终答复',
    hint: '补一个指定回复节点作为流程出口'
  }
}

function issue(
  code: string,
  level: '错误' | '警告',
  nodeId: string | null,
  extra?: Partial<WorkflowValidationIssue>
): WorkflowValidationIssue {
  const text = ISSUE_TEXT[code] ?? { message: code, hint: '请检查该节点配置' }
  return {
    code,
    level,
    nodeId,
    message: text.message,
    hint: text.hint,
    ...extra
  }
}

/** 从 source 出发能到达的节点集合（含 source 自身） */
function reachableFrom(source: string, edges: WorkflowEdge[]): Set<string> {
  const out = new Map<string, string[]>()
  for (const e of edges) {
    const list = out.get(e.source) ?? []
    list.push(e.target)
    out.set(e.source, list)
  }
  const seen = new Set<string>([source])
  const stack = [source]
  while (stack.length) {
    const cur = stack.pop()!
    for (const next of out.get(cur) ?? []) {
      if (!seen.has(next)) {
        seen.add(next)
        stack.push(next)
      }
    }
  }
  return seen
}

/** 有向图是否有环（DFS 三色标记） */
function hasCycle(nodeIds: string[], edges: WorkflowEdge[]): boolean {
  const out = new Map<string, string[]>()
  for (const e of edges) {
    const list = out.get(e.source) ?? []
    list.push(e.target)
    out.set(e.source, list)
  }
  // 0=未访问 1=在栈上 2=已完成
  const color = new Map<string, 0 | 1 | 2>()
  for (const id of nodeIds) color.set(id, 0)

  const visit = (id: string): boolean => {
    color.set(id, 1)
    for (const next of out.get(id) ?? []) {
      const c = color.get(next) ?? 0
      if (c === 1) return true
      if (c === 0 && visit(next)) return true
    }
    color.set(id, 2)
    return false
  }

  for (const id of nodeIds) {
    if ((color.get(id) ?? 0) === 0 && visit(id)) return true
  }
  return false
}

function nodeLabel(node: WorkflowNode): string {
  return node.title || node.type
}

/** 引用检查：模型 / 知识库 */
function checkReferences(nodes: WorkflowNode[], ctx: ValidateContext): WorkflowValidationIssue[] {
  const issues: WorkflowValidationIssue[] = []
  for (const node of nodes) {
    const cfg = node.config as Partial<
      AiChatNodeConfig & SearchDatasetNodeConfig & QuestionNodeConfig
    >
    const modelId = cfg.modelId
    if (typeof modelId === 'string' && modelId && !ctx.modelIds.has(modelId)) {
      issues.push(
        issue('model-not-found', '错误', node.id, {
          message: `节点「${nodeLabel(node)}」引用的模型 ${modelId} 不存在`,
          hint: '在模型管理中创建/加载该模型，或改选一个已存在的模型'
        })
      )
    }
    for (const kbId of cfg.kbIds ?? []) {
      if (!ctx.kbIds.has(kbId)) {
        issues.push(
          issue('kb-not-found', '错误', node.id, {
            message: `节点「${nodeLabel(node)}」引用的知识库 ${kbId} 不存在`,
            hint: '在知识库管理中创建该知识库，或改选一个已存在的知识库'
          })
        )
      } else if (ctx.disabledKbIds?.has(kbId)) {
        issues.push(
          issue('kb-disabled', '警告', node.id, {
            message: `节点「${nodeLabel(node)}」引用的知识库 ${kbId} 已停用`,
            hint: '启用该知识库，或改绑启用中的知识库'
          })
        )
      }
    }
  }
  return issues
}

/**
 * 图级校验。返回可读 issue 列表（含 nodeId 便于定位高亮）。
 * valid === issues 中没有 error。
 */
export function validateGraph(
  graph: WorkflowGraph,
  ctx: ValidateContext
): WorkflowValidationIssue[] {
  const issues: WorkflowValidationIssue[] = []
  const nodes = graph.nodes
  const edges = graph.edges

  if (nodes.length === 0) {
    issues.push(issue('empty-graph', '错误', null))
    return issues
  }

  // 1. 有且仅有一个 start-node
  const starts = nodes.filter((n) => n.type === 'start-node')
  if (starts.length !== 1) {
    issues.push(
      issue('start-node-count', '错误', starts[0]?.id ?? null, {
        message:
          starts.length === 0
            ? '流程缺少开始节点（有且仅有一个）'
            : `流程有 ${starts.length} 个开始节点（有且仅有一个）`,
        hint:
          starts.length === 0
            ? '从节点目录拖入一个开始节点'
            : '删除多余的开始节点，只保留一个'
      })
    )
  }

  const nodeIds = nodes.map((n) => n.id)

  // 2. 禁止成环
  if (hasCycle(nodeIds, edges)) {
    issues.push(issue('cycle', '错误', null))
  }

  // 3. 孤立节点：没有任何入边且没有任何出边
  const hasIn = new Set(edges.map((e) => e.target))
  const hasOut = new Set(edges.map((e) => e.source))
  for (const node of nodes) {
    const degree0 = !hasIn.has(node.id) && !hasOut.has(node.id)
    // 单节点空图已在 empty-graph 处理；这里多节点下 degree-0 即孤立
    if (degree0 && nodes.length > 1) {
      issues.push(
        issue('isolated-node', '错误', node.id, {
          message: `节点「${nodeLabel(node)}」是孤立节点，没有任何连线`,
          hint: '把该节点连入主流程，或删除它'
        })
      )
    }
  }

  // 3b. 断链：从 start 出发不可达（孤立节点已报过，不重复）
  const startId = starts[0]?.id
  if (startId) {
    const reach = reachableFrom(startId, edges)
    for (const node of nodes) {
      if (node.id === startId) continue
      const degree0 = !hasIn.has(node.id) && !hasOut.has(node.id)
      if (!reach.has(node.id) && !degree0) {
        issues.push(
          issue('unreachable-node', '错误', node.id, {
            message: `节点「${nodeLabel(node)}」从开始节点不可达（断链）`,
            hint: '从上游节点连一条边到该节点，或删除它'
          })
        )
      }
    }
  }

  // 4. 判断节点（语义判断 / 数值判断）：每个分支都有出边、且配了兜底
  const edgeSourceHandles = new Map<string, Set<string | undefined>>()
  for (const e of edges) {
    const set = edgeSourceHandles.get(e.source) ?? new Set()
    set.add(e.sourceHandle)
    edgeSourceHandles.set(e.source, set)
  }
  for (const node of nodes) {
    if (!BRANCH_NODE_TYPES.has(node.type)) continue
    const cfg = node.config as { fallbackBranchId: string | null }
    const branches = branchIdLabels(node.config)
    const handles = edgeSourceHandles.get(node.id) ?? new Set<string | undefined>()
    const kindLabel = node.type === 'number-condition-node' ? '数值判断' : '语义判断'

    if (!cfg.fallbackBranchId) {
      issues.push(
        issue('condition-no-fallback', '错误', node.id, {
          message: `${kindLabel}节点「${nodeLabel(node)}」未配置兜底分支`,
          hint: '在判断节点配置里指定兜底分支，保证任意输入都有出口'
        })
      )
    } else if (!branches.some((b) => b.id === cfg.fallbackBranchId)) {
      issues.push(
        issue('condition-fallback-missing', '错误', node.id, {
          message: `${kindLabel}节点「${nodeLabel(node)}」的兜底分支 ${cfg.fallbackBranchId} 不在分支列表中`,
          hint: '把兜底分支指到已有分支，或先添加该分支'
        })
      )
    }

    for (const branch of branches) {
      if (!handles.has(branch.id)) {
        issues.push(
          issue('condition-branch-no-edge', '错误', node.id, {
            message: `${kindLabel}节点「${nodeLabel(node)}」的分支「${branch.label}」没有出边`,
            hint: `从分支「${branch.label}」的出点拖一条线到后续节点`
          })
        )
      }
    }

    // 4b. 类型各自的配置完整性
    if (node.type === 'condition-node') {
      const cfg = node.config as ConditionNodeConfig
      for (const branch of cfg.branches ?? []) {
        if (!String(branch.expression ?? '').trim()) {
          issues.push(
            issue('condition-empty-expression', '错误', node.id, {
              message: `语义判断节点「${nodeLabel(node)}」的分支「${branch.label}」判定条件为空`,
              hint: '用一句自然语言写清命中该分支的条件，由模型判定'
            })
          )
        }
      }
    } else if (node.type === 'number-condition-node') {
      const cfg = node.config as NumberConditionNodeConfig
      for (const branch of cfg.branches ?? []) {
        const ok = (branch.conditions ?? []).some((c) => {
          const hasVar = Array.isArray(c.variable) && c.variable.length >= 2
          const hasOp = !!c.operator
          const hasValue = c.value !== null && c.value !== undefined && String(c.value) !== ''
          return hasVar && hasOp && (UNARY_OPS.has(c.operator) || hasValue)
        })
        if (!ok) {
          issues.push(
            issue('condition-incomplete-item', '错误', node.id, {
              message: `数值判断节点「${nodeLabel(node)}」的分支「${branch.label}」没有完整条件（变量/操作符/值）`,
              hint: '每个分支至少配置一条「变量 + 操作符 + 值」条件（为空/不为空可不填值）'
            })
          )
        }
      }
    }
  }

  // 5. 外部引用
  issues.push(...checkReferences(nodes, ctx))

  // 6. 出口提示：没有指定回复时告警
  if (!nodes.some((n) => n.type === 'reply-node')) {
    issues.push(issue('no-reply-node', '警告', null))
  }

  return issues
}

export function hasBlockingError(issues: WorkflowValidationIssue[]): boolean {
  return issues.some((i) => i.level === '错误')
}
