<template>
  <div class="page">
    <!-- ── 列表态 ──
         消费者形态（07 §五）：只有卡片，没有详情壳 —— 文档与分段对他不可见（07 §4.2），
         所以「点进去看」这一层根本不该存在，而不是点进去之后少几块。 -->
    <template v-if="!openedKb">
      <PageHeader
        title="知识库"
        :desc="consumer
          ? '这些知识库可以被对话直接引用；内容由知识运营维护，你不需要做任何维护动作'
          : '知识库是文档、分段与检索的容器。向量模型创建时选定，有向量化内容后锁定'"
      >
        <template #actions>
          <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
          <el-button v-if="canCreate" size="small" type="primary" @click="openCreate">创建知识库</el-button>
        </template>
      </PageHeader>

      <div class="filters panel">
        <el-input
          v-model="query.keyword"
          size="small"
          placeholder="搜索知识库名称或描述"
          style="width: 230px"
          clearable
          :prefix-icon="Search"
          @keyup.enter="onFilterChange"
          @clear="onFilterChange"
        />
        <div class="cats">
          <button
            type="button"
            class="cat"
            :class="{ on: query.category === '' }"
            @click="setCategory('')"
          >全部</button>
          <button
            v-for="c in categories"
            :key="c.id"
            type="button"
            class="cat"
            :class="{ on: query.category === c.name }"
            @click="setCategory(c.name)"
          >
            {{ c.name }}
            <span class="cat-count num">{{ c.baseCount }}</span>
          </button>
        </div>
        <span class="grow" />
        <span class="micro muted">共 {{ total }} 个</span>
      </div>

      <div v-loading="loading" class="grid">
        <el-card
          v-for="kb in list"
          :key="kb.id"
          shadow="never"
          class="kb-card"
          :class="{ off: !kb.enabled, readonly: consumer }"
          @click="consumer ? undefined : openDetail(kb.id)"
        >
          <div class="kc-head">
            <img
              v-if="String(kb.icon || '').startsWith('http')"
              :src="kb.icon"
              class="kc-icon-img"
              alt=""
            />
            <span v-else class="kc-icon">{{ kb.icon }}</span>
            <div class="kc-main">
              <div class="kc-name ellipsis">{{ kb.name }}</div>
              <div class="kc-desc micro muted ellipsis">{{ kb.description || '暂无描述' }}</div>
            </div>
            <span class="grow" />
            <el-switch
              v-if="!consumer"
              :model-value="kb.enabled"
              size="small"
              @click.stop
              @change="toggleEnabled(kb)"
            />
          </div>

          <!-- 消费者只看「有多少文档」，不看分段 / 字数 —— 后两个词属于建设语汇 -->
          <div class="kc-meta micro muted">
            <span><b class="num">{{ kb.documentCount }}</b> 文档</span>
            <template v-if="!consumer">
              <el-divider direction="vertical" />
              <span><b class="num">{{ kb.segmentCount }}</b> 分段</span>
              <el-divider direction="vertical" />
              <span><b class="num">{{ formatChars(kb.charCount) }}</b> 字</span>
            </template>
          </div>

          <div class="kc-foot">
            <el-tag size="small" effect="plain">{{ kb.category }}</el-tag>
            <el-tag v-if="kb.embeddingLocked && !consumer" size="small" type="warning" effect="plain">向量已锁定</el-tag>
            <span class="grow" />
            <el-button v-if="!consumer" link size="small" type="danger" @click.stop="remove(kb)">删除</el-button>
          </div>
        </el-card>

        <div v-if="!loading && !list.length" class="empty panel">
          <div class="empty-title">
            {{ consumer
              ? '暂时没有可用的知识库'
              : query.keyword || query.category ? '当前筛选下没有知识库' : '还没有知识库' }}
          </div>
          <p class="micro muted">
            {{ consumer ? '知识运营发布后，这里会出现可以被对话引用的知识库' : '知识库是文档、分段与检索的容器' }}
          </p>
          <el-button v-if="canCreate" size="small" type="primary" @click="openCreate">创建第一个知识库</el-button>
        </div>
      </div>

      <div v-if="total > pageSize" class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="pageSize"
          layout="total, prev, pager, next"
          :total="total"
          @current-change="fetchList"
        />
      </div>
    </template>

    <!-- ── 详情壳：左菜单 + 内容 ── -->
    <template v-else>
      <PageHeader :title="openedKb.name" :desc="openedKb.description">
        <template #after-title>
          <el-tag v-if="!openedKb.enabled" size="small" type="info" effect="plain">已停用</el-tag>
        </template>
        <template #actions>
          <el-button size="small" @click="closeDetail">返回列表</el-button>
        </template>
      </PageHeader>

      <div class="detail">
        <!--
          入库流水线 —— 本页唯一那一次深色石墨带（Cobalt 签名 4 + 8，见 design.md § 仪器面板）。
          放在 Tab 内容**之上**：切到分段 / 命中测试 / 设置它也要在，
          它是「这台机器此刻在做什么」的页面级读数，不是某一个面板的附属件。
          读数全部来自 ingestionApi.getIngestionProgress 的服务端真实进度 —— 不编数字。
        -->
        <InstrumentCard
          v-if="panelVisible"
          class="detail-instrument"
          title="入库流水线"
          :status="ingestStatus"
          :rows="ingestRows"
        />

        <aside class="side">
          <button
            v-for="tab in TABS"
            :key="tab.key"
            type="button"
            class="side-item"
            :class="{ on: activeTab === tab.key }"
            @click="setTab(tab.key)"
          >{{ tab.label }}</button>
        </aside>

        <main class="content">
          <DocumentPanel
            v-if="activeTab === 'documents'"
            :kb-id="openedKb.id"
            @goto-segments="gotoSegments"
          />
          <SegmentPanel
            v-else-if="activeTab === 'segments'"
            :kb-id="openedKb.id"
            :initial-document-id="segmentDocId"
          />
          <HitTestPanel
            v-else-if="activeTab === 'hittest'"
            :kb-id="openedKb.id"
          />
          <SettingsPanel
            v-else-if="activeTab === 'settings'"
            :kb="openedKb"
            @changed="onKbChanged"
            @deleted="closeDetail"
          />
        </main>
      </div>
    </template>

    <CreateWizard ref="wizardRef" @created="onCreated" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { ingestionApi, knowledgeApi } from '@/api'
