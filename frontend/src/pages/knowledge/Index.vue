<template>
  <div class="page">
    <!-- ── 列表态 ── -->
    <template v-if="!currentKb">
      <PageHeader
        title="知识库"
        desc="知识库是文档、分段与检索的容器。向量模型创建时选定，有向量化内容后锁定"
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
          :class="{ off: !kb.enabled }"
          @click="openDetail(kb.id)"
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
              :model-value="kb.enabled"
              size="small"
              @click.stop
              @change="toggleEnabled(kb)"
            />
          </div>

          <div class="kc-meta micro muted">
            <span><b class="num">{{ kb.documentCount }}</b> 文档</span>
            <el-divider direction="vertical" />
            <span><b class="num">{{ kb.segmentCount }}</b> 分段</span>
            <el-divider direction="vertical" />
            <span><b class="num">{{ formatChars(kb.charCount) }}</b> 字</span>
          </div>

          <div class="kc-foot">
            <el-tag size="small" effect="plain">{{ kb.category }}</el-tag>
            <el-tag v-if="kb.embeddingLocked" size="small" type="warning" effect="plain">向量已锁定</el-tag>
            <span class="grow" />
            <el-button link size="small" type="danger" @click.stop="remove(kb)">删除</el-button>
          </div>
        </el-card>

        <div v-if="!loading && !list.length" class="empty panel">
          <div class="empty-title">
            {{ query.keyword || query.category ? '当前筛选下没有知识库' : '还没有知识库' }}
          </div>
          <p class="micro muted">知识库是文档、分段与检索的容器</p>
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
      <PageHeader :title="currentKb.name" :desc="currentKb.description">
        <template #after-title>
          <el-tag v-if="!currentKb.enabled" size="small" type="info" effect="plain">已停用</el-tag>
        </template>
        <template #actions>
          <el-button size="small" @click="closeDetail">返回列表</el-button>
        </template>
      </PageHeader>

      <div class="detail">
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
            :kb-id="currentKb.id"
            @goto-segments="gotoSegments"
          />
          <SegmentPanel
            v-else-if="activeTab === 'segments'"
            :kb-id="currentKb.id"
            :initial-document-id="segmentDocId"
          />
          <HitTestPanel
            v-else-if="activeTab === 'hittest'"
            :kb-id="currentKb.id"
          />
          <SettingsPanel
            v-else-if="activeTab === 'settings'"
            :kb="currentKb"
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
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { knowledgeApi } from '@/api'
import type { KnowledgeBase, KnowledgeCategory } from '@/api/knowledge'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
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

// 路由 query 驱动列表/详情切换
watch(
  () => kbId.value,
  (id) => {
    if (id) fetchDetail(id)
    else {
      currentKb.value = null
      fetchList()
    }
  },
  { immediate: true }
)

onMounted(() => {
  if (!kbId.value) fetchList()
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
