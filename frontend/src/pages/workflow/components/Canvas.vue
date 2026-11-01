<template>
  <div class="workflow-wrap">
    <div ref="containerRef" class="workflow-app"></div>
    <Control v-if="lf" class="workflow-control" :lf="lf" :on-auto-layout="() => autoLayout(lf)"></Control>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import LogicFlow from '@logicflow/core'
import Dagre from '@/pages/workflow/plugins/dagre'
import '@logicflow/core/dist/style/index.css'
import '@logicflow/extension/lib/style/index.css'
import { initDefaultShortcut } from '@/pages/workflow/components/common/shortcut'
import AppEdge from './common/edge'
import Control from './common/NodeControl.vue'

const nodes: Record<string, { default: unknown }> = import.meta.glob('./nodes/**/index.ts', {
  eager: true
})
defineOptions({ name: 'WorkFlow' })

type ShapeItem = {
  type?: string
  text?: string
  icon?: string
  label?: string
  className?: string
  disabled?: boolean
  properties?: Record<string, unknown>
  callback?: (lf: LogicFlow, container?: HTMLElement) => void
}

const EMPTY_GRAPH = { nodes: [], edges: [] }

const props = defineProps<{
  /** LogicFlow 格式的图（由页面用 lib/graph-format 转好） */
  data?: unknown
}>()

const containerRef = ref<HTMLElement | null>(null)
// LogicFlow 的 TS 声明与运行时构造不完全对齐，实例用 any 承载，对外方法再收窄
const lf = ref<any>(null)

function ensureLf(): any | null {
  if (lf.value) return lf.value
  const el = containerRef.value
  if (!el) return null

  const instance = new (LogicFlow as any)({
    plugins: [Dagre],
    textEdit: false,
    adjustEdge: false,
    adjustEdgeStartAndEnd: false,
    background: { backgroundColor: '#f5f6f7' },
    grid: {
      size: 10,
      type: 'dot',
      config: { color: '#DEE0E3', thickness: 1 }
    },
    keyboard: { enabled: true },
    isSilentMode: false,
    container: el
  })

  instance.setTheme({ bezier: { stroke: '#afafaf', strokeWidth: 1 } })
  initDefaultShortcut(instance, instance.graphModel)
  // ★ 注册名必须是**契约取值**（`START_NODE`），不是目录名/图标名那套 kebab（`start-node`）。
  //   画布的节点类型与契约 `WorkflowNodeType` 同名同值 —— `nodeDict` 的键、`WorkflowType` 枚举、
  //   `node-actions` 的单例表、「添加组件」的 `addNode({type: meta.type})` 全都按取值来。
  //   规范 14 §三：文件名/目录名不动，取值要改 ⇒ 两套词汇只在 `icons/utils.ts` 相遇一次。
  instance.batchRegister([...Object.keys(nodes).map((key) => nodes[key].default), AppEdge])
  instance.setDefaultEdgeType('app-edge')

  instance.graphModel.eventCenter.on('delete_edge', (idList: string[]) => {
    idList.forEach((id) => instance.deleteEdge(id))
  })

  lf.value = instance
  return instance
}

/** 节点包围盒是否两两交叠 */
function hasOverlap(items: Array<{ x: number; y: number; width: number; height: number }>): boolean {
  for (let i = 0; i < items.length; i++) {
    for (let j = i + 1; j < items.length; j++) {
      const a = items[i]
      const b = items[j]
      if (
        a.x < b.x + b.width &&
        b.x < a.x + a.width &&
        a.y < b.y + b.height &&
        b.y < a.y + a.height
      ) {
        return true
      }
    }
  }
  return false
}

/**
 * `@antv/layout` 的 DagreLayout 对 ranksep / nodesep 有 **约 3 倍** 的放大。
 *
 * 实测（不是推断）：传 `ranksep: 80` ⇒ 实际列间距 **240**；传 `ranksep: 300` ⇒ **898**。
 *   240 / 80 = 3.00   ·   898 / 300 = 2.99
 * 所以调用方必须按**期望间距**反算入参，直接传期望值会得到 3 倍间距 ——
 * 9 个节点铺成一条链时，这一条就把整图横向撑到 3 倍宽，fitView 只好缩到 0.25（字读不了）。
 *
 * 重测方法：把期望间距改成一个特征值（如 300），跑 `python check_canvas.py` 看列间距读数。
 */
const DAGRE_SPACING_FACTOR = 3

/** 期望的列间距 / 同列节点间距（画布逻辑像素）。 */
const WANT_RANK_GAP = 72
const WANT_NODE_GAP = 56

/**
 * 自动排版：按真实卡片尺寸推间距 → dagre → 自检重叠 → 必要时放大间距重跑。
 * 幂等：同一图连点两次结果稳定；失败只 fitView，不抛错。
 */