import type { DocumentStatus, IngestionProgress } from '@/api/ingestion'
import type { KnowledgeBase, KnowledgeCategory } from '@/api/knowledge'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import InstrumentCard, { type InstrumentRow, type InstrumentStatus } from '@/components/InstrumentCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import CreateWizard from './components/CreateWizard.vue'
import DocumentPanel from './components/DocumentPanel.vue'
import SegmentPanel from './components/SegmentPanel.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import HitTestPanel from './components/HitTestPanel.vue'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const canCreate = computed(() => session.can(PERM.knowledgeCreate))

/**
 * 消费者形态（07 §五）：数据范围 `shared` = 只用别人做好的。
 *
 * 判据只有 `session.dataScope` 一处。对消费者，知识库是**只读引用面**：
 * 没有创建/删除/启停，没有详情壳（文档与分段对他不可见，07 §4.2），
 * 也不出现「分段 / 向量化 / 上传」这类建设语汇（07 §五 判据 1）。
 *
 * ★ 这只是 UX —— 后端按 `dataScope` 过滤列表与写操作前置校验才是边界（07 §3.3）。
 */
const consumer = computed(() => session.dataScope === 'shared')

const TABS = [
  { key: 'documents', label: '文档' },
  { key: 'segments', label: '分段' },
  { key: 'hittest', label: '命中测试' },
  { key: 'settings', label: '设置' }
] as const

type TabKey = (typeof TABS)[number]['key']

const list = ref<KnowledgeBase[]>([])
const categories = ref<KnowledgeCategory[]>([])
const total = ref(0)
const loading = ref(false)
const pageSize = 12
const currentKb = ref<KnowledgeBase | null>(null)
const segmentDocId = ref<string | undefined>(undefined)

/**
 * 详情壳（文档 / 分段 / 命中测试 / 设置）里那一个知识库 —— 只有「建设者形态 + 已选中」时非空。
 *
 * 消费者即便被直接投喂一个 `?kb=xxx` 深链也只看到列表 —— 否则「入口藏了、URL 还进得去」
 * 会让他看到满屏建设语汇（07 §五 判据 1），而文档与分段本来就对他不可见（07 §4.2）。
 * 这里挡的是**页面形态**；数据边界仍在后端。
 */
