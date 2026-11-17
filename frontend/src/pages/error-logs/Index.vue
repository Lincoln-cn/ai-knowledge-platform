<template>
  <div class="page">
    <PageHeader
      title="错误日志"
      desc="回答失败、文档解析与向量化失败的逐条原因；读的是产生失败的那张表，不是另一套日志"
    >
      <template #actions>
        <el-radio-group v-model="days" size="small" @change="reload">
          <el-radio-button :value="1">近 24 小时</el-radio-button>
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </template>
    </PageHeader>

    <div v-if="error" class="notice err">
      <b>加载失败：</b>{{ error }}
      <el-button size="small" type="primary" plain class="retry" @click="reload">重试</el-button>
    </div>

    <div class="chips">
      <button
        v-for="chip in chips"
        :key="chip.key"
        type="button"
        class="chip"
        :class="{ active: sourceFilter === chip.key }"
        @click="toggleSource(chip.key)"
      >
        <span class="chip-label">{{ chip.label }}</span>
        <span class="chip-value num">{{ chip.count }}</span>
      </button>
      <button v-if="sourceFilter" type="button" class="chip clear" @click="toggleSource(sourceFilter)">
        清除筛选
      </button>
    </div>

    <section class="panel block">
      <el-table
        v-loading="loading"
        :data="rows"
        size="small"
        row-key="rowId"
        empty-text="这段时间没有失败记录 —— 不等于系统没出错（未捕获异常不落库，见页脚）"
      >
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.at) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'CHAT' ? 'danger' : 'warning'" disable-transitions>
              {{ sourceLabel(row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="200">
          <template #default="{ row }">
            <el-tooltip :content="row.ref" placement="top">
              <span class="ellipsis">{{ row.title }}</span>
            </el-tooltip>
            <div class="micro muted mono-ref">{{ shortId(row.ref) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="320">
          <template #default="{ row }">
            <div class="reason">{{ row.reason }}</div>
            <div v-if="row.hint" class="micro muted hint">{{ row.hint }}</div>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next, total"
          small
          background
          @current-change="fetchPage"
        />
      </div>
    </section>

    <p class="foot micro muted">
      ★ 这里<b>不含</b>未捕获异常（接口 500 的堆栈）：那要集中式日志采集，属于 <code>#73</code> 的可观测那笔账，
      今天做只能做出一个「只在一台机器上、重启就没了」的假日志。所以本页为空<b>不代表</b>系统没出错。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { opsZoneApi } from '@/api'
import type { ErrorLogEntry, ErrorSource } from '@/api/ops-zone'

/** ★ 单点定义：表格与计数条必须说同一句话。 */
const SOURCE_META: Record<ErrorSource, string> = {
  CHAT: '回答',
  DOCUMENT: '文档'
}

const days = ref(7)
const sourceFilter = ref('')
const page = ref(1)
const size = 20
const rows = ref<ErrorLogEntry[]>([])
const total = ref(0)
const bySource = ref<{ source: ErrorSource; count: number }[]>([])
const loading = ref(false)
const error = ref('')

const chips = computed(() =>
  (['CHAT', 'DOCUMENT'] as ErrorSource[]).map((key) => ({
    key,
    label: SOURCE_META[key],
    count: bySource.value.find((one) => one.source === key)?.count ?? 0
  }))
)

const sourceLabel = (value: string) => SOURCE_META[value as ErrorSource] ?? value
const formatTime = (iso: string) => {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN')
}
const shortId = (id: string) => (id.length > 12 ? id.slice(0, 8) + '…' : id)

function toggleSource(key: string) {
  sourceFilter.value = sourceFilter.value === key ? '' : key
  page.value = 1
  void fetchPage()
}

async function fetchPage() {
  loading.value = true
  try {
    const data = await opsZoneApi.getErrorLogs({
      days: days.value,
      source: sourceFilter.value || undefined,
      page: page.value,
      size
    })
    rows.value = data.list
    total.value = data.total
    bySource.value = data.bySource
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  void fetchPage()
}

onMounted(fetchPage)
</script>

<style scoped>
.notice {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  font-size: 13px;
  margin-bottom: var(--space-md);
}
.notice.err {
  background: var(--color-danger-bg, #fef2f2);
  color: var(--color-danger, #b91c1c);
}
.retry {
  margin-left: auto;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}
.chip {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-elevated);
  cursor: pointer;
  font-size: 13px;
}
.chip.active {
  border-color: var(--color-primary);
  box-shadow: inset 0 0 0 1px var(--color-primary);
}
.chip-label {
  color: var(--color-text-secondary);
}
.chip-value {
  font-weight: 600;
  font-size: 15px;
}
.chip.clear {
  color: var(--color-text-secondary);
}
.block {
  padding: var(--space-md);
}
.ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mono-ref {
  font-family: var(--font-mono, monospace);
}
.reason {
  font-size: 13px;
  line-height: 1.6;
}
.hint {
  margin-top: 2px;
  line-height: 1.6;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-sm);
}
.foot {
  margin-top: var(--space-sm);
  line-height: 1.6;
}
</style>
