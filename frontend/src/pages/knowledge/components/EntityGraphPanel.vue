<script setup lang="ts">
/**
 * 实体与关系（0.9.8 ④⑤⑥，`#370`）。
 *
 * ★ 一个数据源、三个视图：实体 / 关系 / 图谱。三者共用一次快照 —— 分成三个请求会变成
 * 三次往返，并且「实体列表里有 200 个实体，关系里却有指向不在列表里的实体的边」
 * （两个请求之间正好写入）。后端 `/knowledge-bases/{kbId}/entity-graph` 一次取齐。
 *
 * ★ 两个数要分开显示：「被 N 段提到」与「图里有几条边」。一个实体可以被 200 段提到
 * 而没有任何关系（关系靠标记词规则，抽不到就没有边）—— 合成一个数会让
 * 「0 条边」被读成「这个实体不重要」。
 *
 * ★ 每条边都要能回到原文那一段（`segmentId` 是取证位置）。没有它，
 * 用户只能相信「系统说这俩有关系」—— 那是另一类产品。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { getEntityGraph, type EntityGraph, type EntityGraphEdge, type EntityGraphNode } from '@/api/knowledge'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ (e: 'goto-segment', documentId: string): void }>()

const view = ref<'entities' | 'relations' | 'graph'>('entities')
const loading = ref(false)
const graph = ref<EntityGraph | null>(null)

const nodes = computed<EntityGraphNode[]>(() => graph.value?.nodes ?? [])
const edges = computed<EntityGraphEdge[]>(() => graph.value?.edges ?? [])
const truncated = computed(() => graph.value?.truncated ?? false)
const totalEntities = computed(() => graph.value?.totalEntities ?? 0)
const totalRelations = computed(() => graph.value?.totalRelations ?? 0)

/** 实体类型：只把已知的两个翻成中文，其余原样显示（不猜）。 */
const typeLabel = (t: string) =>
  ({ ORG: '组织', PERSON: '人名' } as Record<string, string>)[t] || t || '未标类型'

/** 关系类型：与后端 `chk_kb_relation_type` 的七类一一对应（0.9.9 从四类扩到七类）。 */
const RELATION_LABEL: Record<string, string> = {
  BELONGS_TO: '隶属',
  CONTAINS: '包含',
  PART_OF: '属于',
  RESPONSIBLE_FOR: '负责',
  WORKS_AT: '任职',
  REFERENCES: '依据',
  DEFINES: '定义'
}
const relationLabel = (t: string) => RELATION_LABEL[t] || t

/**
 * 图上不画常驻的边标签，改成「按语义分组着色 + 图例 + 悬停/点击看名字」。
 *
 * ★ 2026-11-28 用户反馈两件事，都是常驻边标签带来的：
 *   ① 拖动画布后标签不跟着走（echarts graph 的 edgeLabel 在 roam 之后位置不重算）；
 *   ② 其中一个标签是白色的、看不见 —— 它的字色取 `--color-ink-3`，而这个令牌**不存在**，
 *      探针解析不出来就继承了根元素的颜色，暗色环境下正好是浅色，白底白字。
 *   标签去掉之后这两个问题一并消失，图也清爽：谁和谁什么关系，点那条边看下面。
 */
type RelationGroup = 'hierarchy' | 'duty' | 'document'

const RELATION_GROUP: Record<string, RelationGroup> = {
  BELONGS_TO: 'hierarchy',
  CONTAINS: 'hierarchy',
  PART_OF: 'hierarchy',
  RESPONSIBLE_FOR: 'duty',
  WORKS_AT: 'duty',
  REFERENCES: 'document',
  DEFINES: 'document'
}

const groupOf = (t: string): RelationGroup => RELATION_GROUP[t] || 'hierarchy'

/** 图例只列这一张图里真的出现过的分组 —— 空图例同样是"看着做了、其实没内容"。 */
const legendGroups = computed(() => {
  const seen = new Set<RelationGroup>()
  for (const e of drawnEdges.value) seen.add(groupOf(e.type))
  const all: { key: RelationGroup; label: string }[] = [
    { key: 'hierarchy', label: '层级 / 归属' },
    { key: 'duty', label: '责任 / 任职' },
    { key: 'document', label: '依据 / 定义' }
  ]
  return all.filter((g) => seen.has(g.key))
})

