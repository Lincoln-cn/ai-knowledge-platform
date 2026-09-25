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
        <span class="stat-k micro">{{ s }}</span>
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
        @keyup.enter="fetchList"
        @clear="fetchList"
      />
      <el-select v-model="query.appId" size="small" placeholder="全部应用" style="width: 160px" @change="fetchList">
        <el-option label="全部应用" value="" />
        <el-option v-for="a in appOptions" :key="a.id" :label="a.name" :value="a.id" />
      </el-select>
      <el-select v-model="query.status" size="small" placeholder="全部状态" style="width: 130px" @change="fetchList">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
      </el-select>
      <span class="micro muted">共 {{ total }} 条</span>
    </div>

    <div class="panel table-wrap" v-loading="loading">
      <el-table :data="list" style="width: 100%" :row-class-name="rowClass">
        <el-table-column label="赞踩" width="64" align="center">
          <template #default="{ row }">
            <span class="vote" :class="row.type === 'up' ? 'up' : 'down'">
              <i class="dot" :class="row.type === 'up' ? 'ok' : 'err'" />
              {{ row.type === 'up' ? '赞' : '踩' }}
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
              {{ row.status }}
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
          <div class="empty">
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { feedbackApi } from '@/api'
import type { FeedbackItem, FeedbackStats, FeedbackStatus } from '@/api/feedback'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import PageHeader from '@/components/PageHeader.vue'
import HandleDialog from './components/HandleDialog.vue'
import PromoteDialog from './components/PromoteDialog.vue'

const STATUSES: FeedbackStatus[] = ['待处理', '处理中', '已处理', '已忽略']

const STATUS_DOT: Record<FeedbackStatus, string> = {
  待处理: 'warn',
  处理中: '',
  已处理: 'ok',
  已忽略: 'err'
}
const statusDot = (status: string) => STATUS_DOT[status as FeedbackStatus] ?? ''

const router = useRouter()
const session = useSessionStore()
const canHandle = computed(() => session.can(PERM.feedbackHandle))

const list = ref<FeedbackItem[]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const pageSize = 10

/** 默认只看待处理 —— 形成工作队列，而不是又一个只读日志 */
const query = reactive({
  keyword: '',
  status: '待处理' as FeedbackStatus | '',
  appId: ''
})

const emptyStats: FeedbackStats = { 待处理: 0, 处理中: 0, 已处理: 0, 已忽略: 0 }
const stats = ref<FeedbackStats>({ ...emptyStats })

const current = ref<FeedbackItem | null>(null)
const handleRef = ref<InstanceType<typeof HandleDialog>>()
const promoteRef = ref<InstanceType<typeof PromoteDialog>>()

/** 应用筛选项从已加载数据累积；知识/应用模块迁移后可改为走契约 */
const appOptions = ref<Array<{ id: string; name: string }>>([])

const collectApps = (items: FeedbackItem[]) => {
  const map = new Map(appOptions.value.map((a) => [a.id, a]))
  for (const f of items) map.set(f.appId, { id: f.appId, name: f.appName })
  appOptions.value = [...map.values()].sort((a, b) => a.name.localeCompare(b.name))
}

const isOpen = (row: FeedbackItem) => row.status === '待处理' || row.status === '处理中'
const isClosed = (row: FeedbackItem) => row.status === '已处理' || row.status === '已忽略'

const rowClass = ({ row }: { row: FeedbackItem }) =>
  row.status === '待处理' ? 'row-pending' : row.type === 'down' ? 'row-down' : ''

const fetchList = async () => {
  loading.value = true
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
    collectApps(result.list)
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

const toggleStatus = (s: FeedbackStatus) => {
  query.status = query.status === s ? '' : s
  page.value = 1
  fetchList()
}

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
  await ElMessageBox.confirm(
    `重新打开后状态回到「处理中」并清空处理时间，可继续指派与填写说明。确认重新打开该反馈？`,
    '重新打开反馈',
    { type: 'warning', confirmButtonText: '重新打开' }
  )
  try {
    await feedbackApi.reopenFeedback(row.id)
    ElMessage.success('已重新打开，状态回到「处理中」')
    refresh()
  } catch {
    // ApiError 文案已可读，这里不再叠一层
  }
}

onMounted(refresh)
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
