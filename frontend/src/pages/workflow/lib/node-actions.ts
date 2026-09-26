/**
 * 节点操作动作（删除 / 复制 / 收起展开 / 重命名）与单例规则。
 *
 * ⋮ 操作菜单与「添加组件」面板**共用这一套**，避免多处各写一份规则。
 *
 * 单例删除规则（不是「start-node 一律不能删」）：
 *   删除后图是否仍合法？
 *   - 该类单例节点数量 > 1：删掉一个还剩 ≥1，**可删**（清掉多余开始节点的唯一出路）
 *   - 该类单例节点只有 1 个：删掉就没了，**不可删**，置灰 + 原因
 * 复制规则更严：单例类**永远**不允许复制出第二个。
 * 添加规则与删除对偶：单例类已有实例则拒绝再加。
 */
import { set } from 'lodash-es'
import { ElMessageBox } from 'element-plus'
import { MsgError, MsgWarning, MsgConfirm } from '@/utils/message'

/** 单例节点：画布上只允许出现一个 */
const SINGLETON_TYPES: ReadonlySet<string> = new Set(['start-node'])

const SINGLETON_LABELS: Record<string, string> = {
  'start-node': '开始节点'
}

export function isSingletonType(type: string | undefined): boolean {
  return !!type && SINGLETON_TYPES.has(type)
}

export function singletonLabel(type: string | undefined): string {
  return (type && SINGLETON_LABELS[type]) || '该节点'
}

/** 节点摘要：LogicFlow nodeModel 与契约图节点都能塞进来 */
export type NodeLike = { id: string; type?: string; properties?: any; config?: any }

/** 从 graphModel / 图数据里取节点数组 */
export function toNodeList(source: any): NodeLike[] {
  if (!source) return []
  if (Array.isArray(source)) return source as NodeLike[]
  if (Array.isArray(source.nodes)) return source.nodes as NodeLike[]
  return []
}

export function findNode(nodes: NodeLike[], nodeId: string): NodeLike | null {
  return toNodeList(nodes).find((n) => n.id === nodeId) ?? null
}

/** 画布上某 type 的节点数量 */
export function countByType(nodes: any, type: string): number {
  return toNodeList(nodes).filter((n) => n.type === type).length
}

/**
 * 删除是否可用 + 不可用原因。
 * 可用性 = 「假定删掉它之后图是否仍然合法」。
 */
export function deleteRestriction(
  nodes: any,
  nodeId: string
): { disabled: boolean; reason: string | null } {
  const node = findNode(toNodeList(nodes), nodeId)
  const type = node?.type
  if (!isSingletonType(type)) return { disabled: false, reason: null }
  const count = countByType(nodes, type as string)
  if (count > 1) return { disabled: false, reason: null }
  return {
    disabled: true,
    reason: `${singletonLabel(type)}是流程唯一入口，不能删除`
  }
}

/** 复制是否可用 + 不可用原因。单例类永远不允许复制出第二个。 */
export function copyRestriction(
  nodes: any,
  nodeId: string
): { disabled: boolean; reason: string | null } {
  const node = findNode(toNodeList(nodes), nodeId)
  const type = node?.type
  if (!isSingletonType(type)) return { disabled: false, reason: null }
  return {
    disabled: true,
    reason: `${singletonLabel(type)}是单例节点，不能复制出第二个`
  }
}

/** 删除：单例（仅剩一个时）拦截 → 二次确认 → 删节点 */
export function requestDeleteNode(graphModel: any, nodeId: string): void {
  const restriction = deleteRestriction(graphModel?.nodes ?? [], nodeId)
  if (restriction.disabled) {
    MsgError(restriction.reason ?? '该节点不能删除')
    return
  }
  const title =
    (findNode(graphModel?.nodes ?? [], nodeId) as any)?.properties?.stepName ?? nodeId
  MsgConfirm('提示', `确定删除节点「${title}」？`, {
    confirmButtonText: '删除',
    confirmButtonClass: 'danger'
  })
    .then(() => {
      graphModel.deleteNode(nodeId)
      graphModel.eventCenter?.emit?.('delete_node')
    })
    .catch(() => {
      /* 用户取消 */
    })
}

/** 复制：单例拦截 → clone 并选中 */
export function requestCopyNode(graphModel: any, nodeId: string): void {
  const restriction = copyRestriction(graphModel?.nodes ?? [], nodeId)
  if (restriction.disabled) {
    MsgError(restriction.reason ?? '该节点不能复制')
    return
  }
  graphModel.clearSelectElements()
  const cloneNode = graphModel.cloneNode(nodeId)
  if (cloneNode) {
    set(cloneNode, 'isSelected', true)
    set(cloneNode, 'isHovered', true)
    graphModel.toFront(cloneNode.id)
  }
}

