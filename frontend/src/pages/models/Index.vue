<template>
  <div class="page">
    <PageHeader title="模型管理" desc="模型是对话与向量化的底座。能力三态决定界面上开关怎么呈现，不是可选项">
      <template #actions>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        <el-button size="small" type="primary" @click="openCreate">添加模型</el-button>
      </template>
    </PageHeader>

    <!-- ★ 第三轮：删掉这块说明。
         它复述的是 PageHeader 的 `desc` 已经在说的事，而且把"三态怎么呈现"这种**设计口径**
         写在了用户界面上 —— 用户要回答的是"这个模型能不能思考"，不是"三态而非开关"。
         口径留在 design.md 与 `ModelFormDialog` 的就地提示里（那里才有上下文）。
         这正是使用者说的「单页功能堆太多」的第一类：**把文档放进了界面**。 -->
    <div class="filters">
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索模型名称或基础模型"
        style="width: 230px"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-select v-model="query.kind" size="small" placeholder="全部类型" style="width: 130px" @change="onFilterChange">
        <el-option label="全部类型" value="" />
        <!-- ★ 取值必须是契约码（SCREAMING_SNAKE），不是小写形态：mock 与后端都是按
             `m.kind === 'CHAT' / 'EMBEDDING'` 比的，传 'chat' 会永远筛出 0 条（issue #23）。 -->
        <el-option label="对话模型" value="CHAT" />
        <el-option label="向量模型" value="EMBEDDING" />
      </el-select>
      <el-select v-model="query.status" size="small" placeholder="全部状态" style="width: 130px" @change="onFilterChange">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STATUSES" :key="s" :label="labelOf(MODEL_STATUS_LABELS, s)" :value="s" />
      </el-select>
      <span class="micro muted">
        共 {{ total }} 个
        <template v-if="loadingCount">
          · <span class="live">{{ loadingCount }} 个加载中，自动刷新</span>
        </template>
      </span>
    </div>

    <div v-loading="loading" class="grid enter">
      <el-card v-for="m in list" :key="m.id" shadow="never" class="model-card" :class="{ failed: m.status === 'LOAD_FAILED' }">
        <div class="mc-head">
          <span class="mc-name ellipsis">{{ m.name }}</span>
          <el-tag v-if="m.builtin" size="small" effect="plain" type="info">内置</el-tag>
          <span class="grow" />
          <span class="status" :class="STATUS_CLASS[m.status]">
            <el-icon v-if="m.status === 'LOADING'" class="is-loading"><Loading /></el-icon>
            <i v-else class="dot" :class="STATUS_DOT[m.status]" />
            {{ labelOf(MODEL_STATUS_LABELS, m.status) }}
          </span>
        </div>

        <dl class="mc-facts">
          <div class="fact">
            <dt class="label">类型</dt>
            <dd>{{ KIND_LABEL[m.kind] }} · {{ labelOf(SUPPLIER_LABELS, m.supplier) }}</dd>
          </div>
          <div class="fact">
            <dt class="label">基础模型</dt>
            <dd class="ellipsis">{{ m.baseModel }}</dd>
          </div>
          <div class="fact">
            <dt class="label">{{ m.kind === 'EMBEDDING' ? '向量维度' : '上下文窗口' }}</dt>
            <dd>
              <template v-if="m.kind === 'EMBEDDING'">
                <span class="num">{{ m.dimension }}</span> 维
                <span class="micro muted">（与向量表绑定）</span>
              </template>
              <template v-else><span class="num">{{ m.contextWindow?.toLocaleString() }}</span> tokens</template>
            </dd>
          </div>
          <div v-if="m.kind === 'CHAT'" class="fact">
            <dt class="label">能力</dt>
            <dd class="caps">
              <span class="cap">
                <i class="dot" :class="CAP_DOT[m.thinking]" />
                思考：{{ THINKING_LABEL[m.thinking] }}
              </span>
              <span class="cap">
                <i class="dot" :class="CAP_DOT[m.tools]" />
                工具：{{ TOOLS_LABEL[m.tools] }}
              </span>
            </dd>
          </div>
        </dl>

        <p v-if="m.remark" class="mc-remark micro muted">{{ m.remark }}</p>

        <div v-if="m.status === 'LOAD_FAILED'" class="notice err mc-fail">
          <el-icon><WarningFilled /></el-icon>
          <span>
            <!-- 可读排查原因（契约 loadError，仅 LOAD_FAILED 有值）；没有原因时才退回静态文案 -->
            <b>{{ labelOf(MODEL_STATUS_LABELS, m.status) }}：</b>{{ m.loadError || '原因未知' }}
            <span class="micro">点「重新加载」可重试。</span>
          </span>
        </div>

        <div class="mc-actions">
          <el-button
            v-if="m.status !== 'LOADING'"
            link size="small" type="primary"
            @click="reload(m)"
          >{{ m.status === 'LOADED' ? '重新加载' : '加载' }}</el-button>
          <el-button v-else link size="small" disabled>加载中…</el-button>

          <el-button link size="small" type="primary" @click="openEdit(m)">编辑</el-button>

          <!-- 内置模型不可删 —— 按钮直接不渲染，而不是渲染后报错 -->
          <el-button v-if="!m.builtin" link size="small" type="danger" @click="remove(m)">删除</el-button>
          <span v-else class="micro muted">内置模型不可删除</span>
        </div>
      </el-card>

      <!-- 错误态 ≠ 空态：请求失败不能伪装成「暂无模型」 -->
      <div v-if="error" class="empty panel error-state">
        <div class="empty-title">加载失败</div>
        <hr class="rule-2" />
        <p class="micro muted">{{ error }}</p>
        <el-button size="small" type="primary" @click="onFilterChange">重试</el-button>
      </div>
      <div v-else-if="!loading && !list.length" class="empty panel">
        <div class="empty-title">暂无模型</div>
        <hr class="rule-2" />
        <p class="micro muted">
          {{ query.keyword || query.kind || query.status ? '当前筛选下没有模型' : '还没有注册任何模型' }}
        </p>
        <el-button size="small" type="primary" @click="openCreate">添加模型</el-button>
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

    <ModelFormDialog
      ref="formRef"
      :model="editing"
      @saved="onSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { modelsApi } from '@/api'
