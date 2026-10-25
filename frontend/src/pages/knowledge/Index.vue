<template>
  <div class="page">
    <!-- ── 列表态 ──
         消费者形态（07 §五）：只有卡片，没有详情壳 —— 文档与分段对他不可见（07 §4.2），
         所以「点进去看」这一层根本不该存在，而不是点进去之后少几块。 -->
    <template v-if="!openedKb && detailState !== 'loading'">
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

      <!-- 深链失败提示：?kb=不存在 时回落列表，不许停在空白 -->
      <div v-if="deepLinkError" class="notice warn">
        <div><b>{{ deepLinkError }}</b></div>
        <div class="micro muted">已回到知识库列表。</div>
      </div>

      <!-- 深链带了 doc/segment 却没有 kb：连库都定位不了，也要说出来（同族的静默 no-op） -->
      <div v-if="deepLinkNoKb" class="notice warn">
        <div><b>这条引用没有指向任何知识库，无法定位到分段。</b></div>
        <div class="micro muted">链接里缺少知识库参数（会话附件没有知识库行，属正常现象）。</div>
      </div>

      <!-- 错误态：与空态严格区分，不许把"加载失败"渲染成"还没有知识库" -->
      <div v-if="listError" class="empty panel error-state">
        <div class="empty-title">知识库列表加载失败</div>
        <p class="micro muted">{{ listError.reason }}</p>
        <p v-if="listError.hint" class="micro muted">{{ listError.hint }}</p>
        <el-button size="small" type="primary" @click="fetchList">重试</el-button>
      </div>

      <template v-else>
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
              <el-button v-if="canManage" link size="small" type="primary" @click.stop="openShare(kb)">共享</el-button>
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
    </template>

    <!-- ── 详情加载中：不许闪回列表 ── -->
    <template v-else-if="detailState === 'loading'">
      <PageHeader title="加载中…" desc="正在读取知识库详情">
        <template #actions>
          <el-button size="small" @click="closeDetail">返回列表</el-button>
        </template>
      </PageHeader>
      <div v-loading="true" class="detail-loading" />
    </template>

    <!-- ── 详情不存在 / 无权 / 出错 ── -->
    <template v-else-if="detailState === 'notFound' || detailState === 'error'">
      <PageHeader
        :title="detailState === 'notFound' ? '知识库不存在' : '知识库加载失败'"
        :desc="detailError || '该知识库可能已被删除，或你没有访问权限'"
      >
        <template #actions>
          <el-button size="small" type="primary" @click="closeDetail">返回列表</el-button>
          <el-button v-if="detailState === 'error'" size="small" @click="retryDetail">重试</el-button>
        </template>
      </PageHeader>
      <div class="empty panel error-state">
        <div class="empty-title">
          {{ detailState === 'notFound' ? '找不到这个知识库' : '加载出错了' }}
        </div>
        <p class="micro muted">{{ detailError || '它可能已被删除，或者你没有访问权限。' }}</p>
        <el-button size="small" type="primary" @click="closeDetail">回到知识库列表</el-button>
      </div>
    </template>

    <!-- ── 详情壳：左菜单 + 内容 ── -->
    <template v-else-if="openedKb">
      <PageHeader :title="openedKb.name" :desc="openedKb.description">
        <template #after-title>
          <el-tag v-if="!openedKb.enabled" size="small" type="info" effect="plain">已停用</el-tag>
        </template>
        <template #actions>
          <el-button size="small" @click="closeDetail">返回列表</el-button>
        </template>
      </PageHeader>

      <!--
        引用深链的**接收端**：会话页「跳转到分段管理」发过来的就是这三个键
        （`jumpToSegment` 逐字拼 `doc` / `segment` / `kb`）。没有这一块，
        参数照样进得来、却没有人读它 —— 用户点了没反应，且没有任何提示。
        所以这里两件事都要做：**读**它，读不到时**说出来**（不许静默回落）。
      -->
      <div v-if="segmentLinkState === 'loading'" class="notice info">
        <div><b>正在定位引用分段…</b></div>
      </div>
      <div v-else-if="segmentLinkState === 'error'" class="notice warn">
        <div><b>{{ segmentLinkError }}</b></div>
        <div class="micro muted">已切到分段视图；下面的列表按文档筛选。该分段可能已被删除，或你没有访问权限。</div>
      </div>
      <div v-else-if="targetSegment" class="notice info">
        <div>
          已按引用定位到分段：<b>{{ targetSegment.title || '（无标题）' }}</b>
          <span class="micro muted"> · {{ targetSegment.charCount }} 字</span>
        </div>
        <p class="micro muted link-preview">{{ targetSegment.content }}</p>
      </div>

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
            :highlight-segment-id="targetSegment?.id"
          />
          <QuestionPanel
            v-else-if="activeTab === 'questions'"
            :kb-id="openedKb.id"
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
            @dirty-change="onSettingsDirty"
          />
        </main>
      </div>
    </template>

    <CreateWizard ref="wizardRef" @created="onCreated" />
    <!-- 共享写：契约与后端都在（PUT /knowledge-bases/{id}/grants），此前页面零调用 -->
    <ShareDialog ref="shareRef" :kb="shareKb" @saved="onKbChanged" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { conversationsApi, ingestionApi, knowledgeApi } from '@/api'