const nameOf = computed(() => {
  const map = new Map<string, string>()
  for (const n of nodes.value) map.set(n.id, n.name)
  return (id: string) => map.get(id) || '（不在本页实体里）'
})

/** 每个实体的边数 —— 与「被多少段提到」是两个数，见文件头。 */
const degreeOf = computed(() => {
  const map = new Map<string, number>()
  for (const e of edges.value) {
    map.set(e.from, (map.get(e.from) || 0) + 1)
    map.set(e.to, (map.get(e.to) || 0) + 1)
  }
  return (id: string) => map.get(id) || 0
})

/* ───────────────────── 图谱（echarts graph） ─────────────────────
 * ★ 2026-11-28 从手写 SVG 换成 echarts graph：用户原话「渲染的有点生硬」。
 *   标签自动避让、曲线边、箭头、缩放拖拽都是现成的，而 echarts 本来就在依赖表里
 *   （`frontend/README.md` 白名单 + `GuardTrendChart.vue` 已有用法）。
 * ★ 布局仍然固定：`layout: 'circular'`，环上顺序由实体顺序决定。不用力导向 ——
 *   力导向每次刷新位置都不同，两张截图没法对比，也说不清「上周这条边还在」。
 */
const MAX_DRAWN = 80
const drawn = computed(() => nodes.value.slice(0, MAX_DRAWN))
const hiddenCount = computed(() => Math.max(0, nodes.value.length - drawn.value.length))
const drawnIds = computed(() => new Set(drawn.value.map((n) => n.id)))
/** 只画两端都在画面里的边；其余边在「关系」视图里仍能看到（并标注哪一端缺了）。 */
const drawnEdges = computed(
  () => edges.value.filter((e) => drawnIds.value.has(e.from) && drawnIds.value.has(e.to))
)

const graphEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/** 令牌是 oklch，echarts 只认 rgb/hex ⇒ 用一次性探针取浏览器算出来的值。 */
const cssColor = (token: string): string => {
  const probe = document.createElement('span')
  probe.style.color = `var(${token})`
  document.documentElement.appendChild(probe)
  const resolved = getComputedStyle(probe).color
  probe.remove()
  return resolved
}

const selectedNode = ref('')
const selectedEdge = ref<EntityGraphEdge | null>(null)

const relatedEdges = computed(() =>
  selectedNode.value
    ? edges.value.filter((e) => e.from === selectedNode.value || e.to === selectedNode.value)
    : []
)

const pickNode = (id: string) => {
  selectedEdge.value = null
  selectedNode.value = selectedNode.value === id ? '' : id
}

const pickEdge = (e: EntityGraphEdge) => {
  selectedNode.value = ''
  selectedEdge.value = selectedEdge.value?.id === e.id ? null : e
}

/** echarts 回调里的 data 形状（它给的是 `unknown`，这里收窄一次就够）。 */
type GraphItem = {
  id?: string
  name?: string
  source?: string
  target?: string
  type?: string
  mentions?: number
}
type GraphParams = { dataType?: string; data?: GraphItem }

const nameById = (id: string) => nodes.value.find((n) => n.id === id)?.name || '（不在本页实体里）'

/** 三种分组各一个颜色（令牌解析成 rgb —— echarts 不认 oklch）。 */
const lineColorOf = (t: string): string => {
  const group = groupOf(t)
  if (group === 'duty') return cssColor('--color-accent')
  if (group === 'document') return cssColor('--color-ok')
  return cssColor('--color-rule-strong')
}