import type { ModelInfo, ModelKind, ModelStatus } from '@/api/models'
import { ApiError } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import ModelFormDialog from './components/ModelFormDialog.vue'
import { MODEL_STATUS_LABELS, SUPPLIER_LABELS, labelOf } from '@/constants/labels'

const STATUSES: ModelStatus[] = ['NOT_LOADED', 'LOADING', 'LOADED', 'LOAD_FAILED']

const KIND_LABEL: Record<ModelKind, string> = { CHAT: '对话', EMBEDDING: '向量' }
const THINKING_LABEL = {
  NONE: '无',
  TOGGLEABLE: '可开关',
  ALWAYS_ON: '强制开启'
} as const
const TOOLS_LABEL = {
  UNSUPPORTED: '不支持',
  SUPPORTED: '支持',
  PARALLEL: '支持并行'
} as const
const STATUS_CLASS: Record<ModelStatus, string> = {
  NOT_LOADED: 'st-idle',
  LOADING: 'st-loading',
  LOADED: 'st-ok',
  LOAD_FAILED: 'st-err'
}
const STATUS_DOT: Record<ModelStatus, string> = {
  NOT_LOADED: '',
  LOADING: 'warn',
  LOADED: 'ok',
  LOAD_FAILED: 'err'
}
/** 能力三态的点色：不支持=灰、可开关/支持=琥珀、强制/并行=绿 */
const CAP_DOT: Record<string, string> = {
  NONE: '',
  TOGGLEABLE: 'warn',
  ALWAYS_ON: 'ok',
  UNSUPPORTED: '',
  SUPPORTED: 'warn',
  PARALLEL: 'ok'
}

const route = useRoute()
const router = useRouter()