import { ApiError, ErrorCode } from '@/api/types'
import type { SegmentBrief } from '@/api/conversations'
import type { DocumentStatus, IngestionProgress } from '@/api/ingestion'
import type { KnowledgeBase, KnowledgeCategory } from '@/api/knowledge'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import InstrumentCard, { type InstrumentRow, type InstrumentStatus } from '@/components/InstrumentCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import CreateWizard from './components/CreateWizard.vue'
import ShareDialog from './components/ShareDialog.vue'
import DocumentPanel from './components/DocumentPanel.vue'
import SegmentPanel from './components/SegmentPanel.vue'
import QuestionPanel from './components/QuestionPanel.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import HitTestPanel from './components/HitTestPanel.vue'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const canCreate = computed(() => session.can(PERM.knowledgeCreate))
/** 共享写走 `knowledge:manage`（与后端 PUT /grants 的权限点同口径） */
const canManage = computed(() => session.can(PERM.knowledgeManage))

/**
 * 消费者形态（07 §五）：数据范围 `shared` = 只用别人做好的。
 *
 * 判据只有 `session.dataScope` 一处。对消费者，知识库是**只读引用面**：
 * 没有创建/删除/启停，没有详情壳（文档与分段对他不可见，07 §4.2），
 * 也不出现「分段 / 向量化 / 上传」这类建设语汇（07 §五 判据 1）。
 *
 * ★ 这只是 UX —— 后端按 `dataScope` 过滤列表与写操作前置校验才是边界（07 §3.3）。
 */
const consumer = computed(() => session.dataScope === 'SHARED')

const TABS = [
  { key: 'documents', label: '文档' },
  { key: 'segments', label: '分段' },
  { key: 'questions', label: '问题' },
  { key: 'hittest', label: '命中测试' },
  { key: 'settings', label: '设置' }
] as const

type TabKey = (typeof TABS)[number]['key']

const list = ref<KnowledgeBase[]>([])
const categories = ref<KnowledgeCategory[]>([])
const total = ref(0)
const loading = ref(false)
/** P1/#13 错误态：与空态严格区分。有它就不渲染"还没有知识库" */
const listError = ref<{ reason: string; hint?: string } | null>(null)
/** 深链 `?kb=不存在` 回落时的可读提示 */
const deepLinkError = ref('')
const pageSize = 12
const currentKb = ref<KnowledgeBase | null>(null)
const segmentDocId = ref<string | undefined>(undefined)