const renderGraph = () => {
  if (!chart) return
  const ink = cssColor('--color-ink-2')
  const rule = cssColor('--color-rule-strong')
  const accent = cssColor('--color-accent')
  const accentSoft = cssColor('--color-accent-soft')
  const warn = cssColor('--color-warn')
  const warnSoft = cssColor('--color-warn-soft')
  const surface = cssColor('--color-surface')
  const maxMentions = Math.max(1, ...drawn.value.map((n) => n.mentions))

  // ★ 同一对实体之间可以有多条边（「包含」与「隶属」会在同一对节点上并存 —— 见 #379 的存量问题）。
  //   echarts 的 graph 对同 source/target 的多条 link 不做避让：curveness 一样就完全重叠，
  //   图上只剩最后画的那条 —— 于是「关系 5 条」的库在图上只看得见 2 根线（用户 2026-11-28 报的
  //   「图谱的数据和关系的数据对不上」就是这个）。按序号分到两侧、幅度逐级加大，才能看出
  //   「这两点之间有几件事」。
  const pairSeen = new Map<string, number>()
  const curvenessOf = (from: string, to: string): number => {
    const key = from <= to ? `${from}|${to}` : `${to}|${from}`
    const k = pairSeen.get(key) ?? 0
    pairSeen.set(key, k + 1)
    const base = 0.14
    const level = k >> 1
    return (k % 2 === 0 ? 1 : -1) * (base + level * 0.2)
  }

  chart.setOption(
    {
      tooltip: {
        confine: true,
        backgroundColor: surface,
        borderColor: rule,
        textStyle: { color: ink, fontSize: 12 },
        formatter: (raw: unknown) => {
          const p = raw as GraphParams
          const d = p.data || {}
          if (p.dataType === 'edge') {
            return `${d.source ? nameById(d.source) : ''} · ${relationLabel(String(d.type || ''))} · ${d.target ? nameById(d.target) : ''}`
          }
          const deg = d.id ? edges.value.filter((e) => e.from === d.id || e.to === d.id).length : 0
          return `${d.name || ''}<br/>被 ${d.mentions ?? 0} 段提到 · ${deg} 条关系`
        }
      },
      series: [
        {
          type: 'graph',
          layout: 'circular',
          circular: { rotateLabel: false },
          // circular 会把环铺满容器 ⇒ 上下两个节点的标签贴在容器边缘，同对节点的外侧弧线
          // 还会越过边界被裁掉。缩到 0.85 把这一圈留白换回来。
          zoom: 0.85,
          roam: true,
          draggable: false,
          selectedMode: false,
          data: drawn.value.map((n) => {
            // 两类实体用两种颜色：组织（蓝）与人名（琥珀）。原先一律"白底浅灰边"，在浅灰画布上
            // 几乎看不见圆点 —— 用户 2026-11-28 的原话是「渲染的有点生硬」。
            const person = n.type === 'PERSON'
            const stroke = person ? warn : accent
            return {
              id: n.id,
              name: n.name,
              value: n.mentions,
              mentions: n.mentions,
              symbolSize: 10 + (Math.min(n.mentions, maxMentions) / maxMentions) * 12,
              itemStyle: {
                color: person ? warnSoft : accentSoft,
                borderColor: stroke,
                borderWidth: 1.5
              },
              // 标签放**下面**：放右侧时它会被相邻的线穿过去，读起来像错字。
              // 标签加一层画布色的描边：线从字底下穿过时字仍读得出来（不加就只能靠躲）。
              label: {
                show: true,
                color: ink,
                fontSize: 12,
                position: 'bottom',
                distance: 5,
                textBorderColor: surface,
                textBorderWidth: 3
              },
              emphasis: {
                itemStyle: { borderWidth: 2.5, shadowBlur: 6, shadowColor: stroke },
                label: { color: stroke, fontWeight: 600 }
              }
            }
          }),
          links: drawnEdges.value.map((e) => ({
            id: e.id,
            source: e.from,
            target: e.to,
            type: e.type,
            lineStyle: {
              color: lineColorOf(e.type),
              width: 1.4,
              curveness: curvenessOf(e.from, e.to),
              opacity: 0.9
            }
          })),
          labelLayout: { hideOverlap: true },
          edgeSymbol: ['none', 'arrow'],
          edgeSymbolSize: 7,
          emphasis: {
            focus: 'adjacency',
            lineStyle: { width: 2, color: accent }
          }
        }
      ]
    },
    true
  )
}

const onChartClick = (raw: unknown) => {
  const p = raw as GraphParams
  const d = p.data || {}
  if (p.dataType === 'edge' && d.id) {
    const hit = edges.value.find((e) => e.id === d.id)
    if (hit) pickEdge(hit)
    return
  }
  if (d.id) pickNode(d.id)
}

const resize = () => chart?.resize()

const gotoSource = (e: EntityGraphEdge) => {
  if (!e.documentId) {
    ElMessage.warning('这条边没有取证位置（存量回填那批没有段落），回不到原文')
    return
  }
  emit('goto-segment', e.documentId)
}

/* ───────────────────── 加载 ───────────────────── */

const load = async () => {
  loading.value = true
  try {
    graph.value = await getEntityGraph(props.kbId)
    selectedNode.value = ''
    selectedEdge.value = null
  } catch (e) {
    ElMessage.error((e as Error).message || '加载实体与关系失败')
    graph.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})