const list = ref<ModelInfo[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const pageSize = 12

/** 筛选/分页入 URL —— 刷新与直链不丢状态（一.2） */
const query = reactive({
  keyword: (route.query.keyword as string) ?? '',
  kind: ((route.query.kind as string) ?? '') as ModelKind | '',
  status: ((route.query.status as string) ?? '') as ModelStatus | '',
  page: Number(route.query.page) || 1
})

/** 把当前筛选/分页写回 URL（replace 不产生历史记录） */
const syncUrl = () => {
  router.replace({
    query: {
      ...(query.keyword ? { keyword: query.keyword } : {}),
      ...(query.kind ? { kind: query.kind } : {}),
      ...(query.status ? { status: query.status } : {}),
      ...(query.page > 1 ? { page: String(query.page) } : {})
    }
  })
}

/** 全平台「加载中」的模型数 —— 跨页统计，不是只看当前页 */
const globalLoadingCount = ref(0)
const loadingCount = computed(() =>
  Math.max(globalLoadingCount.value, list.value.filter((m) => m.status === 'LOADING').length)
)

const fetchList = async () => {
  loading.value = true
  error.value = null
  try {
    const result = await modelsApi.listModels({
      keyword: query.keyword || undefined,
      kind: query.kind || undefined,
      status: query.status || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
  } catch (e) {
    // 错误态 ≠ 空态：请求失败不能伪装成「暂无模型」（一.1）
    error.value = e instanceof ApiError ? e.message : '加载模型列表失败，请检查网络后重试'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/**
 * 跨页刷新：轮询时另外查一次「全部加载中」的 total，保证第 2 页的
 * "加载中"也能被感知 —— 否则 loadingCount 只看当前页，翻页后轮询就停了。
 */
const fetchGlobalLoadingCount = async () => {
  try {
    const result = await modelsApi.listModels({ status: 'LOADING', size: 1 })
    globalLoadingCount.value = result.total
  } catch {
    // 静默：计数失败不影响主列表
  }
}

/**
 * 轮询：加上限与退避（P2 文档 §8.5「加上限与退避，但不要改成 SSE」）。
 * - 最多 30 次（约 30×(1.5→8s) ≈ 3 分钟），之后自动停并提示
 * - 间隔从 1.5s 线性退避到 8s，避免后端卡住时无限每 1.5s 打一枪
 */
const MAX_POLLS = 30
const BASE_INTERVAL = 1500
const MAX_INTERVAL = 8000
let pollCount = 0
let timer: ReturnType<typeof setTimeout> | null = null

const clearTimer = () => {
  if (timer) clearTimeout(timer)
  timer = null
}

const nextInterval = () =>
  Math.min(BASE_INTERVAL + pollCount * 250, MAX_INTERVAL)

const schedule = () => {
  clearTimer()
  if (!loadingCount.value || document.hidden) {
    pollCount = 0
    return
  }
  if (pollCount >= MAX_POLLS) {
    ElMessage.info('模型加载较慢，自动刷新已暂停。可手动点「刷新」继续跟踪')
    pollCount = 0
    return
  }
  const interval = nextInterval()
  pollCount += 1
  timer = setTimeout(async () => {
    await Promise.all([fetchList(), fetchGlobalLoadingCount()])
    schedule()
  }, interval)
}

const onVisibility = () => {
  if (document.hidden) {
    clearTimer()
    return
  }
  // 回到前台立即补一次，不等下一个周期
  Promise.all([fetchList(), fetchGlobalLoadingCount()]).then(schedule)
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility)
  Promise.all([fetchList(), fetchGlobalLoadingCount()]).then(schedule)
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  clearTimer()
})

/* ── 筛选切换时重置 page（否则停在空页）+ 同步 URL ── */
const onFilterChange = () => {
  query.page = 1
  syncUrl()
  fetchList().then(schedule)
}

watch(() => query.page, () => {
  syncUrl()
})

// URL → 筛选/分页：浏览器前进/后退、页内改 hash 时界面跟随。
// 值比较后再赋值，避免与 syncUrl 互写成环；只在真变了时才拉列表。
watch(
  () => [route.query.keyword, route.query.kind, route.query.status, route.query.page],
  () => {
    const kw = route.query.keyword ? String(route.query.keyword) : ''
    const kind = route.query.kind ? String(route.query.kind) : ''
    const status = route.query.status ? String(route.query.status) : ''
    const pg = route.query.page ? Number(route.query.page) || 1 : 1
    if (kw === query.keyword && kind === query.kind && status === query.status && pg === query.page) return
    query.keyword = kw
    query.kind = kind as ModelKind | ''
    query.status = status as ModelStatus | ''
    query.page = pg
    fetchList().then(schedule)
  }
)

/* ── 操作 ── */
const formRef = ref<InstanceType<typeof ModelFormDialog>>()
const editing = ref<ModelInfo | null>(null)

const openCreate = () => {
  editing.value = null
  formRef.value?.open()
}

const openEdit = (model: ModelInfo) => {
  editing.value = model
  formRef.value?.open()
}

const onSaved = () => {
  fetchList().then(schedule)
}

const reload = async (model: ModelInfo) => {
  try {
    await modelsApi.loadModel(model.id)
    ElMessage.success(`已提交加载「${model.name}」，加载需要一点时间`)
    await fetchList()
    schedule()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '提交加载失败')
  }
}

const remove = async (model: ModelInfo) => {
  try {
    await ElMessageBox.confirm(
      `删除后使用该模型的应用将无法提问。确认删除「${model.name}」？`,
      '确认删除模型',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch {
    // 用户点了取消 —— 不是错误，静默返回
    return
  }
  try {
    await modelsApi.deleteModel(model.id)
    ElMessage.success('已删除')
    await fetchList()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '删除失败')
  }
}
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  /* ★ 第三轮：去框（原来是 `.filters.panel` —— 一条工具条外面还套一个 panel 盒子）。
     ⚠️ `.filters` 是**冻结钩子**：`smoke_test.py:244/263/271` 断言 `.filters .el-select`
     与 `.filters input[placeholder*='搜索模型名称']`。所以类名、子项顺序、控件都**不许动** ——
     "单页功能太多"要从别处减（说明文字、卡片事实），不许靠隐藏它们来实现。 */
  padding: var(--toolbar-pad-y) 0;
  border-bottom: 1px solid var(--color-rule-2);
}

.live {
  color: var(--color-accent);
  font-weight: 500;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--grid-gap);
  min-height: 120px;
}

/* 卡片：长相交给 element-plus.css 的 `.el-card`（去边框，靠 --color-surface 与画布的
   2% 明度差托起来）。这里只补它自己的排版节奏。 */
.model-card {
  display: flex;
  flex-direction: column;
}

.model-card :deep(.el-card__body) {
  padding: var(--card-pad);
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* ★ `.failed` 从「染边框」改为「描一圈环」：卡片已经没有边框了（第三轮去盒化），
   再写 `border-color` 就什么都不显示 —— 那是静默失效。
   环用 box-shadow 画，1px、30% 危险色，语义与旧版一致但不再依赖边框。 */
.model-card.failed {
  box-shadow: 0 0 0 1px color-mix(in oklch, var(--color-danger) 30%, var(--color-rule));
}

.mc-head {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

/* 卡片标题：mono 保留（模型名是标识符，等宽才排得整齐），字号抬到卡片标题档 18px。
   ★ 判据是 400 对 700 的对比 —— 旧版 600 对 400 只差 200 单位，读起来是"默认设置"。 */
.mc-name {
  font-family: var(--font-mono);
  font-size: var(--text-md);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  max-width: 62%;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-xs);
}

.st-idle {
  color: var(--color-muted);
}

.st-loading {
  color: var(--color-accent);
}

.st-ok {
  color: var(--color-ok);
}

.st-err {
  color: var(--color-danger);
}

.mc-facts {
  margin: var(--space-md) 0 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  flex: 1;
}

/* 事实行：标签 12px mono（`.label` 全球原子）+ 值 13px。
   ★ 第三轮把行距从 `--space-2xs`（4）放到 `--space-xs`（8）—— 4px 是"挤"的来源之一。 */
.fact {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  font-size: var(--text-xs);
  line-height: var(--leading-ui);
}

.fact dt {
  width: 72px;
  flex-shrink: 0;
  color: var(--color-muted);
}

.fact dd {
  margin: 0;
  min-width: 0;
  color: var(--color-ink-2);
  font-variant-numeric: tabular-nums;
}

.caps {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs) var(--space-md);
}

.cap {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3xs);
  font-size: var(--text-xs);
}

.mc-remark {
  margin: var(--space-sm) 0 0;
  font-size: var(--text-xs);
  line-height: var(--leading-body);
  color: var(--color-muted);
}

.mc-fail {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2xs);
  margin-top: var(--space-sm);
  font-size: var(--text-xs);
}

/* 操作区：一条发丝线把它从事实区分开（Editorial：靠线，不靠框） */
.mc-actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-top: var(--space-md);
  padding-top: var(--space-sm);
  border-top: 1px solid var(--color-rule-2);
}

.empty {
  grid-column: 1 / -1;
  padding: var(--space-2xl) var(--space-md);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);
}

.error-state {
  border-color: color-mix(in oklch, var(--color-danger) 30%, var(--color-rule));
}

.empty .rule-2 {
  width: 48px;
}

.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