/**
 * 收起 / 展开单个节点。
 * 真相源在 NodeContainer 的本地 ref（LogicFlow 模型不是响应式对象），
 * 所以这里只发事件，由节点组件自己翻转。
 */
export function toggleNodeCollapse(graphModel: any, nodeId: string): void {
  graphModel.eventCenter?.emit?.('toggle_node_collapse', nodeId)
}

/** 重命名：弹输入框，校验空名与重名后写回 stepName */
export function requestRenameNode(graphModel: any, nodeId: string): void {
  const nodes = (graphModel?.nodes ?? []) as NodeLike[]
  const model = findNode(nodes, nodeId) as any
  if (!model) return
  const current = model.properties?.stepName ?? ''
  ElMessageBox.prompt('节点名称', '重命名', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: current,
    inputValidator: (v: string) => {
      const name = (v ?? '').trim()
      if (!name) return '名称不能为空'
      const dup = nodes.some(
        (n: any) => n.id !== nodeId && n.properties?.stepName === name
      )
      if (dup) return '节点名称已存在'
      return true
    }
  })
    .then(({ value }) => {
      const name = String(value ?? '').trim()
      if (name && name !== current) {
        set(model.properties, 'stepName', name)
      }
    })
    .catch(() => {
      /* 用户取消 */
    })
}

/* ────────────────────────── 菜单描述（两套 UI 共用） ────────────────────────── */

export type NodeMenuKey = 'toggleCollapse' | 'rename' | 'copy' | 'delete'

export interface NodeMenuItem {
  key: NodeMenuKey
  /** 干净的动作名（不含禁用原因） */
  label: string
  disabled: boolean
  disabledReason: string | null
  danger?: boolean
  /** 执行动作。禁用项也会被调用（由它负责弹原因），这样两套 UI 行为一致 */
  run: () => void
}

export interface BuildNodeMenuOptions {
  /** 节点当前是否展开（决定「收起」还是「展开」） */
  expanded: boolean
  graphModel: any
  nodeId: string
}

/**
 * 生成节点操作菜单项。
 * 收起/展开按**当前状态二选一**；删除/复制按上面的单例规则禁用并带原因。
 */
export function buildNodeMenuItems(opts: BuildNodeMenuOptions): NodeMenuItem[] {
  const { expanded, graphModel, nodeId } = opts
  const nodes = graphModel?.nodes ?? []
  const del = deleteRestriction(nodes, nodeId)
  const copy = copyRestriction(nodes, nodeId)

  return [
    {
      key: 'toggleCollapse',
      label: expanded ? '收起节点' : '展开节点',
      disabled: false,
      disabledReason: null,
      run: () => toggleNodeCollapse(graphModel, nodeId)
    },
    {
      key: 'rename',
      label: '重命名',
      disabled: false,
      disabledReason: null,
      run: () => requestRenameNode(graphModel, nodeId)
    },
    {
      key: 'copy',
      label: '复制节点',
      disabled: copy.disabled,
      disabledReason: copy.reason,
      run: () => requestCopyNode(graphModel, nodeId)
    },
    {
      key: 'delete',
      label: '删除节点',
      disabled: del.disabled,
      disabledReason: del.reason,
      danger: !del.disabled,
      run: () => requestDeleteNode(graphModel, nodeId)
    }
  ]
}

/* ────────────────────────── 「添加组件」入口的单例闸门 ────────────────────────── */

/**
 * 能否往图里再放一个该类型节点。
 * `nodes` 传 `workflowRef.getGraphData()?.nodes` 即可（契约节点或 LogicFlow 节点皆可）。
 * `singletonFromContract` 传 `NodeTypeMeta.singleton` —— 契约是最终真相，
 * 内置单例表只是防契约漏标时的兜底。
 */
export function checkCanAddType(
  nodes: any,
  type: string,
  singletonFromContract?: boolean
): { ok: boolean; reason: string | null } {
  const singleton = isSingletonType(type) || !!singletonFromContract
  if (!singleton) return { ok: true, reason: null }
  if (countByType(nodes, type) >= 1) {
    return {
      ok: false,
      reason: `「${singletonLabel(type)}」只能有一个，它是流程唯一入口`
    }
  }
  return { ok: true, reason: null }
}

/** 添加入口统一拒绝提示（不静默忽略）。返回 true 表示已拒绝。 */
export function rejectAddIfSingleton(
  nodes: any,
  type: string,
  singletonFromContract?: boolean
): boolean {
  const check = checkCanAddType(nodes, type, singletonFromContract)
  if (!check.ok) {
    MsgWarning(check.reason ?? '该节点只能有一个')
    return true
  }
  return false
}
