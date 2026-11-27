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
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
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

/** 关系类型：与后端 `chk_kb_relation_type` 的四类一一对应。 */
const RELATION_LABEL: Record<string, string> = {
  BELONGS_TO: '隶属',
  CONTAINS: '包含',
  RESPONSIBLE_FOR: '负责',
  DEFINES: '定义'
}
const relationLabel = (t: string) => RELATION_LABEL[t] || t

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

/* ───────────────────── 图谱布局 ─────────────────────
 * ★ 环形布局，不做力导向：力导向每次刷新位置都不同，两张截图没法对比，
 * 也没法说「上周这条边还在」。按提及数排大小、按名字定角度，同一份数据画出来是同一张图。
 */
const MAX_DRAWN = 80
const drawn = computed(() => nodes.value.slice(0, MAX_DRAWN))
const hiddenCount = computed(() => Math.max(0, nodes.value.length - drawn.value.length))

const W = 720
const H = 460
const CX = W / 2
const CY = H / 2
const R = 165

const positions = computed(() => {
  const map = new Map<string, { x: number; y: number; r: number }>()
  const list = drawn.value
  const maxMentions = Math.max(1, ...list.map((n) => n.mentions))
  list.forEach((n, i) => {
    const angle = (Math.PI * 2 * i) / Math.max(1, list.length) - Math.PI / 2
    const size = 7 + Math.round((Math.min(n.mentions, maxMentions) / maxMentions) * 9)
    map.set(n.id, { x: CX + R * Math.cos(angle), y: CY + R * Math.sin(angle), r: size })
  })
  return map
})

/** 只画两端都在画面里的边；其余边在「关系」视图里仍能看到（并标注是哪一端缺了）。 */
const drawnEdges = computed(() =>
  edges.value.filter((e) => positions.value.has(e.from) && positions.value.has(e.to))
)

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

const isDimmed = (e: EntityGraphEdge) =>
  !!selectedNode.value && e.from !== selectedNode.value && e.to !== selectedNode.value

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

onMounted(load)
</script>

<template>
  <section class="eg-panel">
    <header class="head">
      <div class="head-main">
        <h3>实体与关系</h3>
        <p class="hint">
          文档摄取时自动抽取实体（机构 / 人名）；关系由标记词规则给出，只认四类：
          隶属、包含、负责、定义。<b>抽不到就没有边</b> —— 没有边不等于没有关系，
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
        empty-text="还没有关系 —— 关系靠标记词规则抽取，点「刷新」重读；文档里没有「隶属 / 包含 / 负责 / 定义」这类说法时，这里本来就该是空的"
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
          点一个实体看它的关系；点一条边看它是从哪一段读出来的。
          <span v-if="hiddenCount">（图里只画了前 {{ drawn.length }} 个实体，另有 {{ hiddenCount }} 个在「实体」视图里）</span>
        </p>
        <svg :viewBox="`0 0 ${W} ${H}`" class="graph" role="img" aria-label="实体关系图">
          <defs>
            <marker id="eg-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
              <path d="M0,0 L0,6 L7,3 z" fill="var(--color-rule-strong)" />
            </marker>
          </defs>
          <g class="edges">
            <line
              v-for="e in drawnEdges"
              :key="e.id"
              :x1="positions.get(e.from)?.x"
              :y1="positions.get(e.from)?.y"
              :x2="positions.get(e.to)?.x"
              :y2="positions.get(e.to)?.y"
              :class="['edge', { dim: isDimmed(e), on: selectedEdge?.id === e.id }]"
              marker-end="url(#eg-arrow)"
              @click="pickEdge(e)"
            />
          </g>
          <g class="nodes">
            <g
              v-for="n in drawn"
              :key="n.id"
              :class="['node', { on: selectedNode === n.id }]"
              @click="pickNode(n.id)"
            >
              <circle :cx="positions.get(n.id)?.x" :cy="positions.get(n.id)?.y" :r="positions.get(n.id)?.r" />
              <text
                :x="positions.get(n.id)?.x"
                :y="(positions.get(n.id)?.y || 0) + (positions.get(n.id)?.r || 0) + 13"
                text-anchor="middle"
              >{{ n.name }}</text>
            </g>
          </g>
        </svg>

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
  background: var(--color-accent-soft);
  color: var(--color-accent-ink);
  font-size: var(--text-2xs);
}
.graph-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.graph-hint {
  margin: 0;
}
.graph {
  width: 100%;
  height: auto;
  background: var(--color-surface-2);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm, 4px);
}
.edge {
  stroke: var(--color-rule-strong);
  stroke-width: 1;
  cursor: pointer;
}
.edge.on {
  stroke: var(--color-accent);
  stroke-width: 2;
}
.edge.dim {
  opacity: 0.18;
}
.node circle {
  fill: var(--color-surface);
  stroke: var(--color-accent-line);
  stroke-width: 1.5;
  cursor: pointer;
}
.node.on circle {
  fill: var(--color-accent-soft);
  stroke: var(--color-accent);
  stroke-width: 2;
}
.node text {
  font-size: 11px;
  fill: var(--color-ink-2);
  pointer-events: none;
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
