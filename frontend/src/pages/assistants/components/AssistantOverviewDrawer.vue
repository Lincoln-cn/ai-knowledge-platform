<template>
  <el-drawer
    v-model="visible"
    :title="assistant ? `概览 · ${assistant.name}` : '应用概览'"
    size="min(920px, 94vw)"
    @closed="reset"
  >
    <div v-loading="loading" class="overview">
      <!-- 用量指标：横向紧凑读数，不是四等分 KPI 宫格 -->
      <div class="metrics">
        <div class="metric">
          <div class="label">调用次数</div>
          <div class="m-num"><span class="num">{{ assistant?.callCount.toLocaleString() ?? '—' }}</span></div>
        </div>
        <div class="metric">
          <div class="label">Token 消耗</div>
          <div class="m-num"><span class="num">{{ assistant?.tokenCount.toLocaleString() ?? '—' }}</span></div>
        </div>
        <div class="metric">
          <div class="label">满意度</div>
          <div class="m-num">
            <template v-if="assistant?.satisfaction != null"><span class="num">{{ assistant.satisfaction }}</span>%</template>
            <template v-else>—</template>
          </div>
        </div>
        <div class="metric">
          <div class="label">发布态</div>
          <div class="m-num">
            <el-tag v-if="assistant?.published" size="small" type="success" effect="plain">已发布</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">草稿</el-tag>
          </div>
        </div>
      </div>

      <!-- 趋势图 -->
      <div class="trend panel">
        <div class="sec-hd">
          <span class="h4">趋势</span>
          <el-radio-group v-model="rangeDays" size="small" @change="loadTrend">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
          </el-radio-group>
        </div>
        <p v-if="trendGap" class="micro muted">
          当前分析契约仅支持全站趋势（<code>getTrend(range)</code>），未提供按应用过滤的参数。
          下图为全站数据，仅作参考。
        </p>
        <div ref="chartEl" class="chart" />
        <div v-if="!trend.length && !loading" class="empty">
          <div class="empty-title">暂无趋势数据</div>
        </div>
      </div>

      <CredentialsPanel v-if="assistant" ref="credRef" :app-id="assistant.id" />
      <ApiKeysPanel v-if="assistant" ref="keyRef" :app-id="assistant.id" />
      <BundlePanel v-if="assistant" :app-id="assistant.id" @imported="emit('saved')" />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { analyticsApi } from '@/api'
import type { Assistant } from '@/api/assistants'
import type { TrendPoint } from '@/api/analytics'
import CredentialsPanel from './CredentialsPanel.vue'
import ApiKeysPanel from './ApiKeysPanel.vue'
import BundlePanel from './BundlePanel.vue'

defineProps<{ assistant: Assistant | null }>()
const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const loading = ref(false)
const rangeDays = ref(7)
const trend = ref<TrendPoint[]>([])
const trendGap = ref(true)
const chartEl = ref<HTMLElement>()
const credRef = ref<InstanceType<typeof CredentialsPanel>>()
const keyRef = ref<InstanceType<typeof ApiKeysPanel>>()

let chart: echarts.ECharts | null = null

const loadTrend = async () => {
  const to = dayjs().format('YYYY-MM-DD')
  const from = dayjs().subtract(rangeDays.value - 1, 'day').format('YYYY-MM-DD')
  try {
    trend.value = await analyticsApi.getTrend({ from, to })
  } catch {
    // 分析域 mock 可能尚未挂上 —— 失败必须说话，但不阻断凭证/密钥管理
    trend.value = []
    trendGap.value = true
  }
  await nextTick()
  renderChart()
}

const renderChart = () => {
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  if (!trend.value.length) {
    chart.clear()
    return
  }
  // 图表色从设计令牌解析 —— 不内联色值
  const cs = getComputedStyle(document.documentElement)
  const accent = cs.getPropertyValue('--color-accent').trim()
  const ok = cs.getPropertyValue('--color-ok').trim()
  chart.setOption({
    grid: { left: 40, right: 16, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: trend.value.map((p) => p.day),
      axisLabel: { fontSize: 10 }
    },
    yAxis: [
      { type: 'value', name: '提问', axisLabel: { fontSize: 10 } },
      { type: 'value', name: '用户', axisLabel: { fontSize: 10 } }
    ],
    series: [
      {
        name: '提问数',
        type: 'line',
        smooth: true,
        data: trend.value.map((p) => p.questions),
        itemStyle: { color: accent }
      },
      {
        name: '用户数',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: trend.value.map((p) => p.users),
        itemStyle: { color: ok }
      }
    ]
  })
}

const onResize = () => chart?.resize()
window.addEventListener('resize', onResize)

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})

const reset = () => {
  trend.value = []
  chart?.clear()
}

const open = async () => {
  visible.value = true
  loading.value = true
  try {
    await Promise.all([
      loadTrend(),
      credRef.value?.fetch(),
      keyRef.value?.fetch()
    ])
  } finally {
    loading.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

/* 横向紧凑读数条，不是四等分 KPI 宫格 —— 用发丝线分栏 */
.metrics {
  display: flex;
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  overflow: hidden;
}

.metric {
  flex: 1;
  min-width: 0;
  padding: var(--space-sm) var(--space-md);
}

.metric + .metric {
  border-left: 1px solid var(--color-rule-2);
}

.m-num {
  margin-top: var(--space-2xs);
  font-family: var(--font-mono);
  font-size: var(--text-lg);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
  line-height: var(--leading-title);
}

.trend {
  padding: var(--space-md);
}

.sec-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-xs);
}

.chart {
  height: 240px;
}
</style>
