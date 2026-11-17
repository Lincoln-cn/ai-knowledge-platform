<template>
  <div class="page">
    <PageHeader title="我的投诉" desc="我提交过的投诉与举报，以及它们的处理过程。">
      <template #actions>
        <el-button @click="load">刷新</el-button>
        <router-link to="/complaints">
          <el-button type="primary">提交新的投诉 / 举报</el-button>
        </router-link>
      </template>
    </PageHeader>

    <div class="filters panel">
      <el-select v-model="status" size="small" placeholder="全部状态" style="width: 130px" @change="load">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in COMPLAINT_STATUSES" :key="s" :label="COMPLAINT_STATUS_LABELS[s]" :value="s" />
      </el-select>
      <span class="micro muted">共 {{ total }} 条</span>
      <span class="grow" />
      <span class="micro muted">反馈时限从提交时间起算；超期未答复的可以在原单下追加说明。</span>
    </div>

    <div v-loading="loading" class="panel table-wrap">
      <el-table :data="list" style="width: 100%">
        <el-table-column prop="ticketNo" label="工单号" width="170" show-overflow-tooltip />
        <el-table-column label="类别" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.kind === 'REPORT' ? 'warning' : 'info'" effect="plain">
              {{ labelOf(COMPLAINT_KIND_LABELS, row.kind) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="status-cell" :class="statusClass(row.status)">
              <i class="dot" :class="statusClass(row.status)" />
              {{ labelOf(COMPLAINT_STATUS_LABELS, row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="反馈时限" width="150">
          <template #default="{ row }">
            <span :class="{ overdue: isOverdue(row) }">{{ fmtTime(row.dueAt) }}</span>
            <span v-if="isOverdue(row)" class="micro overdue note">已超期</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openDetail(row)">看过程</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div v-if="error" class="empty">
            <div class="empty-title">加载失败</div>
            <p class="micro muted">{{ error }}</p>
            <el-button size="small" type="primary" @click="load">重试</el-button>
          </div>
          <div v-else class="empty">
            <div class="empty-title">还没有提交过投诉</div>
            <p class="micro muted">
              对内容或服务有意见、发现违法信息时，
              <router-link to="/complaints">去投诉举报页提交</router-link>。
            </p>
          </div>
        </template>
      </el-table>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        @current-change="load"
      />
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
          <span class="k">状态</span>
          <span class="v">{{ labelOf(COMPLAINT_STATUS_LABELS, detail.status) }}</span>
        </div>
        <div class="detail-row">
          <span class="k">反馈时限</span>
          <span class="v" :class="{ overdue: isOverdue(detail) }">{{ fmtTime(detail.dueAt) }}</span>
        </div>
        <ComplaintThread v-if="detailVisible" :ticket-id="detail.id" @changed="refreshAfterChange" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { complianceApi } from '@/api'
import type { ComplaintStatus, ComplaintTicket } from '@/api/compliance'
import { ApiError } from '@/api/types'
import {
  COMPLAINT_KIND_LABELS,
  COMPLAINT_STATUSES,
  COMPLAINT_STATUS_LABELS,
  labelOf,
} from '@/constants/labels'
import PageHeader from '@/components/PageHeader.vue'
import ComplaintThread from '@/pages/complaints/components/ComplaintThread.vue'

const loading = ref(false)
const error = ref('')
const list = ref<ComplaintTicket[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const status = ref<'' | ComplaintStatus>('')

const detailVisible = ref(false)
const detail = ref<ComplaintTicket | null>(null)

function statusClass(s: ComplaintStatus): string {
  return s === 'RESOLVED' ? 'ok' : s === 'REJECTED' ? 'err' : s === 'PROCESSING' ? 'warn' : ''
}

function fmtTime(iso?: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : '—'
}

function isOverdue(t: ComplaintTicket): boolean {
  return t.status !== 'RESOLVED' && t.status !== 'REJECTED' && new Date(t.dueAt).getTime() < Date.now()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    // ★ 固定 mine=true：这一页就是"我提的那些"，与有没有办理权限无关
    const result = await complianceApi.listComplaints({
      mine: true,
      status: status.value || undefined,
      page: page.value,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
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
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-md);
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