const openedKb = computed(() => (consumer.value ? null : currentKb.value))

const query = reactive({
  keyword: '',
  category: '',
  page: 1
})

const activeTab = computed<TabKey>(() => {
  const t = String(route.query.tab ?? 'documents')
  return (TABS.some((x) => x.key === t) ? t : 'documents') as TabKey
})

const kbId = computed(() => (route.query.kb ? String(route.query.kb) : ''))

const formatChars = (n: number) => {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  return String(n)
}

const fetchList = async () => {
  loading.value = true
  try {
    const result = await knowledgeApi.listKnowledgeBases({
      keyword: query.keyword || undefined,
      category: query.category || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
    categories.value = await knowledgeApi.listKnowledgeCategories()
  } finally {
    loading.value = false
  }
}

const fetchDetail = async (id: string) => {
  currentKb.value = await knowledgeApi.getKnowledgeBase(id)
}

/* ────────────── 入库流水线读数（仪器面板 · 签名 4 + 8） ────────────── */

/**
 * 进度取自 `ingestionApi.getIngestionProgress`（服务端真实进度），**不是**前端自己数。
 *
 * 它放在页面壳上而不是 DocumentPanel 里：切到「分段 / 命中测试 / 设置」时读数不能消失。
 * 代价：文档 Tab 激活期间，本页与 DocumentPanel 会各自轮询同一个接口（各 1.5 s 一次）。
 * 这是刻意取舍 —— 要省掉那一次，就得让页面反过来从子组件里"讨"数据
 * （上提状态或新增 emit），那等于让页面级读数依赖子组件挂不挂载，耦合更坏。
 */
const ingestProgress = ref<IngestionProgress | null>(null)

/** 进行中的三个阶段。判据与 DocumentPanel 的 `isRunning` 一致：
 *  「待向量化」是排队等用户点「开始向量化」，不算机器正在做。 */
const RUNNING_STAGES: DocumentStatus[] = ['待解析', '分段中', '向量化中']

const fetchProgress = async () => {
  const id = currentKb.value?.id
  if (!id) return
  ingestProgress.value = await ingestionApi.getIngestionProgress(id)
}

/* 轮询：与 DocumentPanel 同一套纪律 —— 只在 running 时轮询、页面不可见即暂停、
   回到前台立即补一次、全部完成即停。唯一加固：一次请求失败就停表，
   保留上一次的真实读数（失败时继续每 1.5 s 打同一枪，只是把错误刷屏）。 */
/**
 * 入库面板**可见吗** —— 模板的 `v-if` 与轮询的启停共用这一个判据。
 *
 * 两条不渲染条件都不是"保守"，是 `design.md` § 仪器面板 自己写的判据：
 *
 *  ① **空库（`total === 0`）不渲染。** 判据原文是「去掉它，页面会失去一个读数；
 *     若去掉后什么都没少，它就不该在」—— 一个 0 / 0 / 0 / 空闲 的回执，
 *     去掉之后页面什么都不少。（这条是实现后自验时发现的，不是一开始就想清楚。）
 *  ② **文档 Tab 不渲染。** `DocumentPanel` 自带 `GlobalProgress`，已经承载同一批读数；
 *     再放一个是"同屏两个读数器"，违反 § 仪器面板 第一条「**每视口一个**」。
 *     面板的独占价值在分段 / 命中测试 / 设置三个 Tab。
 *
 * ★ 轮询必须跟着**同一个判据**停 —— 否则会出现"每 1.5 s 打一枪，但读数没人看得见"。
 */
const panelVisible = computed(
  () =>
    !!ingestProgress.value &&
    ingestProgress.value.total > 0 &&
    activeTab.value !== 'documents'
)

const INGEST_POLL_MS = 1500
let timer: ReturnType<typeof setTimeout> | null = null

const clearTimer = () => {
  if (timer) clearTimeout(timer)
  timer = null
}

const schedule = () => {
  clearTimer()
  if (!panelVisible.value || !ingestProgress.value?.running || document.hidden) return
  timer = setTimeout(async () => {
    try {
      await fetchProgress()
    } catch {
      clearTimer()
      return
    }
    schedule()
  }, INGEST_POLL_MS)
}

const onVisibility = () => {
  if (document.hidden) {
    clearTimer()
    return
  }
  // 回到前台立即补一次，不等下一个周期
  fetchProgress().then(schedule, clearTimer)
}

/**
 * 读数行。还没测到时返回空数组 —— 组件就什么都不渲染。
 * 「失败」在无失败时显示**真实值 0**，不显示「—」：那是测到的读数，不是缺数据。
 */
const ingestRows = computed<InstrumentRow[]>(() => {
  const p = ingestProgress.value
  if (!p) return []
  const stages = p.stages
    .filter((s) => s.count > 0 && RUNNING_STAGES.includes(s.status))
    .map((s) => `${s.status} ${s.count}`)
  return [
    { label: '文档总数', value: p.total },
    { label: '已就绪', value: p.done, hint: '状态「成功」' },
    { label: '失败', value: p.failed, hint: '分段失败 + 向量化失败，可从断点重试' },
    {
      label: '当前阶段',
      value: stages.length ? stages.join(' · ') : '空闲',
      hint: p.running ? '每 1.5 s 自动刷新' : undefined
    }
  ]
})

/**
 * 状态胶囊**由真实读数推导**，不是装饰：服务端说有进行中任务 → RUNNING；
 * 有失败 → WARN；否则 OK。四态里没用到 ERROR —— 进度接口不通时 rows 为空，面板根本不渲染。
 */
const ingestStatus = computed<InstrumentStatus>(() => {
  if (ingestProgress.value?.running) return 'RUNNING'
  if ((ingestProgress.value?.failed ?? 0) > 0) return 'WARN'
  return 'OK'
})

const onFilterChange = () => {
  query.page = 1
  fetchList()
}

const setCategory = (name: string) => {
  query.category = name
  onFilterChange()
}

const openDetail = (id: string) => {
  router.push({ query: { ...route.query, kb: id, tab: 'documents' } })
}

const closeDetail = () => {
  currentKb.value = null
  segmentDocId.value = undefined
  router.push({ path: '/knowledge' })
  fetchList()
}

const setTab = (tab: TabKey) => {
  router.push({ query: { ...route.query, kb: kbId.value, tab } })
}

const gotoSegments = (documentId: string) => {
  segmentDocId.value = documentId
  setTab('segments')
}

const onKbChanged = async () => {
  if (kbId.value) await fetchDetail(kbId.value)
  await fetchList()
}

const wizardRef = ref<InstanceType<typeof CreateWizard>>()

const openCreate = () => wizardRef.value?.open()

const onCreated = async (kb: KnowledgeBase) => {
  ElMessage.success(`知识库「${kb.name}」已创建`)
  await fetchList()
  openDetail(kb.id)
}

const toggleEnabled = async (kb: KnowledgeBase) => {
  const next = !kb.enabled
  if (!next) {
    await ElMessageBox.confirm(
      `停用后「${kb.name}」不再参与检索。确认停用？`,
      '确认停用知识库',
      { type: 'warning', confirmButtonText: '停用' }
    )
  }
  await knowledgeApi.setKnowledgeBaseEnabled(kb.id, next)
  ElMessage.success(next ? '已启用' : '已停用')
  await fetchList()
}

const remove = async (kb: KnowledgeBase) => {
  await ElMessageBox.confirm(
    `删除「${kb.name}」将一并移除其全部文档与分段，且不可恢复。确认删除？`,
    '确认删除知识库',
    { type: 'warning', confirmButtonText: '删除' }
  )
  await knowledgeApi.deleteKnowledgeBase(kb.id)
  ElMessage.success('已删除')
  await fetchList()
}

// 路由 query 驱动列表/详情切换（消费者不切详情，见 `detailOpen`）
watch(
  () => kbId.value,
  (id) => {
    if (id && !consumer.value) fetchDetail(id)
    else {
      currentKb.value = null
      fetchList()
    }
  },
  { immediate: true }
)

/*
  切 Tab 要重新决定"起不起表"：
  离开文档 Tab → 面板出现 → 若仍在入库则立即接着轮询（不等下一个周期）；
  进入文档 Tab → 面板消失 → `schedule()` 自己会 clearTimer 并返回。
  没有这条 watch，轮询的启停就与面板的可见性脱钩了。
*/
watch(activeTab, () => schedule())

// 详情打开 / 切换知识库时重新读数并起轮询；关闭详情即清空，不留上一个库的数字
watch(
  () => currentKb.value?.id,
  (id) => {
    clearTimer()
    if (!id) {
      ingestProgress.value = null
      return
    }
    fetchProgress().then(schedule, clearTimer)
  }
)

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility)
  if (!kbId.value) fetchList()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  clearTimer()
})
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
  padding: var(--space-sm) var(--space-md);
}

