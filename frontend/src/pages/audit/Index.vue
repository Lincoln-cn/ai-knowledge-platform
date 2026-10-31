<template>
  <div class="page">
    <PageHeader
      title="审计记录"
      desc="谁在什么时候改了什么。只读 —— 审计记录只能追加，不能改也不能删。"
    >
      <template #actions>
        <el-button
          v-if="canPurge"
          size="small"
          :loading="purging"
          @click="startPurge"
        >清理过期记录</el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <!-- ★ 这一条不是装饰：审计只接了一半的写入点（13 §7.3），页面必须说明，
         否则读到 0 条的人会以为是"没人操作过"，而真实原因可能是"这类动作还没接"。 -->
    <div class="notice info">
      <b>今天接了 {{ ACTIONS.length }} 类动作：</b>改密级 · 改标签 · 删文档 · 从回收站恢复 · 彻底删除 · 导出应用 ·
      改角色权限 · 分享链接 · 撤销分享 · 清理过期审计（这一条由系统写，操作人一列显示「系统」）。
      检索 / 查看 / 引用<b>还没有接审计</b>（<code>13 §7.3</code>），所以这几类操作在这里查不到。
    </div>

    <div class="filters panel">
      <el-select
        v-model="query.action"
        size="small"
        placeholder="全部动作"
        style="width: 190px"
        @change="onFilterChange"
      >
        <el-option label="全部动作" value="" />
        <el-option
          v-for="a in ACTIONS"
          :key="a.value"
          :label="a.label"
          :value="a.value"
        />
      </el-select>
      <el-select
        v-model="query.resourceType"
        size="small"
        placeholder="全部资源"
        style="width: 150px"
        @change="onFilterChange"
      >
        <el-option label="全部资源" value="" />
        <el-option v-for="r in RESOURCES" :key="r.value" :label="r.label" :value="r.value" />
      </el-select>
      <el-input
        v-model="query.resourceId"
        size="small"
        placeholder="资源 id（可选）"
        style="width: 240px"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-input
        v-model="query.actorId"
        size="small"
        placeholder="操作人 id（可选）"
        style="width: 240px"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <span class="micro muted">共 {{ total }} 条</span>
      <span class="grow" />
    </div>

    <!-- ★ 错误态是硬闸（check_states.py）：拉不到数据时必须说清"是接口失败"，而不是显示空表 -->
    <div v-if="loadError" class="notice err">
      <b>审计记录没读出来。</b>{{ loadError }}
      <el-button link type="primary" size="small" @click="refresh">重试</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" class="panel">
      <el-table-column prop="at" label="时间" width="180">
        <template #default="{ row }">{{ formatTime(row.at) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="140">
        <template #default="{ row }">
          <!-- ★ 是写那一刻的快照：与用户管理页里现在的名字可能不同，这正是审计要的 -->
          {{ actorText(row.actorName) }}
        </template>
      </el-table-column>
      <el-table-column label="动作" width="170">
        <template #default="{ row }">{{ actionLabel(row.action) }}</template>
      </el-table-column>
      <el-table-column label="资源" min-width="220">
        <template #default="{ row }">
          <span class="micro muted">{{ resourceLabel(row.resourceType) }}</span>
          {{ row.resourceId }}
        </template>
      </el-table-column>
      <el-table-column label="明细" min-width="260">
        <template #default="{ row }">
          <span class="detail">{{ detailText(row.detail) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" @click="openTrace(row)">这个资源</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty">
          <div class="empty-title">当前还没有任何审计记录</div>
          <!-- ★ 零记录本身是一条信息，比空白表格有用（#258 判据 4） -->
          <div class="micro muted">
            这有两种可能：还没有人做过上面那 {{ ACTIONS.length }} 类操作，或者写入点没接上 ——
            做一次「改密级」再看这里，如果仍然没有，那是接错了。
          </div>
        </div>
      </template>
    </el-table>

    <!-- ★ 追溯：某个资源的**全部**记录（"这份文档到底被谁改过"）。
         它消费的是 `GET /audit-logs/by-resource` —— 否则那条端点就只是契约里的一行字。 -->
    <el-dialog v-model="traceOpen" title="这个资源的全部记录" width="720px">
      <p class="micro muted">
        {{ traceType }} / {{ traceId }}
      </p>
      <el-table v-loading="traceLoading" :data="traceRows" size="small">
        <el-table-column prop="at" label="时间" width="180" />
        <el-table-column label="操作人" width="120">
          <template #default="{ row }">{{ actorText(row.actorName) }}</template>
        </el-table-column>
        <el-table-column label="动作" width="150">
          <template #default="{ row }">{{ actionLabel(row.action) }}</template>
        </el-table-column>
        <el-table-column label="明细" min-width="220">
          <template #default="{ row }">{{ detailText(row.detail) }}</template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="empty-title">这个资源还没有审计记录</div>
            <div class="micro muted">
              可能是它从没被改过，也可能是改动走的是还没接审计的动作（检索 / 查看 / 引用）。
            </div>
          </div>
        </template>
      </el-table>
    </el-dialog>

    <div class="pager">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="prev, pager, next, total"
        size="small"
        background
        @current-change="load"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { auditApi } from '@/api'
import type { AuditRecord } from '@/api/audit'

/** 只列**已经接线**的七类（与后端 AuditAction 一致）。没接的不列 —— 列了会被读成"能查到"。 */
const ACTIONS = [
  { value: 'document.reclassify', label: '改文档密级' },
  { value: 'document.relabel', label: '改文档标签' },
  { value: 'conversation.share', label: '生成分享链接' },
  { value: 'conversation.unshare', label: '撤销分享链接' },
  { value: 'document.delete', label: '删文档' },
  { value: 'document.purge', label: '彻底删除' },
  { value: 'document.restore', label: '从回收站恢复' },
  { value: 'data.export', label: '导出应用' },
  { value: 'grant.change', label: '改角色权限' },
  { value: 'audit.purge', label: '清理过期审计' },
]

const RESOURCES = [
  { value: 'DOCUMENT', label: '文档' },
  { value: 'ASSISTANT', label: '应用' },
  { value: 'ROLE', label: '角色' },
  { value: 'CONVERSATION', label: '会话' },
  { value: 'AUDIT_LOG', label: '审计记录' },
]

const session = useSessionStore()
const rows = ref<AuditRecord[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

const query = reactive({
  action: '',
  resourceType: '',
  resourceId: '',
  // ★ 按人查：拿的是 iam_user.id（不是用户名）—— 名字会改，id 不会；与表里「操作人」那一列
  //   显示的快照名字是两件事，所以输入框的占位写的是「操作人 id」
  actorId: '',
  page: 1,
  size: 20,
})

const actionLabel = (code: string) => ACTIONS.find((a) => a.value === code)?.label ?? code

/**
 * 操作人一列的文字。
 *
 * ★ 后端对"系统自己做的事"（留存期清理）写 `actor_id = null`、`actor_name = null`；
 * 用户被删掉之后快照名还在，所以**空名字只可能来自系统**。
 * ★ 两处渲染必须走同一个函数：早先一处用 `||`、一处用 `??`，空串在两者下结果不同 ——
 * 同一张表在列表与追溯对话框里显示不一样，是最难被报上来的那类不一致。
 */
const actorText = (name: string | null | undefined) => name?.trim() || '（系统）'
const resourceLabel = (type: string) => RESOURCES.find((r) => r.value === type)?.label ?? type

const formatTime = (iso: string) => {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN')
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const page = await auditApi.listAuditLogs({
      action: query.action || undefined,
      resourceType: query.resourceType || undefined,
      resourceId: query.resourceId.trim() || undefined,
      actorId: query.actorId.trim() || undefined,
      page: query.page,
      size: query.size,
    })
    rows.value = page.list
    total.value = page.total
  } catch (e) {
    // ★ 不吞：清空列表会让"接口挂了"看起来像"没有记录"
    rows.value = []
    total.value = 0
    loadError.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

const detailText = (detail: unknown) => {
  if (detail == null || detail === '') return '—'
  if (typeof detail === 'string') return detail
  try {
    return JSON.stringify(detail)
  } catch {
    return String(detail)
  }
}

/* ── 追溯：一个资源的全部记录（消费 `GET /audit-logs/by-resource`） ── */
const traceOpen = ref(false)
const traceLoading = ref(false)
const traceRows = ref<AuditRecord[]>([])
const traceType = ref('')
const traceId = ref('')

async function openTrace(row: AuditRecord) {
  traceType.value = row.resourceType
  traceId.value = row.resourceId
  traceRows.value = []
  traceOpen.value = true
  traceLoading.value = true
  try {
    traceRows.value = await auditApi.listAuditLogsByResource(row.resourceType, row.resourceId)
  } catch (e) {
    traceRows.value = []
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    traceLoading.value = false
  }
}

/* ── 留存期清理：先试算再删（消费 `POST /audit-logs/purge`） ── */
const purging = ref(false)
const canPurge = computed(() => session.can(PERM.systemManage))

async function startPurge() {
  let input: string
  try {
    const res = await ElMessageBox.prompt(
      '清理这个时间点之前的审计记录（ISO 日期，例如 2026-01-01）。' +
        '会先试算有多少条，再让你确认 —— 审计只能追加，删掉不可恢复。',
      '清理过期审计记录',
      { confirmButtonText: '试算', cancelButtonText: '取消', inputPlaceholder: '2026-01-01' }
    )
    input = res.value
  } catch {
    return
  }
  const beforeAt = new Date(`${input.trim()}T00:00:00Z`)
  if (Number.isNaN(beforeAt.getTime())) {
    ElMessage.error('日期格式不对，请用 YYYY-MM-DD')
    return
  }
  purging.value = true
  try {
    const dry = await auditApi.purgeAuditLogs(beforeAt.toISOString(), true)
    if (dry.candidates === 0) {
      ElMessage.info('这个时间点之前没有记录，不需要清理')
      return
    }
    await ElMessageBox.confirm(
      `将删除 ${dry.candidates} 条（截止 ${input.trim()}）。清理本身会写一条审计（audit.purge）。确认？`,
      '确认清理',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    const done = await auditApi.purgeAuditLogs(beforeAt.toISOString(), false)
    ElMessage.success(`已清理 ${done.deleted} 条`)
    await load()
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error)
    // 用户点"取消"会走到这里：ElMessageBox 的取消以 reject 表达，不当错误报
    if (reason.includes('cancel') || reason.includes('关闭')) return
    ElMessage.error(reason)
  } finally {
    purging.value = false
  }
}

function onFilterChange() {
  query.page = 1
  void load()
}

function refresh() {
  void load()
}

onMounted(load)
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  margin-bottom: 12px;
}
.grow {
  flex: 1;
}
.detail {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  word-break: break-all;
}
.empty {
  padding: 18px 0;
}
.empty-title {
  font-size: 14px;
  margin-bottom: 6px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