/**
 * 引用深链的目标分段（引用回溯）。
 *
 * 契约里那条接口是 `conversationsApi.getSegment(id)`（`GET /segments/:id`，返回 `SegmentBrief`）——
 * 它就是为「跳转分段管理 / 抽屉里补全文」写的，所以这里**不新增接口、不新增字段**。
 * `SegmentBrief.documentId` 很有用：`?segment=` 有而 `?doc=` 缺（或指错）时，
 * 只有它能告诉我们这条分段属于哪篇文档。
 */
const targetSegment = ref<SegmentBrief | null>(null)
/** 深链定位的状态机：idle 没带深链 · loading 正在取 · ready 取到了 · error 取不到（必须说出来） */
const segmentLinkState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const segmentLinkError = ref('')

/**
 * #14 详情壳状态机：
 *  - idle    未打开（走列表模板）
 *  - loading 深链/点开后读取中 —— 不许闪回列表
 *  - ready   已打开
 *  - notFound 库不存在 / 无权（404）
 *  - error   其它加载错误
 */
const detailState = ref<'idle' | 'loading' | 'ready' | 'notFound' | 'error'>('idle')
const detailError = ref('')

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

/**
 * 会话页「跳转到分段管理」发来的两个键。
 *
 * ★ 键名是**逐字**对着发送端核过的，不是猜的：
 *   `pages/conversation/Index.vue` 与 `pages/conversation/public/Index.vue`
 *   两处 `jumpToSegment` 拼的都是 `doc` / `segment` / `kb`（`kb` 由下面的 `kbId` 消费）。
 *   改这里的任何一个字母都会让跳转退回「静默 no-op」——
 *   `frontend/check_deeplink_segments.py` 钉的就是这条一致性。
 */
const deepLinkDocId = computed(() => (route.query.doc ? String(route.query.doc) : ''))
const deepLinkSegmentId = computed(() => (route.query.segment ? String(route.query.segment) : ''))

const kbId = computed(() => (route.query.kb ? String(route.query.kb) : ''))

/**
 * 深链带了 `doc` / `segment` 却**没有** `kb`：定位不到任何知识库，只能停在列表。
 * 那就必须说出来 —— 这正是本页原来的病（参数进得来、没人读、界面一声不响）。
 * 消费者形态除外：他本来就只有列表态（07 §五），不该看到建设语汇的报错。
 */
const deepLinkNoKb = computed(
  () => !consumer.value && !kbId.value && !!(deepLinkDocId.value || deepLinkSegmentId.value)
)

const activeTab = computed<TabKey>(() => {
  const t = String(route.query.tab ?? '')
  if (TABS.some((x) => x.key === t)) return t as TabKey
  // 深链带了 `doc` / `segment` 却停在「文档」页，等于跳到了、但看不到目标 ——
  // 发送端的意图就是「分段管理」，所以缺 `tab` 时按深链参数落到分段视图。
  if (deepLinkDocId.value || deepLinkSegmentId.value) return 'segments'
  return 'documents'
})

const formatChars = (n: number) => {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  return String(n)
}

const errText = (error: unknown, fallback: string): { reason: string; hint?: string } => {
  if (error instanceof ApiError) return { reason: error.message, hint: error.hint }
  if (error instanceof Error) return { reason: error.message || fallback }
  return { reason: fallback }
}

/** P1 筛选入 URL：刷新与直链能恢复同一筛选态 */
const syncQueryToUrl = () => {
  const next: Record<string, string> = {}
  for (const [k, v] of Object.entries(route.query)) {
    if (v != null && typeof v === 'string') next[k] = v
  }
  if (query.keyword) next.keyword = query.keyword
  else delete next.keyword
  if (query.category) next.category = query.category
  else delete next.category
  if (query.page > 1) next.page = String(query.page)
  else delete next.page
  router.replace({ query: next })
}