.cats {
  display: flex;
  gap: var(--space-2xs);
  flex-wrap: wrap;
}

.cat {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  padding: var(--space-2xs) var(--space-sm);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
  font-family: inherit;
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  color: var(--color-ink-2);
  cursor: pointer;
  transition:
    border-color var(--dur-micro) var(--ease-out),
    background var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.cat:hover {
  background: var(--color-hover);
  border-color: var(--color-rule-strong);
}

.cat.on {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-weight: 500;
}

.cat-count {
  opacity: 0.75;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-sm);
  min-height: 140px;
}

.kb-card {
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition:
    border-color var(--dur-micro) var(--ease-out),
    box-shadow var(--dur-micro) var(--ease-out),
    opacity var(--dur-micro) var(--ease-out);
}

.kb-card:hover {
  border-color: var(--color-accent-line);
  box-shadow: var(--shadow-whisper);
}

.kb-card.off {
  opacity: 0.6;
}

/* 消费者形态：卡片是「看一眼」，不是入口 —— 指针不该变手型许诺一个进不去的详情 */
.kb-card.readonly {
  cursor: default;
}

.kb-card.readonly:hover {
  border-color: var(--color-rule);
  box-shadow: none;
}

.kb-card :deep(.el-card__body) {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.kc-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
}

