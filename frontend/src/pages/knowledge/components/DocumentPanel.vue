<template>
  <div class="doc-panel">
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索文档名"
        style="width: 200px"
        clearable
        :prefix-icon="Search"
        @keyup.enter="fetchList"
        @clear="fetchList"
      />
      <el-select v-model="query.status" size="small" placeholder="全部状态" style="width: 130px" @change="onFilterChange">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
      </el-select>
      <el-button size="small" :icon="Refresh" :loading="loading" @click="manualRefresh">刷新</el-button>
      <span class="grow" />
      <el-button v-if="canUpload" size="small" type="primary" @click="openUpload">+ 上传文档</el-button>
    </div>

    <GlobalProgress :progress="progress" :load-error="progressError" @retry="manualRefresh" />

    <div v-if="loadError" class="notice err">
      <div><b>加载失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" style="margin-top: var(--space-xs)" @click="refreshAll">重试</el-button>
    </div>

    <div v-loading="loading" class="table-wrap">
      <el-table :data="list" row-key="id" empty-text="暂无文档，点右上角「上传文档」开始导入">
        <el-table-column label="文档名" min-width="200">
          <template #default="{ row }">
            <div class="doc-name">
              <span class="ellipsis">{{ row.name }}</span>
              <el-button
                v-if="canManage"
                link size="small" :icon="EditPen"
                class="rename-btn"
                @click="openRename(row)"
              />
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分段数" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.segmentCount" class="num">{{ row.segmentCount }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <span class="status" :class="statusClass(row.status as DocumentStatus)">
              <el-icon v-if="isRunning(row.status as DocumentStatus)" class="is-loading"><Loading /></el-icon>
              <i v-else class="dot" :class="statusDot(row.status as DocumentStatus)" />
              {{ row.status }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <!-- 行内进度：向量化中时展示 processed/total，不是百分比 -->
            <span v-if="row.status === '向量化中'" class="row-progress">
              <span class="rp-bar">
                <span class="rp-fill" :style="{ width: embedPercent(row) + '%' }" />
              </span>
              <span class="micro num">{{ row.processedSegments }}/{{ row.segmentCount }}</span>
            </span>

            <el-button
              v-if="row.status === '待向量化' && canUpload"
              link size="small" type="primary"
              @click="startEmbed(row)"
            >开始向量化</el-button>

            <!-- #10 文档级重新向量化入口 -->
            <el-button
              v-if="row.status === '成功' && canUpload"
              link size="small"
              @click="reembed(row)"
            >重新向量化</el-button>

            <template v-if="isFailed(row.status)">
              <el-button link size="small" @click="toggleFail(row.id)">
                {{ expandedFails.has(row.id) ? '收起原因' : '查看原因' }}
              </el-button>
              <el-button v-if="canUpload" link size="small" type="primary" @click="retry(row)">重试</el-button>
            </template>

            <el-button
              v-if="row.status === '成功'"
              link size="small"
              @click="emit('gotoSegments', row.id)"
            >查看分段</el-button>

            <el-button v-if="canManage" link size="small" @click="toggleEnabled(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canManage" link size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 失败原因展开在表格下方独立区块，避免 expand 列不好用 -->
    <div v-for="row in failedRows" :key="'f' + row.id">
      <div v-if="expandedFails.has(row.id)" class="notice err fail-panel">
        <div class="row-between">
          <div class="fail-title">{{ row.name }} · 失败原因</div>
          <el-button link size="small" @click="toggleFail(row.id)">收起</el-button>
        </div>
        <p class="fail-reason">{{ row.failReason || '未提供失败原因' }}</p>
        <p v-if="row.failHint" class="fail-hint">{{ row.failHint }}</p>
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

    <UploadDialog ref="uploadRef" :kb-id="kbId" @uploaded="onUploaded" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Loading, Refresh, Search } from '@element-plus/icons-vue'
import { ingestionApi } from '@/api'
import { ApiError } from '@/api/types'
import type { DocumentItem, DocumentStatus, IngestionProgress } from '@/api/ingestion'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import GlobalProgress from './GlobalProgress.vue'
import UploadDialog from './UploadDialog.vue'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ gotoSegments: [documentId: string] }>()

const session = useSessionStore()
const canUpload = computed(() => session.can(PERM.documentUpload))
const canManage = computed(() => session.can(PERM.knowledgeManage))