const readQueryFromUrl = () => {
  query.keyword = route.query.keyword ? String(route.query.keyword) : ''
  query.category = route.query.category ? String(route.query.category) : ''
  query.page = route.query.page ? Number(route.query.page) || 1 : 1
}

const fetchList = async () => {
  loading.value = true
  listError.value = null
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
  } catch (error) {
    // #13 错误伪装成空态是本条 P0 的根因 —— 失败就走错误面板，不许清 list 冒充"还没有"
    listError.value = errText(error, '加载知识库列表失败')
  } finally {
    loading.value = false
  }
}

const fetchDetail = async (id: string) => {
  detailState.value = 'loading'
  detailError.value = ''
  try {
    currentKb.value = await knowledgeApi.getKnowledgeBase(id)
    detailState.value = 'ready'
  } catch (error) {
    const isNotFound = error instanceof ApiError && (error.code === 404 || error.code === ErrorCode.NOT_FOUND)
    detailState.value = isNotFound ? 'notFound' : 'error'
    detailError.value = errText(error, isNotFound ? '该知识库不存在或你没有访问权限' : '加载知识库详情失败').reason
    currentKb.value = null
    // #13 深链失败后必须回到列表，不许停在空白
    deepLinkError.value = isNotFound
      ? `找不到知识库「${id}」——它可能已被删除，或你没有访问权限。`
      : `知识库「${id}」加载失败：${detailError.value}`
    detailState.value = isNotFound ? 'notFound' : 'error'
    // 列表也拉一次，让用户看到能进的库
    await fetchList()
    // 5 秒后自动回落列表模板（保留提示），避免卡在错误页
    // 立即回落：错误页上已有「回到列表」按钮，这里直接展示列表 + 顶部提示
    setTimeout(() => {
      if (detailState.value === 'notFound' || detailState.value === 'error') {
        detailState.value = 'idle'
        router.replace({ query: { ...route.query, kb: undefined } })
      }
    }, 4000)
  }
}

