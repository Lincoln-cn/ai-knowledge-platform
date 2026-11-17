<template>
  <div class="page">
    <PageHeader title="投诉待办" desc="还没办结的投诉与举报，按反馈时限排——最该先办的排最前。">
      <template #actions>
        <el-button @click="load">刷新</el-button>
        <router-link to="/complaints">
          <el-button>看全量台账</el-button>
        </router-link>
      </template>
    </PageHeader>

    <div class="filters panel">
      <el-radio-group v-model="onlyOverdue" size="small">
        <el-radio-button :value="false">全部待办（{{ list.length }}）</el-radio-button>
        <el-radio-button :value="true">只看超期（{{ overdueCount }}）</el-radio-button>
      </el-radio-group>
      <span class="grow" />
      <span class="micro muted">
        ★ 这一页不含已办结的单；已办结的到「投诉举报」页按状态筛。
      </span>
    </div>

    <div v-loading="loading" class="panel table-wrap">
      <el-table :data="shown" style="width: 100%">
        <el-table-column prop="ticketNo" label="工单号" width="170" show-overflow-tooltip />
        <el-table-column label="类别" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.kind === 'REPORT' ? 'warning' : 'info'" effect="plain">
              {{ labelOf(COMPLAINT_KIND_LABELS, row.kind) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="status-cell" :class="statusClass(row.status)">
              <i class="dot" :class="statusClass(row.status)" />
              {{ labelOf(COMPLAINT_STATUS_LABELS, row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="requesterName" label="提交人" width="90" show-overflow-tooltip />
        <el-table-column label="反馈时限" width="170">
          <template #default="{ row }">
            <span :class="{ overdue: isOverdue(row) }">{{ fmtTime(row.dueAt) }}</span>
            <span v-if="isOverdue(row)" class="micro overdue note">已超期 {{ daysLate(row) }} 天</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="ops">
              <el-button size="small" link type="primary" @click="openDetail(row)">看过程</el-button>
              <el-button size="small" link type="primary" @click="openHandle(row)">办理</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div v-if="error" class="empty">
            <div class="empty-title">加载失败</div>
            <p class="micro muted">{{ error }}</p>
            <el-button size="small" type="primary" @click="load">重试</el-button>
          </div>
          <div v-else class="empty">
            <div class="empty-title">没有待办</div>
            <p class="micro muted">所有提交过的单都已办结。</p>
          </div>
        </template>
      </el-table>
    </div>

    <el-dialog v-model="detailVisible" :title="detail ? detail.ticketNo : '工单详情'" width="640px">
      <div v-if="detail" class="detail">
        <div class="detail-row">
          <span class="k">标题</span><span class="v">{{ detail.subject }}</span>
        </div>
        <div class="detail-row">
          <span class="k">提交内容</span><span class="v pre">{{ detail.content }}</span>
        </div>
        <div class="detail-row">
          <span class="k">提交人</span><span class="v">{{ detail.requesterName }}</span>
        </div>
        <div class="detail-row">
          <span class="k">反馈时限</span>
          <span class="v" :class="{ overdue: isOverdue(detail) }">{{ fmtTime(detail.dueAt) }}</span>
        </div>
        <ComplaintThread v-if="detailVisible" :ticket-id="detail.id" @changed="refreshAfterChange" />
      </div>
      <template #footer>
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="detail && (detail.status === 'SUBMITTED' || detail.status === 'PROCESSING')"
          size="small"
          type="primary"
          @click="openHandle(detail)"
        >办理</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="handleVisible" title="办理工单" width="560px">
      <el-form label-position="top" class="form">
        <el-form-item label="目标状态">
          <el-radio-group v-model="handleBody.status">
            <el-radio-button v-for="s in nextStatuses" :key="s" :value="s">
              {{ COMPLAINT_STATUS_LABELS[s] }}
            </el-radio-button>
          </el-radio-group>
          <div class="hint micro">
            办结 / 不予受理是终态，必填处理说明；状态只往前走，不能回退。每次流转都会在时间线里留一条。
          </div>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input
            v-model="handleBody.handlerNote"
            type="textarea"
            :rows="4"
            maxlength="1000"
            placeholder="办结结果或不予受理理由（会作为留痕展示给提交人）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="handleVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="handleLoading" @click="submitHandle">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { complianceApi } from '@/api'
import type { ComplaintPatchBody, ComplaintStatus, ComplaintTicket } from '@/api/compliance'
import { ApiError } from '@/api/types'
import {
  COMPLAINT_KIND_LABELS,
  COMPLAINT_STATUS_LABELS,
  labelOf,
} from '@/constants/labels'
import PageHeader from '@/components/PageHeader.vue'
import ComplaintThread from '@/pages/complaints/components/ComplaintThread.vue'

const loading = ref(false)
const error = ref('')
const list = ref<ComplaintTicket[]>([])
const onlyOverdue = ref(false)

const detailVisible = ref(false)
const detail = ref<ComplaintTicket | null>(null)

const handleVisible = ref(false)
const handleLoading = ref(false)
const handleTarget = ref<ComplaintTicket | null>(null)
const handleBody = reactive({ status: 'PROCESSING' as ComplaintStatus, handlerNote: '' })

/** 状态流转表：与后端 `ComplaintStatus.canTransitionTo` 同步（只往前走）。 */
const TRANSITIONS: Record<ComplaintStatus, ComplaintStatus[]> = {
  SUBMITTED: ['PROCESSING', 'REJECTED'],
  PROCESSING: ['RESOLVED', 'REJECTED'],
  RESOLVED: [],
  REJECTED: []
}
const nextStatuses = computed(() =>
  handleTarget.value ? TRANSITIONS[handleTarget.value.status] : []
)

const overdueCount = computed(() => list.value.filter(isOverdue).length)
const shown = computed(() => (onlyOverdue.value ? list.value.filter(isOverdue) : list.value))

function statusClass(s: ComplaintStatus): string {
  return s === 'RESOLVED' ? 'ok' : s === 'REJECTED' ? 'err' : s === 'PROCESSING' ? 'warn' : ''
}

function fmtTime(iso?: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : '—'
}

function isOverdue(t: ComplaintTicket): boolean {
  return t.status !== 'RESOLVED' && t.status !== 'REJECTED' && new Date(t.dueAt).getTime() < Date.now()
}

function daysLate(t: ComplaintTicket): number {
  return Math.max(1, Math.floor((Date.now() - new Date(t.dueAt).getTime()) / 86400_000))
}

/**
 * 取待办：后端一次只收一个 status，所以两次请求再合并。
 *
 * ★ 不用"取全部再前端筛"：分页在后端做，筛完当页可能空而 total 还在，页面会显示"一条没有"却写着"共 40 条"。
 * ★ 合并后按 dueAt 升序 —— 这一页的排序语义是"最该先办的最前"，不是"最新的最前"。
 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [submitted, processing] = await Promise.all([
      complianceApi.listComplaints({ status: 'SUBMITTED', page: 1, size: 50 }),
      complianceApi.listComplaints({ status: 'PROCESSING', page: 1, size: 50 })
    ])
    list.value = [...submitted.list, ...processing.list].sort(
      (a, b) => new Date(a.dueAt).getTime() - new Date(b.dueAt).getTime()
    )
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

function openDetail(row: ComplaintTicket) {
  detail.value = row
  detailVisible.value = true
}

function openHandle(row: ComplaintTicket) {
  handleTarget.value = row
  handleBody.status = TRANSITIONS[row.status][0] ?? 'PROCESSING'
  handleBody.handlerNote = ''
  handleVisible.value = true
}

async function submitHandle() {
  if (!handleTarget.value) return
  const target = handleBody.status
  const note = handleBody.handlerNote.trim()
  if ((target === 'RESOLVED' || target === 'REJECTED') && !note) {
    ElMessage.warning('办结或不予受理时必须填写处理说明')
    return
  }
  const body: ComplaintPatchBody = { status: target, handlerNote: note }
  handleLoading.value = true
  try {
    await complianceApi.patchComplaint(handleTarget.value.id, body)
    ElMessage.success('已保存')
    handleVisible.value = false
    detailVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败，请重试')
  } finally {
    handleLoading.value = false
  }
}

async function refreshAfterChange() {
  await load()
  if (detail.value) {
    try {
      detail.value = await complianceApi.getComplaint(detail.value.id)
    } catch {
      // 详情读不回来时保留旧值：时间线刚刷过，页面还有内容可看
    }
  }
}

onMounted(load)
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.grow {
  flex: 1;
}
.table-wrap {
  padding: 0;
}
.ops {
  display: flex;
  gap: var(--space-2xs);
}
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-text-secondary);
}
.dot.ok {
  background: var(--color-success);
}
.dot.warn {
  background: var(--color-warning);
}
.dot.err {
  background: var(--color-danger);
}
.overdue {
  color: var(--color-danger);
}
.note {
  margin-left: 6px;
}
.empty {
  padding: var(--space-lg) 0;
  text-align: center;
}
.empty-title {
  font-weight: 600;
  margin-bottom: var(--space-2xs);
}
.detail-row {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: var(--space-sm);
  padding: var(--space-2xs) 0;
  font-size: 13px;
}
.detail-row .k {
  color: var(--color-text-secondary);
}
.detail-row .v {
  min-width: 0;
}
.pre {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
