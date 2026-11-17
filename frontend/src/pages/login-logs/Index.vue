<template>
  <div class="page">
    <PageHeader
      title="登录日志"
      desc="谁在什么时候、从哪个地址、以什么结果进门。成功与被拒都记，只增不改"
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

    <!-- 各结果计数：一眼看出"今天被试了多少次" -->
    <div class="chips">
      <button
        v-for="chip in chips"
        :key="chip.key"
        type="button"
        class="chip"
        :class="[chip.tone, { active: resultFilter === chip.key }]"
        @click="toggleResult(chip.key)"
      >
        <span class="chip-label">{{ chip.label }}</span>
        <span class="chip-value num">{{ chip.count }}</span>
      </button>
      <button
        v-if="resultFilter"
        type="button"
        class="chip clear"
        @click="toggleResult(resultFilter)"
      >
        清除筛选
      </button>
    </div>

    <section class="panel block">
      <div class="filters">
        <el-input
          v-model="keyword"
          placeholder="按用户名筛选"
          clearable
          size="small"
          class="kw"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-button size="small" @click="reload">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" size="small" row-key="id" empty-text="这段时间没有登录记录">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.at) }}</template>
        </el-table-column>
        <el-table-column label="用户名" min-width="140">
          <template #default="{ row }">
            <span>{{ row.username }}</span>
            <!-- ★ 账号不存在时 userId 为空，用一句说明代替"查无此人"的空白 -->
            <span v-if="!row.userId" class="tag-note">账号不存在</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="resultTone(row.result)" disable-transitions>
              {{ resultLabel(row.result) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="IP" width="140">
          <template #default="{ row }">
            <span class="mono">{{ row.ip || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="客户端" min-width="200">
          <template #default="{ row }">
            <el-tooltip v-if="row.userAgent" :content="row.userAgent" placement="top">
              <span class="ua">{{ row.userAgent }}</span>
            </el-tooltip>
            <span v-else class="muted">—</span>
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
      这张表只增不改，没有删除入口，也没有「清理」按钮 —— 被清过的登录证据比没有更糟：
      事后无法判断是「没记录」还是「被删过」。保留期待定（等保要求 6 个月，见 <code>#75</code>）。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { opsZoneApi } from '@/api'
import type { LoginLogEntry, LoginResult } from '@/api/ops-zone'

/** 结果的中文与色调。★ 单点定义：表格与计数条必须说同一句话（各写一份必然漂移）。 */
const RESULT_META: Record<LoginResult, { label: string; tone: 'ok' | 'bad' | 'warn' }> = {
  SUCCESS: { label: '成功', tone: 'ok' },
  BAD_CREDENTIALS: { label: '密码错误', tone: 'bad' },
  LOCKED: { label: '已锁定', tone: 'bad' },
  DISABLED: { label: '已停用', tone: 'warn' },
  CAPTCHA: { label: '验证码错误', tone: 'warn' }
}

const CHIP_ORDER: LoginResult[] = ['SUCCESS', 'BAD_CREDENTIALS', 'CAPTCHA', 'LOCKED', 'DISABLED']

const days = ref(7)
const keyword = ref('')
const resultFilter = ref('')
const page = ref(1)
const size = 20
const rows = ref<LoginLogEntry[]>([])
const total = ref(0)
const counts = ref<{ result: LoginResult; count: number }[]>([])
const loading = ref(false)
const error = ref('')

const chips = computed(() =>
  CHIP_ORDER.map((key) => ({
    key,
    label: RESULT_META[key].label,
    tone: RESULT_META[key].tone,
    count: counts.value.find((one) => one.result === key)?.count ?? 0
  }))
)

const resultLabel = (value: string) =>
  RESULT_META[value as LoginResult]?.label ?? value
const resultTone = (value: string) => {
  const meta = RESULT_META[value as LoginResult]
  return meta?.tone === 'ok' ? 'success' : meta?.tone === 'bad' ? 'danger' : 'warning'
}
const formatTime = (iso: string) => {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN')
}

function toggleResult(key: string) {
  resultFilter.value = resultFilter.value === key ? '' : key
  page.value = 1
  void fetchPage()
}

async function fetchPage() {
  loading.value = true
  try {
    const data = await opsZoneApi.getLoginLogs({
      days: days.value,
      result: resultFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size
    })
    rows.value = data.list
    total.value = data.total
    counts.value = data.counts
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
.chip.ok .chip-value {
  color: var(--color-success, #15803d);
}
.chip.bad .chip-value {
  color: var(--color-danger, #b91c1c);
}
.chip.warn .chip-value {
  color: var(--color-warning, #b45309);
}
.chip.clear {
  color: var(--color-text-secondary);
}

.block {
  padding: var(--space-md);
}
.filters {
  display: flex;
  gap: var(--space-sm);
  margin-bottom: var(--space-sm);
}
.kw {
  width: 240px;
}
.mono {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}
.ua {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--color-text-secondary);
  cursor: help;
}
.tag-note {
  margin-left: var(--space-xs);
  font-size: 12px;
  color: var(--color-warning, #b45309);
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