/* emoji 是排版元素，不是装饰品 —— 与正文同量级，不套色块 */
.kc-icon {
  font-size: var(--text-md);
  line-height: var(--leading-tight);
  flex-shrink: 0;
}

.kc-icon-img {
  width: var(--text-md);
  height: var(--text-md);
  object-fit: contain;
  flex-shrink: 0;
  border-radius: var(--radius-xs);
  vertical-align: -2px;
}

.kc-main {
  min-width: 0;
  flex: 1;
}

.kc-name {
  font-size: var(--text-md);
  font-weight: 500;
  line-height: var(--leading-tight);
  color: var(--color-ink);
}

.kc-desc {
  margin-top: var(--space-3xs);
  line-height: var(--leading-tight);
}

.kc-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  line-height: var(--leading-tight);
}

.kc-meta .num {
  font-size: var(--text-2xs);
  font-weight: 500;
  color: var(--color-ink-2);
}

.kc-foot {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  padding-top: var(--space-xs);
  border-top: 1px solid var(--color-rule-2);
}

.empty {
  grid-column: 1 / -1;
}

.pager {
  display: flex;
  justify-content: flex-end;
}

/* ── 详情壳 ── */
.detail {
  display: grid;
  grid-template-columns: 132px 1fr;
  gap: var(--space-md);
  align-items: start;
}

/* 仪器面板横跨「左菜单 + 内容」两列 —— 它是全页读数，不是内容列的附属件 */
.detail-instrument {
  grid-column: 1 / -1;
}

.side {
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  padding: var(--space-xs);
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
  position: sticky;
  top: var(--space-sm);
}

.side-item {
  padding: var(--space-xs) var(--space-sm);
  border: none;
  border-radius: var(--radius);
  background: transparent;
  font-family: inherit;
  font-size: var(--text-xs);
  line-height: var(--leading-tight);
  color: var(--color-ink-2);
  cursor: pointer;
  text-align: left;
  transition:
    background var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.side-item:hover {
  background: var(--color-hover);
}

.side-item.on {
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-weight: 500;
  box-shadow: inset 2px 0 0 var(--color-accent);
}

.content {
  min-width: 0;
}
</style>
