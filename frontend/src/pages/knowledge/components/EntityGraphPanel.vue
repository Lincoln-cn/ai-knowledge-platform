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
import {
  getEntities,
  getEntityGraph,
  getRelations,
  type EntityGraph,
  type EntityGraphEdge,
  type EntityGraphNode,
  type RelationRow
} from '@/api/knowledge'

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
  ({ ORG: '组织', PERSON: '人名', MISSION: '职责', DOC: '文书', TERM: '术语', OTHER: '其他' } as Record<
    string,
    string
  >)[t] ||
  t ||
  '未标类型'

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

/** 图上画了几个实体（= 有关系的实体数）与"库里没有关系的实体有多少"。 */
const involvedCount = computed(() => nodes.value.length)
const noEdgeCount = computed(() => Math.max(0, totalEntities.value - involvedCount.value))

const typeOfNode = (id: string) => nodes.value.find((n) => n.id === id)?.type || ''

const nameOf = computed(() => {
  const map = new Map<string, string>()
  for (const n of nodes.value) map.set(n.id, n.name)
  return (id: string) => map.get(id) || '（不在本页实体里）'
})

/* ───────────────────── 两个表格的分页 ─────────────────────
 * ★ 2026-11-29 用户原话：「库里一共 1408 个实体，这一页只取了前 500 个…… 页面上需要分页」。
 *   分页必须在库里做：一次全取再让浏览器切页，等于把「库里有多少」变成「浏览器一次能吃多少」。
 * ★ 实体与关系各有一份页码/页大小 —— 合用一个会让"翻关系的时候实体表也跳页"。
 */
const entityPage = ref(1)
const entitySize = ref(50)
const entityType = ref('')
const entityKeyword = ref('')
const entityRows = ref<EntityGraphNode[]>([])
const entityTotal = ref(0)
const entityLoading = ref(false)

const relationPage = ref(1)
const relationSize = ref(50)
const relationRows = ref<RelationRow[]>([])
const relationTotal = ref(0)
const relationLoading = ref(false)

/** 实体类型下拉：与抽取器产出的类型一致（MISSION 是 2026-11-28 新加的「职责」）。 */
const ENTITY_TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'ORG', label: '组织' },
  { value: 'PERSON', label: '人名' },
  { value: 'MISSION', label: '职责' },
  { value: 'DOC', label: '文书' },
  { value: 'TERM', label: '术语' }
]

/** 关系数：从图谱拿到的那份边统计（图谱一次拉全量边，≤2000 条）。 */
const degreeById = ref(new Map<string, number>())

const loadEntities = async () => {
  entityLoading.value = true
  try {
    const page = await getEntities(props.kbId, {
      page: entityPage.value,
      size: entitySize.value,
      type: entityType.value || undefined,
      keyword: entityKeyword.value || undefined
    })
    entityRows.value = page.list
    entityTotal.value = page.total
  } catch (e) {
    ElMessage.error((e as Error).message || '加载实体失败')
    entityRows.value = []
    entityTotal.value = 0
  } finally {
    entityLoading.value = false
  }
}

const loadRelations = async () => {
  relationLoading.value = true
  try {
    const page = await getRelations(props.kbId, {
      page: relationPage.value,
      size: relationSize.value
    })
    relationRows.value = page.list
    relationTotal.value = page.total
  } catch (e) {
    ElMessage.error((e as Error).message || '加载关系失败')
    relationRows.value = []
    relationTotal.value = 0
  } finally {
    relationLoading.value = false
  }
}

/** 换筛选条件要回到第 1 页 —— 否则会停在"第 7 页，共 2 页"这种空页上。 */
const onEntityFilterChanged = () => {
  entityPage.value = 1
  loadEntities()
}

/** 每个实体的边数 —— 与「被多少段提到」是两个数，见文件头。 */
const degreeOf = (id: string) => degreeById.value.get(id) || 0

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

