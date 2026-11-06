<template>
  <div class="page">
    <PageHeader title="投诉举报" desc="对平台内容或服务有意见、发现违法违规信息，在这里提交，我们会按公示的流程处理并反馈。">
      <template #actions>
        <el-button type="primary" @click="openCreate">提交投诉 / 举报</el-button>
      </template>
    </PageHeader>

    <!-- ★ 第十五条要件：公布处理流程与反馈时限（数字读合规设置，可配置） -->
    <div class="panel process">
      <div class="process-title">处理流程</div>
      <ol class="process-steps">
        <li><b>受理</b><span class="micro muted">提交后生成工单号，留痕可查</span></li>
        <li><b>办理</b><span class="micro muted">合规人员核查内容与记录，必要时停止生成 / 传输并消除（第十四条）</span></li>
        <li><b>反馈</b><span class="micro muted">在 <b class="num">{{ feedbackDays }}</b> 天内反馈处理结果</span></li>
      </ol>
      <div class="process-note micro muted">
        反馈时限以提交时间起算（工单的「反馈时限」列即截止日期）；超期未答复的，可在本页追加投诉。
      </div>
    </div>

    <div class="filters panel">
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜标题 / 内容 / 工单号"
        clearable
        style="width: 220px"
        @change="onFilterChange"
      />
      <el-select v-model="query.status" size="small" placeholder="全部状态" style="width: 130px" @change="onFilterChange">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in COMPLAINT_STATUSES" :key="s" :label="COMPLAINT_STATUS_LABELS[s]" :value="s" />
      </el-select>
      <el-select v-model="query.kind" size="small" placeholder="全部类别" style="width: 110px" @change="onFilterChange">
        <el-option label="全部类别" value="" />
        <el-option label="投诉" value="COMPLAINT" />
        <el-option label="举报" value="REPORT" />
      </el-select>
      <span class="micro muted">共 {{ total }} 条</span>
    </div>

    <div class="panel table-wrap" v-loading="loading">
      <el-table :data="list" style="width: 100%">
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
        <el-table-column prop="createdAt" label="提交时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="反馈时限" width="160">
          <template #default="{ row }">
            <span :class="{ overdue: isOverdue(row) }">{{ fmtTime(row.dueAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <div class="ops">
              <el-button size="small" link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button
                v-if="canHandle && (row.status === 'SUBMITTED' || row.status === 'PROCESSING')"
                size="small"
                link
                type="primary"
                @click="openHandle(row)"
              >办理</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div v-if="error" class="empty">
            <div class="empty-title">加载失败</div>
            <p class="micro muted">{{ error }}</p>
            <el-button size="small" type="primary" @click="onFilterChange">重试</el-button>
          </div>
          <div v-else class="empty">
            <div class="empty-title">暂无工单</div>
            <p class="micro muted">点击右上角「提交投诉 / 举报」即可创建工单</p>
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
        @current-change="loadList"
      />
    </div>

    <!-- 提交对话框 -->
    <el-dialog v-model="createVisible" title="提交投诉 / 举报" width="560px">
      <el-form label-width="72px" class="form">
        <el-form-item label="类别">
          <el-radio-group v-model="createBody.kind">
            <el-radio-button value="COMPLAINT">投诉</el-radio-button>
            <el-radio-button value="REPORT">举报</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="createBody.subject" maxlength="120" placeholder="一句话说明问题" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="createBody.content"
            type="textarea"
            :rows="5"
            maxlength="2000"
            placeholder="发生了什么、涉及哪个会话 / 文档；举报违法违规信息请注明位置，便于我们核查与保存记录"
          />
        </el-form-item>
        <el-form-item label="联系方式">
          <el-input v-model="createBody.contact" maxlength="120" placeholder="邮箱或电话（选填，便于反馈处理结果）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="createLoading" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailVisible" :title="detail ? detail.ticketNo : '工单详情'" width="620px">
      <div v-if="detail" class="detail">
        <div class="detail-row"><span class="k">标题</span><span class="v">{{ detail.subject }}</span></div>
        <div class="detail-row"><span class="k">类别</span><span class="v">{{ COMPLAINT_KIND_LABELS[detail.kind] }}</span></div>
        <div class="detail-row"><span class="k">状态</span><span class="v">{{ COMPLAINT_STATUS_LABELS[detail.status] }}</span></div>
        <div class="detail-row"><span class="k">提交人</span><span class="v">{{ detail.requesterName }}（{{ fmtTime(detail.createdAt) }}）</span></div>
        <div class="detail-row"><span class="k">反馈时限</span><span class="v">{{ fmtTime(detail.dueAt) }}</span></div>
        <div class="detail-row"><span class="k">内容</span><span class="v pre">{{ detail.content }}</span></div>
        <div v-if="detail.handlerNote" class="detail-row">
          <span class="k">处理说明</span>
          <span class="v pre">{{ detail.handlerNote }}<span class="muted micro">（{{ detail.handlerName }} · {{ fmtTime(detail.handledAt ?? detail.updatedAt) }}）</span></span>
        </div>
      </div>
      <template #footer>
        <el-button size="small" type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 办理对话框 -->
    <el-dialog v-model="handleVisible" title="办理工单" width="560px">
      <el-form label-width="88px" class="form">
        <el-form-item label="目标状态">
          <el-radio-group v-model="handleBody.status">
            <el-radio-button v-for="s in nextStatuses" :key="s" :value="s">{{ COMPLAINT_STATUS_LABELS[s] }}</el-radio-button>
          </el-radio-group>
          <div class="hint micro">办结 / 不予受理是终态，必填处理说明；状态只往前走，不能回退。</div>
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
import type { ComplaintKind, ComplaintPatchBody, ComplaintStatus, ComplaintTicket } from '@/api/compliance'
import { ApiError } from '@/api/types'
import { PERM } from '@/constants/permissions'
import {
  COMPLAINT_KIND_LABELS,
  COMPLAINT_STATUSES,
  COMPLAINT_STATUS_LABELS,
  labelOf,
} from '@/constants/labels'
import { useSessionStore } from '@/stores/session'
import PageHeader from '@/components/PageHeader.vue'

const session = useSessionStore()
const canHandle = computed(() => session.can(PERM.complaintHandle))

const loading = ref(false)
const error = ref('')
const list = ref<ComplaintTicket[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const query = reactive({ keyword: '', status: '' as '' | ComplaintStatus, kind: '' as '' | ComplaintKind })

const feedbackDays = ref(15)

const createVisible = ref(false)
const createLoading = ref(false)
const createBody = reactive({ kind: 'COMPLAINT' as ComplaintKind, subject: '', content: '', contact: '' })

const detailVisible = ref(false)
const detail = ref<ComplaintTicket | null>(null)

const handleVisible = ref(false)
const handleLoading = ref(false)
const handleTarget = ref<ComplaintTicket | null>(null)
const handleBody = reactive({ status: 'PROCESSING' as ComplaintStatus, handlerNote: '' })

/** 状态流转表：与后端 ComplaintStatus.canTransitionTo 同步（只往前走）。 */
const TRANSITIONS: Record<ComplaintStatus, ComplaintStatus[]> = {
  SUBMITTED: ['PROCESSING', 'REJECTED'],
  PROCESSING: ['RESOLVED', 'REJECTED'],
  RESOLVED: [],
  REJECTED: []
}
const nextStatuses = computed(() =>
  handleTarget.value ? TRANSITIONS[handleTarget.value.status] : []
)

function statusClass(s: ComplaintStatus): string {
  return s === 'RESOLVED' ? 'ok' : s === 'REJECTED' ? 'err' : s === 'PROCESSING' ? 'warn' : ''
}

function fmtTime(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : '—'
}

function isOverdue(t: ComplaintTicket): boolean {
  return t.status !== 'RESOLVED' && t.status !== 'REJECTED' && new Date(t.dueAt).getTime() < Date.now()
}

async function loadSettings() {
  try {
    const settings = await complianceApi.getComplianceSettings()
    feedbackDays.value = settings.complaintFeedbackDays
  } catch {
    // 设置读不到时保留缺省 15 天 —— 公示数字不能空着（与后端 DEFAULT_COMPLAINT_FEEDBACK_DAYS 一致）
    feedbackDays.value = 15
  }
}

async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const result = await complianceApi.listComplaints({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      kind: query.kind || undefined,
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

function onFilterChange() {
  page.value = 1
  loadList()
}

function openCreate() {
  createBody.kind = 'COMPLAINT'
  createBody.subject = ''
  createBody.content = ''
  createBody.contact = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!createBody.subject.trim() || !createBody.content.trim()) {
    ElMessage.warning('标题与内容不能为空')
    return
  }
  createLoading.value = true
  try {
    const ticket = await complianceApi.createComplaint({ ...createBody })
    createVisible.value = false
    ElMessage.success(`已受理，工单号 ${ticket.ticketNo}，我们会在 ${feedbackDays.value} 天内反馈`)
    loadList()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '提交失败，请重试')
  } finally {
    createLoading.value = false
  }
}

function openDetail(row: ComplaintTicket) {
  detail.value = row
  detailVisible.value = true
}

function openHandle(row: ComplaintTicket) {
  // ★ 浅拷贝而不是直接存引用：Vue 的 ref 对「同引用赋值」不触发依赖更新，
  // 而 PATCH 是原地改 mock/后端对象 —— 同一工单第二次点「办理」时 nextStatuses
  // 会停留在第一次计算的旧转移表上（radio 选项不刷新）。真踩过。
  handleTarget.value = { ...row }
  handleBody.status = TRANSITIONS[row.status][0] ?? 'PROCESSING'
  handleBody.handlerNote = ''
  handleVisible.value = true
}

async function submitHandle() {
  const target = handleTarget.value
  if (!target) return
  const terminal = handleBody.status === 'RESOLVED' || handleBody.status === 'REJECTED'
  if (terminal && !handleBody.handlerNote.trim()) {
    ElMessage.warning('办结或不予受理时必须填写处理说明')
    return
  }
  handleLoading.value = true
  try {
    const body: ComplaintPatchBody = { status: handleBody.status, handlerNote: handleBody.handlerNote.trim() }
    await complianceApi.patchComplaint(target.id, body)
    handleVisible.value = false
    ElMessage.success('已保存办理记录')
    loadList()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '办理失败，请重试')
  } finally {
    handleLoading.value = false
  }
}

onMounted(() => {
  loadSettings()
  loadList()
})
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
}

.panel {
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
}

.process {
  padding: var(--space-sm) var(--space-md);
  box-shadow: inset 3px 0 0 var(--color-accent);
}

.process-title {
  font-family: var(--font-display);
  font-size: var(--text-lg);
  line-height: var(--leading-display);
  color: var(--color-ink);
}

.process-steps {
  display: flex;
  gap: var(--space-md);
  margin: var(--space-xs) 0;
  padding-left: var(--space-md);
}

.process-steps li {
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
  line-height: var(--leading-body);
}

.process-steps b {
  color: var(--color-ink-2);
}

.process-note {
  margin-top: var(--space-3xs);
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.table-wrap {
  padding: 0;
}

.ops {
  display: flex;
  gap: var(--space-3xs);
}

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3xs);
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.status-cell.ok {
  color: var(--color-ok);
}

.status-cell.warn {
  color: var(--color-warn);
}

.status-cell.err {
  color: var(--color-danger);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-muted);
}

.dot.ok {
  background: var(--color-ok);
}

.dot.warn {
  background: var(--color-warn);
}

.dot.err {
  background: var(--color-danger);
}

.overdue {
  color: var(--color-danger);
}

.empty {
  padding: var(--space-xl) var(--space-md);
  text-align: center;
}

.empty-title {
  margin-bottom: var(--space-3xs);
  color: var(--color-ink-2);
}

.pager {
  display: flex;
  justify-content: flex-end;
}

.form .hint {
  margin-top: var(--space-3xs);
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.detail-row {
  display: flex;
  gap: var(--space-sm);
  line-height: var(--leading-body);
}

.detail-row .k {
  flex: 0 0 72px;
  color: var(--color-muted);
  font-size: var(--text-2xs);
}

.detail-row .v {
  flex: 1;
  color: var(--color-ink-2);
  font-size: var(--text-xs);
}

.detail-row .v.pre {
  white-space: pre-wrap;
}

.micro {
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.muted {
  color: var(--color-muted);
}

.num {
  font-family: var(--font-display);
}
</style>