const retryDetail = () => {
  if (kbId.value) fetchDetail(kbId.value)
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
const RUNNING_STAGES: DocumentStatus[] = ['PENDING_PARSE', 'SEGMENTING', 'EMBEDDING']

const fetchProgress = async () => {
  const id = currentKb.value?.id
  if (!id) return
  ingestProgress.value = await ingestionApi.getIngestionProgress(id)
}

/* 轮询：与 DocumentPanel 同一套纪律 —— 只在 running 时轮询、页面不可见即暂停、
   回到前台立即补一次、全部完成即停。加固两件：一次请求失败就停表；
   **上限 + 退避** —— 后端卡在 running 时不无限打枪。 */
const INGEST_POLL_BASE_MS = 1500
const INGEST_POLL_MAX_MS = 12_000
const INGEST_POLL_MAX_ATTEMPTS = 48
let timer: ReturnType<typeof setTimeout> | null = null
let pollAttempts = 0

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

const clearTimer = () => {
  if (timer) clearTimeout(timer)
  timer = null
}

const nextDelay = () =>
  Math.min(
    INGEST_POLL_MAX_MS,
    Math.round(INGEST_POLL_BASE_MS * Math.pow(1.25, Math.max(0, pollAttempts - 1)))
  )

const schedule = () => {
  clearTimer()
  if (!panelVisible.value || !ingestProgress.value?.running || document.hidden) {
    pollAttempts = 0
    return
  }
  if (pollAttempts >= INGEST_POLL_MAX_ATTEMPTS) {
    // 到上限：停表，保留上一次真实读数。用户可手动刷新
    return
  }
  pollAttempts += 1
  timer = setTimeout(async () => {
    try {
      await fetchProgress()
    } catch {
      clearTimer()
      pollAttempts = 0
      return
    }
    schedule()
  }, nextDelay())
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
  syncQueryToUrl()
  fetchList()
}

const setCategory = (name: string) => {
  query.category = name
  onFilterChange()
}

const openDetail = (id: string) => {
  // ★ 引用深链的 `doc` / `segment` 不跟着「从列表点进另一个库」走：
  //   那两个 id 属于上一个库，带过去会让新库被上一个库的深链定位
  //   （`?doc=X` 的 X 根本不属于这个库 ⇒ 分段列表筛成空）。
  const next: Record<string, string> = {}
  for (const [k, v] of Object.entries(route.query)) {
    if (k === 'doc' || k === 'segment') continue
    if (v != null && typeof v === 'string') next[k] = v
  }
  router.push({ query: { ...next, kb: id, tab: 'documents' } })
}

const closeDetail = () => {
  currentKb.value = null
  segmentDocId.value = undefined
  targetSegment.value = null
  segmentLinkState.value = 'idle'
  segmentLinkError.value = ''
  detailState.value = 'idle'
  detailError.value = ''
  deepLinkError.value = ''
  router.push({ path: '/knowledge', query: { keyword: query.keyword || undefined, category: query.category || undefined } })
  fetchList()
}

const setTab = async (tab: TabKey) => {
  // P1 设置面板改动静默丢失：切走前确认
  if (activeTab.value === 'settings' && tab !== 'settings' && settingsDirty.value) {
    try {
      await ElMessageBox.confirm('设置面板有未保存的改动，切换后会丢失。确认离开？', '未保存的改动', {
        type: 'warning',
        confirmButtonText: '放弃改动并离开',
        cancelButtonText: '留在设置'
      })
    } catch {
      return
    }
    settingsDirty.value = false
  }
  router.push({ query: { ...route.query, kb: kbId.value, tab } })
}

const gotoSegments = (documentId: string) => {
  segmentDocId.value = documentId
  setTab('segments')
}

/**
 * 消费引用深链（本页原来完全没有这一半）。
 *
 * 处置三档，任何一档都不许停在「点了没反应」：
 *  ① 只有 `?doc=`  → 切分段视图 + 按该文档筛选（不发多余请求）
 *  ② 有 `?segment=` → 用契约的 `getSegment` 把目标分段**取回来展示**；
 *     文档筛选以 `SegmentBrief.documentId` 为准（`?doc=` 缺失/指错时它就是唯一的答案）
 *  ③ 取不到（404 / 无权 / 网络错）→ 画一条可读提示，仍留在分段视图，不静默回落列表
 */
const applyDeepLink = async () => {
  const segId = deepLinkSegmentId.value
  const docId = deepLinkDocId.value

  // 与 `?kb=` 同口径：消费者没有详情壳，深链也不给他开（07 §五）
  if (!openedKb.value || (!segId && !docId)) {
    targetSegment.value = null
    segmentLinkState.value = 'idle'
    segmentLinkError.value = ''
    return
  }

  segmentDocId.value = docId || undefined
  if (!segId) {
    targetSegment.value = null
    segmentLinkState.value = 'idle'
    segmentLinkError.value = ''
    return
  }
  if (targetSegment.value?.id === segId) {
    segmentLinkState.value = 'ready'
    return
  }

  segmentLinkState.value = 'loading'
  segmentLinkError.value = ''
  try {
    const seg = await conversationsApi.getSegment(segId)
    targetSegment.value = seg
    // 分段自己的归属优先 —— `?doc=` 只是发送端顺手带的，可能缺、可能是旧的
    segmentDocId.value = seg.documentId
    segmentLinkState.value = 'ready'
  } catch (error) {
    targetSegment.value = null
    segmentLinkError.value = errText(error, '加载引用分段失败').reason
    segmentLinkState.value = 'error'
  }
}

/*
  深链消费的触发点：详情壳就绪（`openedKb` 由空变有）或 URL 上的深链参数变了。
  必须 watch `openedKb` 而不是 `onMounted` —— `?segment=` 到场时分段接口还调不了，
  得等 `fetchDetail` 把库读出来；而深链**同库内**再来一次（kb 不变）只有参数变化能触发。
*/
watch(
  () => [openedKb.value?.id ?? '', deepLinkDocId.value, deepLinkSegmentId.value] as const,
  () => applyDeepLink(),
  { immediate: true }
)

const onKbChanged = async () => {
  if (kbId.value) await fetchDetail(kbId.value)
  await fetchList()
}

const wizardRef = ref<InstanceType<typeof CreateWizard>>()
const shareRef = ref<InstanceType<typeof ShareDialog>>()
const shareKb = ref<KnowledgeBase | null>(null)

/** 打开共享：先把当前库放进 props，再 open —— 列表行是最新快照 */
const openShare = (kb: KnowledgeBase) => {
  shareKb.value = kb
  shareRef.value?.open(kb)
}

// ★ 把当前筛选的分类带进向导：用户已经用筛选表达过"我要建这一类"，再问一次是白问一遍。
//   （空列表的「创建第一个知识库」就是这个意思 —— 那个按钮只在"当前筛选下没有库"时出现。）
const openCreate = () => wizardRef.value?.open(query.category || '')

const onCreated = async (kb: KnowledgeBase) => {
  ElMessage.success(`知识库「${kb.name}」已创建`)
  await fetchList()
  openDetail(kb.id)
}

const toggleEnabled = async (kb: KnowledgeBase) => {
  const next = !kb.enabled
  if (!next) {
    try {
      await ElMessageBox.confirm(
        `停用后「${kb.name}」不再参与检索。确认停用？`,
        '确认停用知识库',
        { type: 'warning', confirmButtonText: '停用' }
      )
    } catch {
      return
    }
  }
  try {
    await knowledgeApi.setKnowledgeBaseEnabled(kb.id, next)
    ElMessage.success(next ? '已启用' : '已停用')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '操作失败').reason)
    await fetchList()
  }
}