/** 关系表格行的「看原文」：行是 RelationRow（带 documentId），与图上的边形状不同。 */
const gotoRelation = (r: RelationRow) => {
  if (!r.documentId) {
    ElMessage.warning('这条关系没有取证位置（存量回填那批没有段落），回不到原文')
    return
  }
  emit('goto-segment', r.documentId)
}

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
    // ★ 图谱先到：它的边要用来自两个表格的「关系数」列，晚到会让那一列先显示 0 再跳数。
    graph.value = await getEntityGraph(props.kbId)
    const degree = new Map<string, number>()
    for (const e of graph.value.edges) {
      degree.set(e.from, (degree.get(e.from) || 0) + 1)
      degree.set(e.to, (degree.get(e.to) || 0) + 1)
    }
    degreeById.value = degree
    selectedNode.value = ''
    selectedEdge.value = null
  } catch (e) {
    ElMessage.error((e as Error).message || '加载实体与关系失败')
    graph.value = null
  } finally {
    loading.value = false
  }
  await Promise.all([loadEntities(), loadRelations()])
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
          实体 {{ entityTotal }}
        </button>
        <button
          type="button"
          class="view-btn"
          :class="{ on: view === 'relations' }"
          @click="view = 'relations'"
        >
          关系 {{ relationTotal }}
        </button>
        <button type="button" class="view-btn" :class="{ on: view === 'graph' }" @click="view = 'graph'">
          图谱
        </button>
        <el-button size="small" :loading="loading" data-test="eg-refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <p v-if="truncated" class="notice warn">
      库里一共 {{ totalRelations }} 条关系，图上只画了前 {{ edges.length }} 条（上限
      {{ graph?.relationLimit }} 条）：剩下的在「关系」页签里翻页看。
    </p>

    <p v-if="!loading && !nodes.length" class="empty">
      这个库还没有实体 —— 文档解析完成后会自动抽取；如果刚上传完，等摄取跑完再点「刷新」。
    </p>

    <template v-else>
      <div v-if="view === 'entities'" class="filters">
        <el-select v-model="entityType" class="f-type" @change="onEntityFilterChanged">
          <el-option
            v-for="o in ENTITY_TYPE_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <el-input
          v-model="entityKeyword"
          class="f-kw"
          placeholder="按名字搜，比如「公司」"
          clearable
          @keyup.enter="onEntityFilterChanged"
          @clear="onEntityFilterChanged"
        />
        <button type="button" class="mini" @click="onEntityFilterChanged">筛选</button>
        <span class="micro muted">共 {{ entityTotal }} 个实体</span>
      </div>

      <el-table
        v-if="view === 'entities'"
        v-loading="entityLoading"
        :data="entityRows"
        size="small"
        class="tbl"
        height="440"
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

      <el-pagination
        v-if="view === 'entities' && entityTotal > entitySize"
        v-model:current-page="entityPage"
        v-model:page-size="entitySize"
        class="pager"
        layout="prev, pager, next, sizes, total"
        :page-sizes="[20, 50, 100, 200]"
        :total="Number(entityTotal)"
        @current-change="loadEntities"
        @size-change="onEntityFilterChanged"
      />

      <el-table
        v-if="view === 'relations'"
        v-loading="relationLoading"
        :data="relationRows"
        size="small"
        class="tbl"
        height="440"
        empty-text="还没有关系 —— 关系靠标记词规则抽取，点「刷新」重读；文档里没有「隶属 / 包含 / 属于 / 负责 / 任职 / 依据 / 定义」这类说法时，这里本来就该是空的"
      >
        <el-table-column label="这条关系" min-width="420">
          <template #default="{ row }">
            <!--
              ★ 2026-11-29 重做：原来是「门内部」「负责」「财政部」「查看这一段原文」挤在一行，
                三个信息（左端 / 关系 / 右端）没有层次，读起来像四个并列的词。
                现在把关系做成中间的一条带箭头的通道，两端各自带类型标签 —— 一眼能看出方向。
            -->
            <div class="rel-line">
              <span class="rel-end">
                <span class="kind sm">{{ typeLabel(row.fromType) }}</span>
                <b>{{ row.fromName }}</b>
              </span>
              <span class="rel-arrow">
                <span class="rel" :class="groupOf(row.type)">{{ relationLabel(row.type) }}</span>
                <i class="arrow" :class="groupOf(row.type)" />
              </span>
              <span class="rel-end">
                <span class="kind sm">{{ typeLabel(row.toType) }}</span>
                <b>{{ row.toName }}</b>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="原文出处" width="150">
          <template #default="{ row }">
            <el-button v-if="row.segmentId" link size="small" @click="gotoRelation(row)">
              查看这一段原文
            </el-button>
            <span v-else class="micro muted">没有取证位置</span>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            <span class="micro muted">{{ Number(row.confidence).toFixed(2) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="view === 'relations' && relationTotal > relationSize"
        v-model:current-page="relationPage"
        v-model:page-size="relationSize"
        class="pager"
        layout="prev, pager, next, sizes, total"
        :page-sizes="[20, 50, 100, 200]"
        :total="Number(relationTotal)"
        @current-change="loadRelations"
        @size-change="loadRelations"
      />

      <div v-if="view === 'graph'" class="graph-wrap">
        <p class="micro muted graph-hint">
          图上画的是<b>有关系</b>的实体：{{ involvedCount }} 个实体、{{ edges.length }} 条边。
          库里共 {{ totalEntities }} 个实体
          <template v-if="noEdgeCount">
            ，另有 {{ noEdgeCount }} 个没有任何关系（关系靠标记词规则抽取，抽不到就没有边 ——
            它们在「实体」页签里，按「关系数 = 0」能找到）
          </template>
          。
          <span v-if="hiddenCount">图上最多画 {{ MAX_DRAWN }} 个点，其余 {{ hiddenCount }} 个在表格里。</span>
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
          <span class="rel-end">
            <span class="kind sm">{{ typeLabel(typeOfNode(selectedEdge.from)) }}</span>
            <b>{{ nameOf(selectedEdge.from) }}</b>
          </span>
          <span class="rel-arrow">
            <span class="rel" :class="groupOf(selectedEdge.type)">
              {{ relationLabel(selectedEdge.type) }}
            </span>
            <i class="arrow" :class="groupOf(selectedEdge.type)" />
          </span>
          <span class="rel-end">
            <span class="kind sm">{{ typeLabel(typeOfNode(selectedEdge.to)) }}</span>
            <b>{{ nameOf(selectedEdge.to) }}</b>
          </span>
          <el-button v-if="selectedEdge.segmentId" link size="small" @click="gotoSource(selectedEdge)">
            查看这一段原文
          </el-button>
          <span v-else class="micro muted">这条边没有取证位置（存量回填那批没有段落）</span>
        </div>
        <div v-else-if="selectedNode" class="pick">
          <span class="rel-end">
            <span class="kind sm">{{ typeLabel(typeOfNode(selectedNode)) }}</span>
            <b>{{ nameOf(selectedNode) }}</b>
          </span>
          <span class="micro muted">
            被 {{ nodes.find((n) => n.id === selectedNode)?.mentions || 0 }} 段提到 ·
            {{ relatedEdges.length }} 条关系
          </span>
          <span v-if="!relatedEdges.length" class="micro muted">
            （没有关系不等于不重要 —— 见上方说明）
          </span>
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
  display: inline-block;
  margin-left: 6px;
  padding: 0 5px;
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-xs);
  font-size: var(--text-2xs);
  line-height: 1.5;
  color: var(--color-muted);
  background: var(--color-surface-2);
  /* 不加 nowrap：名字很长时宁可让徽标掉到下一行，也不要它压住名字 */
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

/* ── 两个表格的分页与筛选（2026-11-29） ── */
.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.f-type {
  width: 132px;
}

.f-kw {
  width: 220px;
}

.mini {
  padding: 4px 12px;
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-ink);
  cursor: pointer;
  font-size: var(--text-sm);
}

.mini:hover {
  border-color: var(--color-accent);
  color: var(--color-accent);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

/* ── 关系行：左端 / 关系 / 右端，三段各自成块（原来是四个词挤一行） ── */
.rel-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.rel-end {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}

.rel-end b {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 220px;
}

.rel-arrow {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
}

.arrow {
  position: relative;
  display: inline-block;
  width: 26px;
  height: 1px;
  background: var(--color-rule-strong);
}

.arrow::after {
  content: '';
  position: absolute;
  right: 0;
  top: -3px;
  border-left: 5px solid var(--color-rule-strong);
  border-top: 3.5px solid transparent;
  border-bottom: 3.5px solid transparent;
}

.arrow.duty {
  background: var(--color-accent);
}

.arrow.duty::after {
  border-left-color: var(--color-accent);
}

.arrow.document {
  background: var(--color-ok);
}

.arrow.document::after {
  border-left-color: var(--color-ok);
}

.kind.sm {
  padding: 0 4px;
  font-size: var(--text-2xs);
}

</style>