const STATUSES: DocumentStatus[] = [
  '待解析',
  '分段中',
  '待向量化',
  '向量化中',
  '成功',
  '分段失败',
  '向量化失败'
]

const STATUS_CLASS: Record<DocumentStatus, string> = {
  待解析: 'st-idle',
  分段中: 'st-loading',
  待向量化: 'st-warn',
  向量化中: 'st-loading',
  成功: 'st-ok',
  分段失败: 'st-err',
  向量化失败: 'st-err'
}

const STATUS_DOT: Record<DocumentStatus, string> = {
  待解析: '',
  分段中: 'warn',
  待向量化: 'warn',
  向量化中: 'warn',
  成功: 'ok',
  分段失败: 'err',
  向量化失败: 'err'
}

const isRunning = (s: DocumentStatus) => s === '待解析' || s === '分段中' || s === '向量化中'
const isFailed = (s: DocumentStatus) => s === '分段失败' || s === '向量化失败'
const statusClass = (s: DocumentStatus) => STATUS_CLASS[s] ?? 'st-idle'
const statusDot = (s: DocumentStatus) => STATUS_DOT[s] ?? ''

const list = ref<DocumentItem[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref<{ reason: string; hint?: string } | null>(null)
const progressError = ref<string | null>(null)
const pageSize = 20
const expandedFails = ref(new Set<string>())

const query = reactive({
  keyword: '',
  status: '' as DocumentStatus | '',
  page: 1
})

const progress = ref<IngestionProgress>({
  kbId: props.kbId,
  total: 0,
  done: 0,
  failed: 0,
  stages: [],
  running: false
})

const failedRows = computed(() => list.value.filter((d) => isFailed(d.status)))

const embedPercent = (row: DocumentItem) => {
  if (!row.segmentCount) return 0
  return Math.min(100, Math.round((row.processedSegments / row.segmentCount) * 100))
}

const errText = (error: unknown, fallback: string): { reason: string; hint?: string } => {
  if (error instanceof ApiError) return { reason: error.message, hint: error.hint }
  if (error instanceof Error) return { reason: error.message || fallback }
  return { reason: fallback }
}

const fetchList = async () => {
  loading.value = true
  loadError.value = null
  try {
    const result = await ingestionApi.listDocuments(props.kbId, {
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    loadError.value = errText(error, '加载文档列表失败')
  } finally {
    loading.value = false
  }
}

const fetchProgress = async () => {
  progressError.value = null
  try {
    progress.value = await ingestionApi.getIngestionProgress(props.kbId)
  } catch (error) {
    // 进度失败必须与"暂无文档"区分：GlobalProgress 靠这个字段换标题
    progressError.value = errText(error, '加载进度失败').reason
    throw error
  }
}

const refreshAll = async () => {
  await Promise.all([fetchList(), fetchProgress().catch(() => undefined)])
}

/* ── 轮询：只在有进行中任务时轮询；完成即停；页面不可见暂停；
      上限 + 退避 —— 后端卡在"运行中"时不会无限每 1.5s 打一枪 ── */
const POLL_BASE_MS = 1500
const POLL_MAX_MS = 12_000
const POLL_MAX_ATTEMPTS = 48

let timer: ReturnType<typeof setTimeout> | null = null
let pollAttempts = 0

const clearTimer = () => {
  if (timer) clearTimeout(timer)
  timer = null
}

const nextDelay = () =>
  Math.min(POLL_MAX_MS, Math.round(POLL_BASE_MS * Math.pow(1.25, Math.max(0, pollAttempts - 1))))

const schedule = () => {
  clearTimer()
  if (!progress.value.running || document.hidden) {
    pollAttempts = 0
    return
  }
  if (pollAttempts >= POLL_MAX_ATTEMPTS) {
    progressError.value = `轮询已达上限（${POLL_MAX_ATTEMPTS} 次），已停止自动刷新。可手动点「刷新」继续。`
    return
  }
  pollAttempts += 1
  timer = setTimeout(async () => {
    try {
      await refreshAll()
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
  refreshAll().then(schedule, clearTimer)
}

const manualRefresh = async () => {
  pollAttempts = 0
  await refreshAll()
  schedule()
}

const onFilterChange = () => {
  query.page = 1
  fetchList().then(schedule)
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility)
  refreshAll().then(schedule)
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  clearTimer()
})

/* ── 操作 ── */
const uploadRef = ref<InstanceType<typeof UploadDialog>>()

const openUpload = () => uploadRef.value?.open()

const onUploaded = async () => {
  await refreshAll()
  schedule()
}

const startEmbed = async (row: DocumentItem) => {
  try {
    await ingestionApi.startEmbedding(row.id)
    ElMessage.success(`已开始向量化「${row.name}」`)
    pollAttempts = 0
    await refreshAll()
    schedule()
  } catch (error) {
    ElMessage.error(errText(error, '启动向量化失败').reason)
  }
}

/** #10 文档级重新向量化 */
const reembed = async (row: DocumentItem) => {
  try {
    await ingestionApi.reembedDocument(row.id)
    ElMessage.success(`已开始重新向量化「${row.name}」`)
    pollAttempts = 0
    await refreshAll()
    schedule()
  } catch (error) {
    ElMessage.error(errText(error, '重新向量化失败').reason)
  }
}

const retry = async (row: DocumentItem) => {
  try {
    await ingestionApi.retryDocument(row.id)
    ElMessage.success(`已重新提交「${row.name}」，从失败阶段继续`)
    expandedFails.value.delete(row.id)
    pollAttempts = 0
    await refreshAll()
    schedule()
  } catch (error) {
    ElMessage.error(errText(error, '重试失败').reason)
  }
}

const toggleFail = (id: string) => {
  const next = new Set(expandedFails.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedFails.value = next
}

const openRename = async (row: DocumentItem) => {
  let value: string
  try {
    const res = await ElMessageBox.prompt('输入新的文档名', '重命名文档', {
      inputValue: row.name,
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    value = res.value
  } catch {
    // 用户取消，不是错误
    return
  }
  if (!value?.trim()) return
  try {
    await ingestionApi.renameDocument(row.id, value.trim())
    ElMessage.success('已重命名')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '重命名失败').reason)
  }
}

const toggleEnabled = async (row: DocumentItem) => {
  const next = !row.enabled
  if (!next) {
    try {
      await ElMessageBox.confirm(
        `停用后「${row.name}」的分段将不再参与检索。确认停用？`,
        '确认停用文档',
        { type: 'warning', confirmButtonText: '停用' }
      )
    } catch {
      return
    }
  }
  try {
    await ingestionApi.setDocumentEnabled(row.id, next)
    ElMessage.success(next ? '已启用' : '已停用')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '操作失败').reason)
  }
}

const remove = async (row: DocumentItem) => {
  try {
    await ElMessageBox.confirm(
      `删除「${row.name}」将一并移除其全部分段，且不可恢复。确认删除？`,
      '确认删除文档',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch {
    return
  }
  try {
    await ingestionApi.deleteDocument(row.id)
    ElMessage.success('已删除')
    await refreshAll()
  } catch (error) {
    ElMessage.error(errText(error, '删除失败').reason)
    await refreshAll()
  }
}
</script>

<style scoped>
.doc-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.table-wrap {
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.doc-name {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  min-width: 0;
}

.rename-btn {
  opacity: 0;
  transition: opacity var(--dur-micro) var(--ease-out);
}

tr:hover .rename-btn {
  opacity: 1;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
}

.st-idle { color: var(--color-muted); }
.st-loading { color: var(--color-accent); }
.st-warn { color: var(--color-warn); }
.st-ok { color: var(--color-ok); }
.st-err { color: var(--color-danger); }

.row-progress {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  margin-right: var(--space-xs);
  vertical-align: middle;
}

.rp-bar {
  display: inline-block;
  width: 64px;
  height: 4px;
  border-radius: var(--radius-pill);
  background: var(--color-sunken);
  overflow: hidden;
}

.rp-fill {
  display: block;
  height: 100%;
  background: var(--color-accent);
  border-radius: var(--radius-pill);
  transition: width var(--dur-short) var(--ease-out);
}

.fail-panel {
  margin-bottom: var(--space-xs);
}

.fail-title {
  font-size: var(--text-2xs);
  font-weight: 500;
  line-height: var(--leading-tight);
  margin-bottom: var(--space-2xs);
  color: var(--color-ink);
}

.fail-reason {
  margin: 0;
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.fail-hint {
  margin: var(--space-2xs) 0 0;
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
  opacity: 0.85;
}

.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