const remove = async (kb: KnowledgeBase) => {
  try {
    await ElMessageBox.confirm(
      `删除「${kb.name}」将一并移除其全部文档与分段，且不可恢复。确认删除？`,
      '确认删除知识库',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch {
    return
  }
  try {
    await knowledgeApi.deleteKnowledgeBase(kb.id)
    ElMessage.success('已删除')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '删除失败').reason)
    await fetchList()
  }
}

/** P1 设置面板脏标记 */
const settingsDirty = ref(false)
const onSettingsDirty = (dirty: boolean) => {
  settingsDirty.value = dirty
}

// 路由 query 驱动列表/详情切换（消费者不切详情，见 `detailOpen`）
watch(
  () => kbId.value,
  (id) => {
    if (id && !consumer.value) {
      fetchDetail(id)
    } else {
      currentKb.value = null
      detailState.value = 'idle'
      detailError.value = ''
      fetchList()
    }
  },
  { immediate: true }
)

// 筛选入 URL：路由里的 keyword/category/page 变化时同步本地
watch(
  () => [route.query.keyword, route.query.category, route.query.page],
  () => {
    if (kbId.value) return
    const kw = route.query.keyword ? String(route.query.keyword) : ''
    const cat = route.query.category ? String(route.query.category) : ''
    const pg = route.query.page ? Number(route.query.page) || 1 : 1
    if (kw !== query.keyword || cat !== query.category || pg !== query.page) {
      query.keyword = kw
      query.category = cat
      query.page = pg
      fetchList()
    }
  }
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
    pollAttempts = 0
    if (!id) {
      ingestProgress.value = null
      return
    }
    fetchProgress().then(schedule, clearTimer)
  }
)

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility)
  readQueryFromUrl()
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

.error-state {
  border-color: color-mix(in oklch, var(--color-danger) 30%, var(--color-rule));
}

.detail-loading {
  min-height: 240px;
}

/* 引用定位提示里那段正文：三行封顶，详情在下面的分段列表里看 */
.link-preview {
  margin: var(--space-2xs) 0 0;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-wrap;
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