function autoLayout(instance: any) {
  if (!instance?.graphModel?.nodes?.length) {
    instance?.fitView?.(24)
    return
  }
  const list = instance.graphModel.nodes as Array<any>
  const boxes = () =>
    list.map((n) => ({
      x: n.x - n.width / 2,
      y: n.y - n.height / 2,
      width: n.width,
      height: n.height
    }))

  // 间距按**期望值**定，再除以 DagreLayout 的放大系数换算成它的入参。
  // ⚠️ 这里不要再用 `Math.max(80/60, …)` 那种下限兜底 —— 那是按"入参"设的下限，
  //    换算之后等于硬性要求 240px 的列间距，直接把期望值吃掉。
  //    下限只用来防止算出 0 或负数。
  let ranksep = Math.max(12, Math.round(WANT_RANK_GAP / DAGRE_SPACING_FACTOR))
  let nodesep = Math.max(10, Math.round(WANT_NODE_GAP / DAGRE_SPACING_FACTOR))

  const maxRetry = 4
  for (let attempt = 0; attempt < maxRetry; attempt++) {
    try {
      instance.extension?.dagre?.layout?.({ rankdir: 'LR', nodesep, ranksep })
    } catch {
      break
    }
    if (!hasOverlap(boxes())) break
    // 重叠就放大一档再排
    ranksep = Math.round(ranksep * 1.35)
    nodesep = Math.round(nodesep * 1.35)
  }
  // 无论自检结果如何，都让人能看到全貌（统一用数值 padding；
  // 本版 LogicFlow 的 fitView 不接受数组形式，传数组会算出 NaN transform）
  instance.fitView(40)
}

function draw(data?: unknown) {
  const instance = ensureLf()
  if (!instance) return
  instance.render((data ?? EMPTY_GRAPH) as Parameters<LogicFlow['render']>[0])
  // 等 HtmlResize 量完真实尺寸；两轮：第二轮用最终高度纠偏
  setTimeout(() => autoLayout(instance), 500)
  setTimeout(() => autoLayout(instance), 1200)
}

onMounted(() => {
  draw(props.data)
})

// 属性驱动：页面只管把图放进 `data`，不需要命令式地喊画布重绘
watch(
  () => props.data,
  (next) => draw(next)
)

onBeforeUnmount(() => {
  lf.value?.destroy?.()
  lf.value = null
})

const render = (data: unknown) => draw(data)
const renderGraphData = (data?: unknown) => draw(data)

function clearGraphData() {
  lf.value?.clearData()
}

/**
 * 把视口移到某个节点并选中它。
 *
 * 发布前校验按 `nodeId` 报错，用户点清单里那一行就要能看见**画布上的哪个节点** ——
 * 否则一张 20 个节点的图里，用户只能靠 `message` 自己猜。（`#198` 接真端点的那一批）
 *
 * 返回 `false` 表示画布上找不到这个 id（issues 可能来自另一张图），调用方要如实说出来，
 * 不要静默什么也不做。
 */
function focusNode(nodeId: string): boolean {
  const instance = lf.value
  if (!instance || !nodeId) return false
  const model = instance.getNodeModelById?.(nodeId)
  if (!model) return false
  instance.focusOn({ id: nodeId })
  instance.selectElementById(nodeId)
  return true
}

const validate = () =>
  lf.value
    ? Promise.all(
        lf.value.graphModel.nodes.map((el: any) => (el?.validate?.() as unknown) ?? Promise.resolve())
      )
    : Promise.resolve([])

const getGraphData = () => lf.value?.getGraphData() ?? EMPTY_GRAPH

const onmousedown = (shapeItem: ShapeItem) => {
  if (!lf.value) return
  if (shapeItem.type) {
    lf.value.dnd.startDrag({
      type: shapeItem.type,
      properties: { ...shapeItem.properties }
    } as any)
  }
  shapeItem.callback?.(lf.value as any)
}

const addNode = (shapeItem: ShapeItem) => {
  if (!lf.value) return
  lf.value.clearSelectElements()
  const { virtualRectCenterPositionX, virtualRectCenterPositionY } =
    lf.value.graphModel.getVirtualRectSize()
  const newNode = lf.value.graphModel.addNode({
    type: shapeItem.type as string,
    properties: shapeItem.properties,
    x: virtualRectCenterPositionX,
    y: virtualRectCenterPositionY - lf.value.graphModel.height / 2
  })
  newNode.isSelected = true
  newNode.isHovered = true
  lf.value.toFront(newNode.id)
}

defineExpose({
  onmousedown,
  validate,
  getGraphData,
  addNode,
  clearGraphData,
  renderGraphData,
  render,
  focusNode,
  /** 用户主动点的「自动排版」 */
  autoLayout: () => autoLayout(lf.value)
})
</script>

<style scoped lang="scss">
.workflow-wrap {
  width: 100%;
  height: 100%;
  position: relative;
}

.workflow-app {
  width: 100%;
  height: 100%;
  position: relative;
}

.workflow-control {
  position: absolute;
  bottom: 24px;
  right: 24px;
  z-index: 2;
}
</style>

<!-- 非 scoped：节点子应用读不到 data-v，工具类必须全局可见 -->
<style lang="scss">
@use '@/pages/workflow/styles/variables.scss' as *;
@use '@/pages/workflow/styles/app.scss' as *;

/* 节点卡片：surface 底 + 发丝描边 + 轻阴影，与 paper 画布底分开。
   颜色向本系统靠（accent / rule / sunken），不再自成一套色板。 */
.workflow-node-container .step-container.app-card {
  background: var(--color-surface);
  border: 1px solid var(--color-rule-strong) !important;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-whisper) !important;
}
</style>