/* 图谱是 v-if 里的一块容器：切到这一页时才建实例，切走时不销毁（回来还是同一张图）。 */
watch([() => view.value, () => graph.value], async () => {
  if (view.value !== 'graph') return
  await nextTick()
  if (!graphEl.value) return
  // ★ 图谱这块是 `v-if` 里的一块容器：切走时 DOM 被销毁，切回来是**新**的节点。
  //   而 echarts 实例仍拿着旧的、已脱离文档的那个 —— 此后 setOption/resize 都作用在它身上，
  //   容器里一个 canvas 都没有，看起来就是「第二次进图谱不显示」（实测：容器 1267x438 在，
  //   canvas 数量 0）。判据只能是"实例的 DOM 还是不是眼前这个容器"。
  if (chart && chart.getDom() !== graphEl.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) {
    chart = echarts.init(graphEl.value)
    chart.on('click', onChartClick)
  }
  chart.resize()
  renderGraph()
})
</script>

<template>
  <section class="eg-panel">
    <header class="head">
      <div class="head-main">
        <h3>实体与关系</h3>
        <p class="hint">
          文档摄取时自动抽取实体（机构 / 人名）；关系由标记词规则给出，认七类：
          隶属、包含、属于、负责、任职、依据、定义。<b>抽不到就没有边</b> —— 没有边不等于没有关系，
          只等于「这一段没有用「隶属」这类词说出来」。
        </p>
      </div>
      <div class="views">
        <button type="button" class="view-btn" :class="{ on: view === 'entities' }" @click="view = 'entities'">
          实体 {{ nodes.length }}
        </button>
        <button
          type="button"
          class="view-btn"
          :class="{ on: view === 'relations' }"
          :title="`库里共 ${totalRelations} 条关系，这一页取了 ${edges.length} 条`"
          @click="view = 'relations'"
        >
          关系 {{ edges.length }}
        </button>
        <button type="button" class="view-btn" :class="{ on: view === 'graph' }" @click="view = 'graph'">
          图谱
        </button>
        <el-button size="small" :loading="loading" data-test="eg-refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <p v-if="truncated" class="notice warn">
      库里一共 {{ totalEntities }} 个实体，这一页只取了前 {{ nodes.length }} 个（按被提及的段数排序）：
      图里没有的实体不代表库里没有。
    </p>

    <p v-if="!loading && !nodes.length" class="empty">
      这个库还没有实体 —— 文档解析完成后会自动抽取；如果刚上传完，等摄取跑完再点「刷新」。
    </p>

    <template v-else>
      <el-table
        v-if="view === 'entities'"
        :data="nodes"
        size="small"
        class="tbl"
        empty-text="没有实体 —— 点「刷新」重新读一次；文档还没摄取完时这里是空的"
      >
        <el-table-column label="实体" min-width="200">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <span class="kind">{{ typeLabel(row.type) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="被多少段提到" width="140">
          <template #default="{ row }">
            <span class="num">{{ row.mentions }}</span>
            <span class="micro muted"> 段</span>
          </template>
        </el-table-column>
        <el-table-column label="关系数" width="110">
          <template #default="{ row }">
            <span class="num">{{ degreeOf(row.id) }}</span>
            <span class="micro muted"> 条</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="150">
          <template #default="{ row }">
            <span class="micro muted">{{ row.source || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            <span class="micro muted">{{ Number(row.confidence).toFixed(2) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-table
        v-else-if="view === 'relations'"
        :data="edges"
        size="small"
        class="tbl"
        empty-text="还没有关系 —— 关系靠标记词规则抽取，点「刷新」重读；文档里没有「隶属 / 包含 / 属于 / 负责 / 任职 / 依据 / 定义」这类说法时，这里本来就该是空的"
      >
        <el-table-column label="关系" min-width="320">
          <template #default="{ row }">
            <b>{{ nameOf(row.from) }}</b>
            <span class="rel">{{ relationLabel(row.type) }}</span>
            <b>{{ nameOf(row.to) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="取证" width="160">
          <template #default="{ row }">
            <el-button v-if="row.segmentId" link size="small" @click="gotoSource(row)">看原文</el-button>
            <span v-else class="micro muted">无取证位置</span>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            <span class="micro muted">{{ Number(row.confidence).toFixed(2) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-else class="graph-wrap">
        <p class="micro muted graph-hint">
          点一个实体看它的关系（相邻的会高亮）；点一条边看它是从哪一段读出来的。图上可以滚轮缩放、拖动平移。
          <span v-if="hiddenCount">（图里只画了前 {{ drawn.length }} 个实体，另有 {{ hiddenCount }} 个在「实体」视图里）</span>
        </p>
        <p v-if="legendGroups.length" class="legend">
          <span class="micro muted">线色</span>
          <span v-for="g in legendGroups" :key="g.key" class="legend-item">
            <i :class="['dot', g.key]" />{{ g.label }}
          </span>
          <span class="micro muted">—— 点一条边看具体关系与取证</span>
        </p>
        <div ref="graphEl" class="graph" role="img" aria-label="实体关系图" />


        <div v-if="selectedEdge" class="pick">
          <b>{{ nameOf(selectedEdge.from) }}</b>
          <span class="rel">{{ relationLabel(selectedEdge.type) }}</span>
          <b>{{ nameOf(selectedEdge.to) }}</b>
          <el-button v-if="selectedEdge.segmentId" link size="small" @click="gotoSource(selectedEdge)">
            看这一段原文
          </el-button>
          <span v-else class="micro muted">这条边没有取证位置（存量回填那批没有段落）</span>
        </div>
        <div v-else-if="selectedNode" class="pick">
          <b>{{ nameOf(selectedNode) }}</b>
          <span class="micro muted">
            · 被 {{ nodes.find((n) => n.id === selectedNode)?.mentions || 0 }} 段提到
            · {{ relatedEdges.length }} 条关系
          </span>
          <span v-if="!relatedEdges.length" class="micro muted">（没有关系不等于不重要 —— 见上方说明）</span>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.eg-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  flex-wrap: wrap;
}
.head-main h3 {
  margin: 0 0 4px;
  font-size: var(--text-sm);
}
.hint {
  margin: 0;
  max-width: 62ch;
  font-size: var(--text-xs);
  color: var(--color-ink-2);
  line-height: 1.6;
}
.views {
  display: flex;
  align-items: center;
  gap: 4px;
}
.view-btn {
  border: 1px solid var(--color-rule);
  background: var(--color-surface);
  color: var(--color-ink-2);
  border-radius: var(--radius-sm, 4px);
  padding: 3px 10px;
  font-size: var(--text-xs);
  cursor: pointer;
}
.view-btn.on {
  border-color: var(--color-accent);
  color: var(--color-accent);
  background: var(--color-accent-soft);
}
.notice {
  margin: 0;
  padding: 6px 10px;
  border-radius: var(--radius-sm, 4px);
  font-size: var(--text-xs);
}
.notice.warn {
  background: var(--color-warn-soft);
  color: var(--color-warn);
}
.empty {
  margin: 0;
  padding: var(--space-md);
  font-size: var(--text-xs);
  color: var(--color-ink-2);
  background: var(--color-surface-2);
  border-radius: var(--radius-sm, 4px);
}
.tbl {
  width: 100%;
}
.kind {
  margin-left: 6px;
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
}
.num {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.rel {
  margin: 0 6px;
  padding: 0 6px;
  border-radius: 999px;
  /* ★ 底色是 `accent-soft`（oklch 96%，很浅），字必须用**深色**的 accent-hover（52%）。
     2026-11-28 之前这里写的是 `--color-accent-ink`（oklch 99%，近白）—— 那个令牌的语义是
     「压在 accent 实底上的前景色」，放到浅底上就是白字白底，四类关系标签几乎看不见。 */
  background: var(--color-accent-soft);
  color: var(--color-accent-hover);
  font-size: var(--text-2xs);
  font-weight: 500;
}
.graph-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.graph-hint {
  margin: 0;
}
.legend {
  margin: 0;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.dot {
  display: inline-block;
  width: 14px;
  height: 2px;
  border-radius: 1px;
}
.dot.hierarchy {
  background: var(--color-rule-strong);
}
.dot.duty {
  background: var(--color-accent);
}
.dot.document {
  background: var(--color-ok);
}

/* echarts 的画布要一个确定的高度（它不参与 flex 剩余空间分配，给 auto 会塌成 0）。 */
.graph {
  width: 100%;
  height: 440px;
  background: var(--color-surface-2);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm, 4px);
}
.pick {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 6px 10px;
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm, 4px);
  font-size: var(--text-xs);
}
</style>
