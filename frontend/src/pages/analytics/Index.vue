<template>
  <div class="page">
    <PageHeader
      title="运营看板"
      desc="用量、质量与反馈的一页总览。指标全部来自后端事实，无数据时显示「—」而不是 0"
    >
      <template #actions>
        <el-radio-group v-model="rangeKey" size="small" @change="fetchAll">
          <el-radio-button value="7d">近 7 天</el-radio-button>
          <el-radio-button value="30d">近 30 天</el-radio-button>
        </el-radio-group>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchAll">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 核心指标 -->
    <div v-loading="loading" class="kpi-row">
      <div class="kpi panel">
        <div class="kpi-label label">提问数</div>
        <div class="kpi-value num">{{ fmtCount(overview?.questionCount) }}</div>
      </div>
      <div class="kpi panel">
        <div class="kpi-label label">活跃用户数</div>
        <div class="kpi-value num">{{ fmtCount(overview?.activeUserCount) }}</div>
      </div>
      <div class="kpi panel">
        <div class="kpi-label label">新增用户</div>
        <div class="kpi-value num">{{ fmtCount(overview?.newUserCount) }}</div>
      </div>
      <div class="kpi panel">
        <div class="kpi-label label">Token 消耗</div>
        <div class="kpi-value num">{{ fmtCount(overview?.tokenCount) }}</div>
      </div>
      <div class="kpi panel">
        <div class="kpi-label label">用户满意度</div>
        <div class="kpi-value num">{{ fmtPct(overview?.satisfaction) }}</div>
      </div>
    </div>

    <!-- 趋势 -->
    <section class="panel block">
      <header class="block-head">
        <h3 class="block-title">提问数与用户数趋势</h3>
        <span class="micro muted">{{ range.from }} ~ {{ range.to }}</span>
      </header>
      <div v-loading="loading" class="block-body">
        <TrendChart v-if="trend.length" :points="trend" />
        <div v-else class="empty micro muted">该区间内没有提问记录</div>
      </div>
    </section>

    <!-- 应用排行 + 质量观测 -->
    <div class="two-col">
      <section class="panel block">
        <header class="block-head">
          <h3 class="block-title">应用排行</h3>
          <span class="micro muted">按调用数排序</span>
        </header>
        <div v-loading="loading" class="block-body">
          <el-table :data="rank" size="small" :show-overflow-tooltip="true">
            <el-table-column type="index" label="#" width="44" />
            <el-table-column prop="name" label="应用" min-width="140" />
            <el-table-column prop="callCount" label="调用数" width="90" align="right">
              <template #default="{ row }">
                <span class="num">{{ fmtCount(row.callCount) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="tokenCount" label="Token" width="100" align="right">
              <template #default="{ row }">
                <span class="num">{{ fmtCount(row.tokenCount) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="satisfaction" label="满意度" width="88" align="right">
              <template #default="{ row }">
                <span class="num">{{ fmtPct(row.satisfaction) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!rank.length" class="empty micro muted">该区间内没有应用调用</div>
        </div>
      </section>

      <section class="panel block">
        <header class="block-head">
          <h3 class="block-title">质量观测</h3>
          <span class="micro muted">机制七 · 可观测</span>
        </header>
        <div v-loading="loading" class="block-body quality">
          <div class="q-grid">
            <div class="q-item">
              <div class="q-label label">首字延迟 P50</div>
              <div class="q-value num">{{ fmtMs(quality?.firstTokenLatencyP50) }}</div>
              <p class="q-note tiny muted">用户感知的核心指标，比总耗时更重要</p>
            </div>

            <div class="q-item">
              <div class="q-label label">
                结构化输出失败率
                <span class="q-tag micro muted">只埋点</span>
              </div>
              <div class="q-value num">{{ fmtRate(quality?.artifactFailureRate) }}</div>
              <p class="q-note tiny muted">MVP 只埋点，V1.1 起考核</p>
            </div>

            <div class="q-item">
              <div class="q-label label">平均引用数</div>
              <div class="q-value num">{{ fmtAvg(quality?.avgRefCount) }}</div>
              <p class="q-note tiny muted">命中数长期偏低说明知识库需要补充</p>
            </div>

            <div class="q-item">
              <div class="q-label label">点赞 / 点踩</div>
              <div class="q-value votes">
                <span class="num up"><i class="dot ok" />{{ fmtCount(quality?.upCount) }}</span>
                <span class="num down"><i class="dot err" />{{ fmtCount(quality?.downCount) }}</span>
              </div>
              <p class="q-note tiny muted">赞踩互斥且可撤销，刷新后状态保留</p>
            </div>
          </div>

          <div class="q-feedback">
            <div>
              <div class="q-label label">待处理反馈</div>
              <div class="q-value num">{{ fmtCount(quality?.pendingFeedbackCount) }}</div>
            </div>
            <el-button size="small" type="primary" plain @click="goFeedback">
              去处理
              <el-icon class="el-icon--right"><ArrowRight /></el-icon>
            </el-button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Refresh } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { analyticsApi } from '@/api'
import type {
  AssistantRankItem,
  AnalyticsRange,
  OverviewMetrics,
  QualityMetrics,
  TrendPoint
} from '@/api/analytics'
import PageHeader from '@/components/PageHeader.vue'
import TrendChart from './components/TrendChart.vue'

type RangeKey = '7d' | '30d'

const RANGE_DAYS: Record<RangeKey, number> = { '7d': 7, '30d': 30 }

const router = useRouter()

const rangeKey = ref<RangeKey>('7d')
const loading = ref(false)

const overview = ref<OverviewMetrics | null>(null)
const trend = ref<TrendPoint[]>([])
const rank = ref<AssistantRankItem[]>([])
const quality = ref<QualityMetrics | null>(null)

const range = computed<AnalyticsRange>(() => {
  const to = dayjs()
  const from = to.subtract(RANGE_DAYS[rangeKey.value] - 1, 'day')
  return { from: from.format('YYYY-MM-DD'), to: to.format('YYYY-MM-DD') }
})

const fetchAll = async () => {
  loading.value = true
  try {
    const q = range.value
    const [o, t, a, m] = await Promise.all([
      analyticsApi.getOverview(q),
      analyticsApi.getTrend(q),
      analyticsApi.listTopAssistants(q, 10),
      analyticsApi.getQualityMetrics(q)
    ])
    overview.value = o
    trend.value = t
    rank.value = a
    quality.value = m
  } finally {
    loading.value = false
  }
}

onMounted(fetchAll)

const goFeedback = () => router.push('/feedback')

/* ── 展示格式：无数据一律「—」，不把「没测到」画成 0 ── */
const fmtCount = (n: number | null | undefined) => (n == null ? '—' : n.toLocaleString())

/** 满意度已是百分比数值（如 91.5） */
const fmtPct = (n: number | null | undefined) =>
  n == null ? '—' : `${Number.isInteger(n) ? n : n.toFixed(1)}%`

/** 失败率是 0–1 比率 */
const fmtRate = (n: number | null | undefined) =>
  n == null ? '—' : `${(n * 100).toFixed(1)}%`

const fmtAvg = (n: number | null | undefined) => (n == null ? '—' : n.toFixed(1))

const fmtMs = (n: number | null | undefined) => (n == null ? '—' : `${n.toLocaleString()} ms`)
</script>

<style scoped>
.kpi-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--space-sm);
  min-height: 88px;
}

.kpi {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.kpi-value {
  font-family: var(--font-display);
  font-size: var(--text-xl);
  font-weight: 600;
  line-height: var(--leading-display);
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
  letter-spacing: var(--tracking-display);
}

.block {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md) 0;
}

.block-title {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-ink);
}

.block-body {
  padding: var(--space-xs) var(--space-md) var(--space-sm);
  min-height: 120px;
}

.two-col {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: var(--space-sm);
  align-items: start;
}

.empty {
  padding: var(--space-lg) var(--space-sm);
  text-align: center;
}

.quality {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.q-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-xs);
}

.q-item {
  padding: var(--space-sm) var(--space-md);
  background: var(--color-sunken);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.q-label {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

.q-tag {
  transform: scale(0.92);
  transform-origin: left center;
}

.q-value {
  font-family: var(--font-display);
  font-size: var(--text-lg);
  font-weight: 600;
  line-height: var(--leading-display);
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
  letter-spacing: var(--tracking-display);
}

.q-note {
  margin: 0;
  line-height: var(--leading-body);
}

.votes {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  font-size: var(--text-lg);
}

.votes .up {
  color: var(--color-ok);
}

.votes .down {
  color: var(--color-danger);
}

.votes .dot {
  margin-right: var(--space-2xs);
}

.q-feedback {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.q-feedback .q-value {
  font-size: var(--text-lg);
  color: var(--color-ink);
}

@media (max-width: 1100px) {
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
  }

  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
