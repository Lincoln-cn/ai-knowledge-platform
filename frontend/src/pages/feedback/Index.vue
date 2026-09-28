<template>
  <div class="page">
    <PageHeader
      title="反馈处理"
      desc="把用户赞踩与文字意见变成可办的工作队列：指派、处理说明、跳转对话、沉淀问答对。默认只看待处理。"
    >
      <template #after-title>
        <el-tag size="small" type="warning" effect="plain">V0.2 设计预演</el-tag>
      </template>
      <template #actions>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <div class="notice warn">
      <b>V0.2 设计预演，不是 MVP 已交付能力。</b>
      <code>04-MVP最小验证系统设计</code> 把「反馈处理闭环（状态流转、指派）」列为 <b>V0.2</b>：
      MVP 阶段只做赞踩/评分的收集与只读列表。本页按目标设计（05·第四梯队第 3 项 / MISS-01）把闭环做出来，
      用于验证交互与状态机，<b>不能在 MVP 阶段假装它已交付</b>。
    </div>

    <div class="stats panel">
      <button
        v-for="s in STATUSES"
        :key="s"
        type="button"
        class="stat"
        :class="{ on: query.status === s }"
        @click="toggleStatus(s)"
      >
        <span class="stat-n num">{{ stats[s] ?? 0 }}</span>
        <span class="stat-k micro">{{ labelOf(FEEDBACK_STATUS_LABELS, s) }}</span>
      </button>
      <div class="stat-note micro muted">
        平均处理时长：契约未提供该字段（见迁移报告缺口）。点击卡片可切换状态筛选。
      </div>
    </div>

    <div class="filters panel">
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索问题 / 回答 / 反馈内容"
        style="width: 240px"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-select v-model="query.appId" size="small" placeholder="全部应用" style="width: 160px" @change="onFilterChange">
        <el-option label="全部应用" value="" />
        <el-option v-for="a in appOptions" :key="a.id" :label="a.name" :value="a.id" />
      </el-select>
      <el-select v-model="query.status" size="small" placeholder="全部状态" style="width: 130px" @change="onFilterChange">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STATUSES" :key="s" :label="labelOf(FEEDBACK_STATUS_LABELS, s)" :value="s" />
      </el-select>
      <span class="micro muted">共 {{ total }} 条</span>
      <span class="grow" />
      <!-- 批量入口只在有 feedback:handle 时出现（session.can 与页面门禁同一点） -->
      <template v-if="canHandle">
        <span class="micro muted">已选 {{ selectedRows.length }} 项</span>
        <el-button
          size="small"
          type="primary"
          :disabled="!selectedRows.length"
          @click="openBatch('status')"
        >批量改状态</el-button>
        <el-button
          size="small"
          :disabled="!selectedRows.length"
          @click="openBatch('assign')"
        >批量指派</el-button>
      </template>
    </div>

    <div class="panel table-wrap" v-loading="loading">
      <el-table :data="list" style="width: 100%" :row-class-name="rowClass" @selection-change="onSelectionChange">
        <el-table-column v-if="canHandle" type="selection" width="42" />
        <el-table-column label="赞踩" width="64" align="center">
          <template #default="{ row }">
            <span class="vote" :class="row.type === 'UP' ? 'up' : 'down'">
              <i class="dot" :class="row.type === 'UP' ? 'ok' : 'err'" />
              {{ row.type === 'UP' ? '赞' : '踩' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column prop="question" label="用户问题" min-width="180" show-overflow-tooltip />

        <el-table-column prop="answer" label="系统回答" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="answer-text">{{ row.answer }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="comment" label="用户反馈内容" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.comment" class="comment-text">{{ row.comment }}</span>
            <span v-else class="muted micro">—</span>
          </template>
        </el-table-column>

        <el-table-column prop="appName" label="所属应用" width="120" show-overflow-tooltip />

        <el-table-column label="关联会话" width="140">
          <template #default="{ row }">
            <button
              v-if="row.conversationId"
              type="button"
              class="link"
              :title="row.conversationTitle || row.conversationId"
              @click="goConversation(row)"
            >
              {{ row.conversationTitle || '查看对话' }}
            </button>
            <span v-else class="muted micro">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="status-cell" :class="statusDot(row.status)">
              <i class="dot" :class="statusDot(row.status)" />
              {{ labelOf(FEEDBACK_STATUS_LABELS, row.status) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="处理人" width="90">
          <template #default="{ row }">
            <span v-if="row.assignee">{{ row.assignee }}</span>
            <span v-else class="muted micro">未指派</span>
          </template>
        </el-table-column>

        <el-table-column prop="createdAt" label="提交时间" width="130" />

        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <div class="ops">
              <el-button
                v-if="canHandle && isOpen(row)"
                link size="small" type="primary"
                @click="openHandle(row)"
              >办理</el-button>
              <el-button
                v-else
                link size="small"
                @click="openDetail(row)"
              >详情</el-button>

              <el-button
                v-if="canHandle && isClosed(row)"
                link size="small" type="warning"
                @click="reopen(row)"
              >重新打开</el-button>

              <el-button
                v-if="canHandle && !row.promotedToKbId"
                link size="small"
                @click="openPromote(row)"
              >优化答案</el-button>
              <span v-else-if="row.promotedToKbId" class="micro muted">已沉淀</span>

              <el-button
                v-if="row.conversationId"
                link size="small" type="primary"
                @click="goConversation(row)"
              >对话</el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <div v-if="error" class="empty">
            <div class="empty-title">加载失败</div>
            <hr class="rule-2" />
            <p class="micro muted">{{ error }}</p>
            <el-button size="small" type="primary" @click="onFilterChange">重试</el-button>
          </div>
          <div v-else class="empty">
            <div class="empty-title">暂无反馈</div>
            <hr class="rule-2" />
            <p class="micro muted">
              {{ query.keyword || query.appId || query.status ? '当前筛选下没有反馈' : '还没有任何反馈' }}
            </p>
            <p class="micro muted">用户在对话页点赞 / 点踩 / 评分后会出现在这里（默认只看待处理）</p>
          </div>
        </template>
      </el-table>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="fetchList"
      />
    </div>

    <HandleDialog ref="handleRef" :item="current" @saved="onSaved" />
    <PromoteDialog ref="promoteRef" :item="current" @saved="onSaved" />

    <!-- 批量办理：状态/指派共用一个对话框，按入口决定主字段 -->
    <el-dialog v-model="batchVisible" :title="batchMode === 'assign' ? '批量指派' : '批量改状态'" width="480px">
      <p class="micro muted">将对已勾选的 {{ selectedRows.length }} 条反馈统一办理。状态机会逐条校验，办不成的会给出原因。</p>
      <el-form label-width="88px" class="batch-form">
        <el-form-item v-if="batchMode === 'status'" label="目标状态">
          <el-radio-group v-model="batchStatus">
            <el-radio-button v-for="s in batchStatusOptions" :key="s" :value="s">{{ labelOf(FEEDBACK_STATUS_LABELS, s) }}</el-radio-button>
          </el-radio-group>
          <div class="hint micro">已办结的反馈不能改状态，需先「重新打开」</div>
        </el-form-item>
        <el-form-item v-if="batchStatus === 'RESOLVED'" label="处理说明">
          <el-input
            v-model="batchResolution"
            type="textarea"
            :rows="3"
            resize="none"
            maxlength="500"
            show-word-limit
            placeholder="流转到「已处理」必须填写处理说明"
          />
        </el-form-item>
        <el-form-item label="处理人">
          <el-select v-model="batchAssigneeId" placeholder="不改动 / 指派给…" clearable style="width: 260px">
            <el-option label="（不改动处理人）" value="" />
            <el-option v-for="u in batchUsers" :key="u.id" :label="u.displayName" :value="u.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="batchVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="batchLoading" @click="submitBatch">
          办理 {{ selectedRows.length }} 条
        </el-button>
      </template>
    </el-dialog>

    <!-- 部分失败回执：逐条列出成功/失败，不能只打一个绿色 toast -->
    <el-dialog v-model="receiptVisible" title="批量办理回执" width="560px">
      <div v-if="receipt" class="receipt">
        <div class="receipt-summary micro">
          成功 <b class="num">{{ receipt.accepted.length }}</b> 条 ·
          失败 <b class="num">{{ receipt.rejected.length }}</b> 条
        </div>
        <div v-if="receipt.accepted.length" class="receipt-block">
          <div class="receipt-head micro ok">已办成</div>
          <ul class="receipt-list">
            <li v-for="item in receipt.accepted" :key="item.id">
              <span class="ok">✓</span> {{ item.id }} · {{ labelOf(FEEDBACK_STATUS_LABELS, item.status) }}
              <span class="muted micro">{{ item.question }}</span>
            </li>
          </ul>
        </div>
        <div v-if="receipt.rejected.length" class="receipt-block">
          <div class="receipt-head micro err">未办成（逐条原因）</div>
          <ul class="receipt-list">
            <li v-for="r in receipt.rejected" :key="r.id">
              <span class="err">✗</span> <b>{{ r.id }}</b> — {{ r.reason }}
            </li>
          </ul>
        </div>
      </div>
      <template #footer>
        <el-button size="small" type="primary" @click="receiptVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { feedbackApi, identityApi } from '@/api'
import type {
  BatchHandleFeedbackResult,
  FeedbackItem,
  FeedbackStats,
  FeedbackStatus
} from '@/api/feedback'
import type { User } from '@/api/identity'
import { ApiError } from '@/api/types'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import PageHeader from '@/components/PageHeader.vue'
import HandleDialog from './components/HandleDialog.vue'
import PromoteDialog from './components/PromoteDialog.vue'
import { FEEDBACK_STATUS_LABELS, labelOf } from '@/constants/labels'

const STATUSES: FeedbackStatus[] = ['PENDING', 'PROCESSING', 'RESOLVED', 'IGNORED']

const STATUS_DOT: Record<FeedbackStatus, string> = {
  PENDING: 'warn',
  PROCESSING: '',
  RESOLVED: 'ok',
  IGNORED: 'err'
}
const statusDot = (status: string) => STATUS_DOT[status as FeedbackStatus] ?? ''

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const canHandle = computed(() => session.can(PERM.feedbackHandle))

const list = ref<FeedbackItem[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const pageSize = 10

/** 默认只看待处理 —— 形成工作队列，而不是又一个只读日志。筛选/分页入 URL */
const query = reactive({
  keyword: (route.query.keyword as string) ?? '',
  status: ((route.query.status as string) ?? 'PENDING') as FeedbackStatus | '',
  appId: (route.query.appId as string) ?? ''
})
const page = ref(Number(route.query.page) || 1)

/** 把当前筛选/分页写回 URL（replace 不产生历史记录） */
const syncUrl = () => {
  router.replace({
    query: {
      ...(query.keyword ? { keyword: query.keyword } : {}),
      ...(query.status ? { status: query.status } : {}),
      ...(query.appId ? { appId: query.appId } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {})
    }
  })
}

const emptyStats: FeedbackStats = { PENDING: 0, PROCESSING: 0, RESOLVED: 0, IGNORED: 0 }
const stats = ref<FeedbackStats>({ ...emptyStats })

const current = ref<FeedbackItem | null>(null)
const handleRef = ref<InstanceType<typeof HandleDialog>>()
const promoteRef = ref<InstanceType<typeof PromoteDialog>>()

/* ────────────────────────── 批量办理 ────────────────────────── */

const selectedRows = ref<FeedbackItem[]>([])
const batchVisible = ref(false)
const batchLoading = ref(false)
const batchMode = ref<'status' | 'assign'>('status')
const batchStatus = ref<FeedbackStatus>('PROCESSING')
const batchResolution = ref('')
const batchAssigneeId = ref('')
const batchUsers = ref<User[]>([])
const receiptVisible = ref(false)
const receipt = ref<BatchHandleFeedbackResult | null>(null)

/** 批量改状态可选目标：不含「待处理」（不是工作队列的前进方向） */
const batchStatusOptions: FeedbackStatus[] = ['PROCESSING', 'RESOLVED', 'IGNORED']

const onSelectionChange = (rows: FeedbackItem[]) => {
  selectedRows.value = rows
}

const loadBatchUsers = async () => {
  try {
    const result = await identityApi.listUsers({ page: 1, size: 100 })
    batchUsers.value = result.list
  } catch {
    // 用户目录拿不到就不让指派，状态批量仍可用
    batchUsers.value = []
  }
}

const openBatch = (mode: 'status' | 'assign') => {
  if (!selectedRows.value.length) return
  batchMode.value = mode
  batchStatus.value = 'PROCESSING'
  batchResolution.value = ''
  batchAssigneeId.value = ''
  batchVisible.value = true
  loadBatchUsers()
}

const submitBatch = async () => {
  if (!selectedRows.value.length) return
  if (batchStatus.value === 'RESOLVED' && !batchResolution.value.trim()) {
    ElMessage.warning('流转到「已处理」必须填写处理说明')
    return
  }
  batchLoading.value = true
  try {
    // 指派入口也带状态（契约 body 必有 status）：指派默认推进到「处理中」
    const status: FeedbackStatus = batchMode.value === 'assign' ? 'PROCESSING' : batchStatus.value
    const result = await feedbackApi.batchHandleFeedbacks({
      ids: selectedRows.value.map((r) => r.id),
      status,
      ...(batchAssigneeId.value ? { assigneeId: batchAssigneeId.value } : {}),
      ...(status === 'RESOLVED' ? { resolution: batchResolution.value } : {})
    })
    receipt.value = result
    batchVisible.value = false
    // 部分失败也要让用户看见逐条回执，不能只打一个绿色 toast
    receiptVisible.value = true
    if (!result.rejected.length) {
      ElMessage.success(`已办理 ${result.accepted.length} 条`)
    } else if (result.accepted.length) {
      ElMessage.warning(`成功 ${result.accepted.length} 条，失败 ${result.rejected.length} 条 —— 详见回执`)
    } else {
      ElMessage.error(`全部失败（${result.rejected.length} 条）—— 详见回执`)
    }
    selectedRows.value = []
    refresh()
  } catch (e) {
    // ApiError 文案已可读（含 403 缺 feedback:handle），不再叠一层
    if (!(e instanceof ApiError)) {
      ElMessage.error('批量办理失败，请稍后重试')
    }
  } finally {
    batchLoading.value = false
  }
}

/** 应用筛选项从已加载数据累积；知识/应用模块迁移后可改为走契约 */
const appOptions = ref<Array<{ id: string; name: string }>>([])

const collectApps = (items: FeedbackItem[]) => {
  const map = new Map(appOptions.value.map((a) => [a.id, a]))
  for (const f of items) map.set(f.appId, { id: f.appId, name: f.appName })
  appOptions.value = [...map.values()].sort((a, b) => a.name.localeCompare(b.name))
}

const isOpen = (row: FeedbackItem) => row.status === 'PENDING' || row.status === 'PROCESSING'
const isClosed = (row: FeedbackItem) => row.status === 'RESOLVED' || row.status === 'IGNORED'

const rowClass = ({ row }: { row: FeedbackItem }) =>
  row.status === 'PENDING' ? 'row-pending' : row.type === 'DOWN' ? 'row-down' : ''

const fetchList = async () => {
  loading.value = true
  error.value = null
  try {
    const result = await feedbackApi.listFeedbacks({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      appId: query.appId || undefined,
      page: page.value,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
    // 列表换了（筛选/分页/办理后刷新）——旧勾选不能带着走，否则批量会打到看不见的行上
    selectedRows.value = []
    collectApps(result.list)
  } catch (e) {
    // 错误态 ≠ 空态：请求失败不能伪装成「暂无反馈」（一.1）
    error.value = e instanceof ApiError ? e.message : '加载反馈列表失败，请检查网络后重试'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const fetchStats = async () => {
  try {
    stats.value = await feedbackApi.getFeedbackStats()
  } catch {
    stats.value = { ...emptyStats }
  }
}

const refresh = () => {
  fetchList()
  fetchStats()
}

/** 筛选切换时重置 page（否则停在空页）+ 同步 URL */
const onFilterChange = () => {
  page.value = 1
  syncUrl()
  fetchList()
}

const toggleStatus = (s: FeedbackStatus) => {
  query.status = query.status === s ? '' : s
  onFilterChange()
}

watch(page, () => syncUrl())

// URL → 筛选/分页：浏览器前进/后退、页内改 hash 时界面跟随。
// 值比较后再赋值，避免与 syncUrl 互写成环；只在真变了时才拉列表。
// 注意 status 缺省在这里读作 ''（= 不筛），不是初始默认「待处理」——
// syncUrl 非空才写 status，用户清掉筛选后 URL 就是无 status，不能再弹回默认。
watch(
  () => [route.query.keyword, route.query.status, route.query.appId, route.query.page],
  () => {
    const kw = route.query.keyword ? String(route.query.keyword) : ''
    const st = (route.query.status ? String(route.query.status) : '') as FeedbackStatus | ''
    const app = route.query.appId ? String(route.query.appId) : ''
    const pg = route.query.page ? Number(route.query.page) || 1 : 1
    if (kw === query.keyword && st === query.status && app === query.appId && pg === page.value) return
    query.keyword = kw
    query.status = st
    query.appId = app
    page.value = pg
    fetchList()
  }
)

const onSaved = () => refresh()

const openHandle = (row: FeedbackItem) => {
  current.value = row
  handleRef.value?.open()
}

const openDetail = (row: FeedbackItem) => {
  current.value = row
  handleRef.value?.open({ readonly: true })
}

const openPromote = (row: FeedbackItem) => {
  current.value = row
  promoteRef.value?.open()
}

const goConversation = (row: FeedbackItem) => {
  if (!row.conversationId) return
  router.push({ path: '/conversation', query: { conversationId: row.conversationId } })
}

const reopen = async (row: FeedbackItem) => {
  try {
    await ElMessageBox.confirm(
      `重新打开后状态回到「处理中」并清空处理时间，可继续指派与填写说明。确认重新打开该反馈？`,
      '重新打开反馈',
      { type: 'warning', confirmButtonText: '重新打开' }
    )
  } catch {
    // 用户点了取消 —— 不是错误，静默返回
    return
  }
  try {
    await feedbackApi.reopenFeedback(row.id)
    ElMessage.success('已重新打开，状态回到「处理中」')
    refresh()
  } catch {
    // ApiError 文案已可读，这里不再叠一层
  }
}

onMounted(() => {
  syncUrl()
  refresh()
})
</script>

<style scoped>
.stats {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  flex-wrap: wrap;
}

.stat {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-3xs);
  min-width: 88px;
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: border-color var(--dur-micro) var(--ease-out), background var(--dur-micro) var(--ease-out);
}

.stat:hover {
  border-color: var(--color-accent-line);
  background: var(--color-hover);
}

.stat.on {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
  box-shadow: inset 2px 0 0 var(--color-accent);
}

.stat-n {
  font-family: var(--font-display);
  font-size: var(--text-lg);
  font-weight: 600;
  line-height: var(--leading-display);
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
}

.stat-k {
  color: var(--color-muted);
}

.stat-note {
  margin-left: auto;
  max-width: 280px;
  line-height: var(--leading-body);
}

.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  padding: var(--space-sm) var(--space-md);
}

.grow {
  flex: 1;
}

.batch-form .hint {
  margin-top: var(--space-3xs);
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.receipt-summary {
  margin-bottom: var(--space-sm);
  color: var(--color-ink-2);
}

.receipt-block {
  margin-bottom: var(--space-sm);
}

.receipt-head {
  font-weight: 600;
  margin-bottom: var(--space-3xs);
}

.receipt-head.ok {
  color: var(--color-ok);
}

.receipt-head.err {
  color: var(--color-danger);
}

.receipt-list {
  margin: 0;
  padding-left: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.receipt-list .ok {
  color: var(--color-ok);
}

.receipt-list .err {
  color: var(--color-danger);
}

.table-wrap {
  overflow: hidden;
}

.answer-text {
  color: var(--color-ink-2);
}

.comment-text {
  color: var(--color-ink-2);
}

.vote {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3xs);
  font-size: var(--text-2xs);
}

.vote.up {
  color: var(--color-ok);
}

.vote.down {
  color: var(--color-danger);
}

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3xs);
  font-size: var(--text-2xs);
}

.status-cell.warn {
  color: var(--color-warn);
}

.status-cell.ok {
  color: var(--color-ok);
}

.status-cell.err {
  color: var(--color-danger);
}

.ops {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  flex-wrap: wrap;
}

.link {
  border: 0;
  background: none;
  padding: 0;
  color: var(--color-accent);
  cursor: pointer;
  font: inherit;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.link:hover {
  color: var(--color-accent-hover);
  text-decoration: underline;
}

.empty {
  padding: var(--space-xl) var(--space-md);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);
}

.empty .rule-2 {
  width: 48px;
}

.pager {
  display: flex;
  justify-content: flex-end;
}

:deep(.row-pending) {
  box-shadow: inset 3px 0 0 var(--color-warn);
}

:deep(.row-down .comment-text) {
  color: var(--color-danger);
}
</style>
