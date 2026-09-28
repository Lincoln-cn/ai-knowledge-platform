<template>
  <div class="page">
    <PageHeader
      title="应用市场"
      desc="挑一个应用开始对话。点卡片看详情，收藏后下次更快找到"
    >
      <template #actions>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      </template>
    </PageHeader>

    <!--
      最近使用：从会话侧推（listConversations 已有 mine）。
      只列名字与跳转 —— 会话契约里有 appId / appName，不需要为一条窄带再造一个接口。
    -->
    <section v-if="recent.length" class="recent">
      <div class="recent-label label">最近使用</div>
      <div class="recent-row">
        <button
          v-for="r in recent"
          :key="r.appId"
          type="button"
          class="recent-chip"
          @click="startChat(r.appId)"
        >
          <span class="recent-name">{{ r.appName }}</span>
          <span class="recent-go micro">继续对话</span>
        </button>
      </div>
    </section>

    <div class="filters panel">
      <!-- 来源分段：全部 / 官方 / 共享给我 / 我的（与卡片徽标同一套判据） -->
      <el-radio-group v-model="query.origin" size="small" @change="onFilterChange">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="official">官方</el-radio-button>
        <el-radio-button value="shared">共享给我</el-radio-button>
        <el-radio-button value="mine">我的</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索应用名称或描述"
        style="width: 210px"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-select
        v-model="query.tag"
        size="small"
        placeholder="全部分类"
        style="width: 130px"
        clearable
        @change="onFilterChange"
      >
        <el-option v-for="t in tags" :key="t" :label="t" :value="t" />
      </el-select>
      <el-select
        v-model="query.sort"
        size="small"
        style="width: 110px"
        @change="onFilterChange"
      >
        <el-option label="人气" value="popular" />
        <el-option label="最新" value="latest" />
        <el-option label="名称" value="name" />
      </el-select>
      <el-checkbox
        v-model="query.favorite"
        size="small"
        @change="onFilterChange"
      >只看收藏</el-checkbox>
      <span class="micro muted">共 {{ total }} 个</span>
    </div>

    <!-- 错误态与空态严格区分 -->
    <div v-if="loadError" class="notice err load-error">
      <div><b>加载失败：</b>{{ loadError }}</div>
      <el-button size="small" @click="fetchList">重试</el-button>
    </div>

    <div v-else v-loading="loading" class="grid">
      <el-card
        v-for="a in list"
        :id="`market-card-${a.id}`"
        :key="a.id"
        shadow="never"
        class="app-card"
        :class="{ focused: highlightId === a.id }"
        @click="openDetail(a)"
      >
        <div class="ac-head">
          <span class="ac-icon">{{ a.icon }}</span>
          <div class="ac-title">
            <div class="ac-name-row">
              <span class="h3 ac-name">{{ a.name }}</span>
              <span class="ac-tags">
                <el-tag v-if="originOf(a) === 'OFFICIAL'" size="small" effect="plain" type="info">官方</el-tag>
                <el-tag v-else-if="originOf(a) === 'MINE'" size="small" effect="plain" type="success">我的</el-tag>
                <el-tag v-else size="small" effect="plain" type="warning">
                  由 {{ a.ownerName || '他人' }} 分享
                </el-tag>
              </span>
            </div>
            <div class="micro muted ellipsis-2 ac-desc" :title="a.description">{{ a.description }}</div>
          </div>
        </div>

        <div v-if="a.tags?.length" class="ac-taglist">
          <span v-for="t in a.tags" :key="t" class="tag-chip">{{ t }}</span>
        </div>

        <div class="ac-meta micro muted">
          <span class="num">{{ a.callCount.toLocaleString() }}</span> 次调用
          <span v-if="a.published" class="meta-dot">·</span>
          <span v-if="a.published" class="ok-text">已发布</span>
        </div>

        <div class="ac-actions" @click.stop>
          <el-button
            link
            size="small"
            :type="a.favorited ? 'warning' : undefined"
            @click="toggleFavorite(a)"
          >{{ a.favorited ? '已收藏' : '收藏' }}</el-button>
          <el-button link size="small" @click="openDetail(a)">详情</el-button>
          <span class="grow" />
          <el-button link size="small" type="primary" @click="startChat(a.id)">开始对话</el-button>
        </div>
      </el-card>

      <div v-if="!loading && !list.length" class="empty panel">
        <div class="empty-title">{{ emptyTitle }}</div>
        <p class="micro muted">{{ emptyHint }}</p>
      </div>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="onPageChange"
      />
    </div>

    <AppDetailDrawer
      ref="detailRef"
      :assistant="current"
      @start="startChat"
      @favorite="toggleFavorite"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { assistantsApi, conversationsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Assistant, AssistantOrigin } from '@/api/assistants'
import type { Conversation } from '@/api/conversations'
import { useSessionStore } from '@/stores/session'
import PageHeader from '@/components/PageHeader.vue'
import AppDetailDrawer from './components/AppDetailDrawer.vue'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()

const list = ref<Assistant[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const pageSize = 12
const current = ref<Assistant | null>(null)
const tags = ref<string[]>([])

/* ── 最近使用：从会话侧推（listConversations 已有 mine） ── */
const recent = ref<Array<{ appId: string; appName: string }>>([])

const fetchRecent = async () => {
  try {
    const result = await conversationsApi.listConversations({ mine: true, size: 20 })
    const seen = new Set<string>()
    const items: Array<{ appId: string; appName: string }> = []
    for (const c of result.list as Conversation[]) {
      if (c.appId && !seen.has(c.appId)) {
        seen.add(c.appId)
        items.push({ appId: c.appId, appName: c.appName })
      }
      if (items.length >= 5) break
    }
    recent.value = items
  } catch {
    // 最近使用是增强信息，失败不影响主列表
    recent.value = []
  }
}

/* ── 筛选/分页入 URL ── */

const readQueryFromUrl = () => {
  const q = route.query
  return {
    keyword: typeof q.keyword === 'string' ? q.keyword : '',
    origin: (typeof q.origin === 'string' ? q.origin : '') as '' | AssistantOrigin,
    tag: typeof q.tag === 'string' ? q.tag : '',
    sort: (typeof q.sort === 'string' ? q.sort : 'popular') as 'POPULAR' | 'LATEST' | 'NAME',
    favorite: q.favorite === 'true',
    page: Number(q.page) > 0 ? Number(q.page) : 1
  }
}

const query = reactive(readQueryFromUrl())

const syncQueryToUrl = () => {
  router.replace({
    query: {
      ...(query.keyword ? { keyword: query.keyword } : {}),
      ...(query.origin ? { origin: query.origin } : {}),
      ...(query.tag ? { tag: query.tag } : {}),
      ...(query.sort !== 'POPULAR' ? { sort: query.sort } : {}),
      ...(query.favorite ? { favorite: 'true' } : {}),
      ...(query.page > 1 ? { page: String(query.page) } : {})
    }
  })
}

const hasFilter = computed(() =>
  Boolean(query.keyword || query.origin || query.tag || query.favorite)
)

/* ── 来源三态（与卡片徽标同一套判据） ── */

const meId = computed(() => session.user?.id ?? '')

const originOf = (a: Assistant): AssistantOrigin => {
  if (a.builtin) return 'OFFICIAL'
  return a.ownerId && a.ownerId === meId.value ? 'MINE' : 'SHARED'
}

const emptyTitle = computed(() => {
  if (hasFilter.value) return '当前筛选下没有应用'
  return '暂时没有可用的应用'
})

const emptyHint = computed(() => {
  if (hasFilter.value) return '换个关键词或清空筛选试试'
  return '已经发布的应用会出现在这里'
})

const detailRef = ref<InstanceType<typeof AppDetailDrawer>>()

const onFilterChange = () => {
  query.page = 1
  syncQueryToUrl()
  fetchList()
}

const onPageChange = () => {
  syncQueryToUrl()
  fetchList()
}

// URL → 筛选/分页：浏览器前进/后退、页内改 hash 时界面跟随。
// 值比较后再赋值，避免与 syncQueryToUrl 互写成环；只在真变了时才拉列表。
// （`?focus=` 是一次性滚动定位，不是可同步的筛选态，不在此列。）
watch(
  () => [
    route.query.keyword,
    route.query.origin,
    route.query.tag,
    route.query.sort,
    route.query.favorite,
    route.query.page
  ],
  () => {
    const next = readQueryFromUrl()
    if (
      next.keyword === query.keyword &&
      next.origin === query.origin &&
      next.tag === query.tag &&
      next.sort === query.sort &&
      next.favorite === query.favorite &&
      next.page === query.page
    ) {
      return
    }
    Object.assign(query, next)
    fetchList()
  }
)

const fetchList = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const result = await assistantsApi.listAssistants({
      keyword: query.keyword || undefined,
      published: true,
      origin: query.origin || undefined,
      tag: query.tag || undefined,
      sort: query.sort,
      favorite: query.favorite || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    // 失败与「没有数据」严格区分
    list.value = []
    total.value = 0
    loadError.value =
      error instanceof ApiError
        ? `${error.message}${error.hint ? '。' + error.hint : ''}`
        : '应用列表加载失败，请检查网络后重试'
  } finally {
    loading.value = false
  }
}

const fetchTags = async () => {
  try {
    tags.value = await assistantsApi.listAssistantTags()
  } catch {
    tags.value = []
  }
}

const openDetail = (a: Assistant) => {
  current.value = a
  detailRef.value?.open(a)
}

const startChat = (appId: string) => {
  router.push({ path: '/conversation', query: { appId } })
}

/**
 * 收藏开关：乐观更新 + 失败回滚。
 * 可逆动作不做确认弹窗（design.md 微交互立场）；「可撤销」= 再点一次即取消。
 * 静默成功 —— 星标变化本身就是可见结果，不弹成功提示。
 */
const toggleFavorite = async (a: Assistant) => {
  const was = !!a.favorited
  a.favorited = !was
  // 同步 current（详情抽屉共用同一条数据）
  if (current.value?.id === a.id) current.value.favorited = !was
  try {
    if (was) {
      await assistantsApi.unfavoriteAssistant(a.id)
    } else {
      await assistantsApi.favoriteAssistant(a.id)
    }
    // 只看收藏时，取消收藏的项应从列表消失
    if (query.favorite && was) await fetchList()
  } catch (error) {
    a.favorited = was
    if (current.value?.id === a.id) current.value.favorited = was
    ElMessage.error(
      error instanceof ApiError
        ? `${error.message}${error.hint ? '。' + error.hint : ''}`
        : '操作失败，请重试'
    )
  }
}

/* ── ?focus= 定位（从对话页跳来） ── */
const focusId = ref('')
const highlightId = ref('')

const applyFocus = async () => {
  if (!focusId.value) return
  const id = focusId.value
  let found = list.value.some((a) => a.id === id)
  if (!found && hasFilter.value) {
    query.keyword = ''
    query.origin = ''
    query.tag = ''
    query.favorite = false
    query.page = 1
    syncQueryToUrl()
    await fetchList()
    found = list.value.some((a) => a.id === id)
  }
  if (!found) {
    ElMessage.info('目标应用不在当前列表中，可能已下架或你没有查看权限')
    return
  }
  highlightId.value = id
  document.getElementById(`market-card-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  window.setTimeout(() => {
    highlightId.value = ''
  }, 2600)
}

onMounted(async () => {
  const focus = route.query.focus
  focusId.value = typeof focus === 'string' ? focus : ''
  await Promise.all([fetchList(), fetchTags(), fetchRecent()])
  await applyFocus()
})
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  padding: var(--space-sm) var(--space-md);
}

/* ── 最近使用窄带 ── */
.recent {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xs) 0 var(--space-sm);
  border-bottom: 1px solid var(--color-rule-2);
  margin-bottom: var(--space-sm);
}

.recent-label {
  flex-shrink: 0;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
}

.recent-row {
  display: flex;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.recent-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-2xs) var(--space-sm);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  cursor: pointer;
  font-family: inherit;
  font-size: var(--text-xs);
  color: var(--color-ink-2);
  line-height: var(--leading-ui);
  transition:
    background var(--dur-micro) var(--ease-out),
    border-color var(--dur-micro) var(--ease-out);
}

.recent-chip:hover {
  background: var(--color-hover);
  border-color: var(--color-rule-strong);
}

.recent-chip:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 1px;
}

.recent-name {
  font-weight: 500;
  color: var(--color-ink);
}

.recent-go {
  color: var(--color-accent);
}

/* ── 卡片网格 ── */
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-sm);
  min-height: var(--space-2xl);
}

.app-card {
  border-radius: var(--radius-lg);
  display: flex;
  flex-direction: column;
  cursor: pointer;
  transition: border-color var(--dur-micro) var(--ease-out);
}

.app-card:hover {
  border-color: var(--color-rule-strong);
}

.app-card.focused {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
}

.app-card :deep(.el-card__body) {
  padding: var(--space-md) var(--space-lg);
  height: 100%;
  display: flex;
  flex-direction: column;
}

.ac-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-xs);
  flex: 1;
}

.ac-icon {
  font-size: var(--text-xl);
  line-height: 1;
  flex-shrink: 0;
}

.ac-title {
  flex: 1;
  min-width: 0;
}

.ac-name-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.ac-name {
  min-width: 0;
}

.ac-desc {
  margin-top: var(--space-3xs);
  line-height: var(--leading-tight);
  word-break: break-word;
}

.ac-tags {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  flex-shrink: 0;
}

.ac-taglist {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2xs);
  margin-top: var(--space-xs);
}

.tag-chip {
  font-size: var(--text-2xs);
  line-height: var(--leading-ui);
  padding: 0 var(--space-2xs);
  border: 1px solid var(--color-rule-2);
  border-radius: var(--radius-sm);
  color: var(--color-muted);
  background: var(--color-sunken);
}

.ac-meta {
  margin-top: var(--space-xs);
  line-height: var(--leading-ui);
}

.meta-dot {
  margin: 0 var(--space-2xs);
}

.ok-text {
  color: var(--color-ok);
}

.ac-actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
  padding-top: var(--space-xs);
  border-top: 1px solid var(--color-rule-2);
}

.grow {
  flex: 1;
}

.empty {
  grid-column: 1 / -1;
}

.load-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-xs);
}

.pager {
  display: flex;
  justify-content: flex-end;
}

.num {
  font-variant-numeric: tabular-nums;
}
</style>
