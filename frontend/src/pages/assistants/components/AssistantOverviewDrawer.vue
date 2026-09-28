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

      <!--
        发布与访问回执 —— 本抽屉唯一那一次深色石墨带（Cobalt 签名 4 + 8，见 design.md § 仪器面板）。
        读数全部来自 `assistant` 上**已有、此前没展示**的字段（type / modelName / workflow /
        kbIds / promptIds / multiTurn），外加一次 listCredentials。

        ⚠️ CredentialsPanel 内部**也会**调 `assistantsApi.listCredentials`，所以本处多一次请求。
        这是刻意取舍：要省掉它，就得让那个表格组件把列表吐上来（新增 emit / 上提状态），
        等于让"独立读数"反过来依赖表格组件的挂载与刷新时机，耦合更坏。
        代价要说清：在本抽屉里新建 / 撤销凭证后，这里有两条读数要等**下次打开抽屉**才刷新。

        发布态**不单列一行** —— 上面 .metrics 已经有一条「发布态」标签，重复一遍不是读数；
        由右上角四态胶囊承担（published → OK / 未发布 → WARN）。
      -->
      <InstrumentCard
        v-if="publishRows.length"
        title="发布与访问回执"
        :status="publishStatus"
        :rows="publishRows"
      />

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
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { analyticsApi, assistantsApi } from '@/api'
import type { AccessCredential, Assistant } from '@/api/assistants'
import type { TrendPoint } from '@/api/analytics'
import InstrumentCard, { type InstrumentRow, type InstrumentStatus } from '@/components/InstrumentCard.vue'
import CredentialsPanel from './CredentialsPanel.vue'
import ApiKeysPanel from './ApiKeysPanel.vue'
import BundlePanel from './BundlePanel.vue'

const props = defineProps<{ assistant: Assistant | null }>()
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

/* ────────────── 发布与访问回执读数（仪器面板 · 签名 4 + 8） ────────────── */

/** 凭证列表。`null` = 还没读到（含读取失败），此时面板显示「—」而不是画成 0 */
const creds = ref<AccessCredential[] | null>(null)
const credsFailed = ref(false)

const loadCredentials = async () => {
  const id = props.assistant?.id
  if (!id) {
    creds.value = null
    credsFailed.value = false
    return
  }
  try {
    creds.value = await assistantsApi.listCredentials(id)
    credsFailed.value = false
  } catch {
    creds.value = null
    credsFailed.value = true
  }
}

/**
 * 读数行。**零新增字段**：全部取自 `assistant` 上已有但此前没展示的属性 —— 不编数字。
 *
 * 编排应用没有 `modelName` 是**契约刻意的**（模型在各 AI 节点里，可以一条流程用多个，
 * 在应用壳上放一个"默认模型"会让人以为整条流程就用那一个），不是缺数据，
 * 所以显示「见流程」+ 节点数，而不是「—」。
 *
 * 发布态不在这里重复（见模板注释）；`.metrics` 已经有一条「发布态」。
 */
const publishRows = computed<InstrumentRow[]>(() => {
  const a = props.assistant
  if (!a) return []
  const workflow = a.type === 'WORKFLOW'

  const rows: InstrumentRow[] = [
    {
      label: '应用类型',
      value: workflow ? '编排' : '简单',
      hint: workflow ? '创建后不可更改' : `${a.retrieval.mode} · Top${a.retrieval.topN}`
    },
    {
      label: '模型',
      value: workflow ? '见流程' : (a.modelName ?? '—'),
      hint: workflow
        ? (a.workflow ? `流程共 ${a.workflow.nodeCount} 个节点` : undefined)
        : `${a.multiTurn ? '多轮' : '单轮'}上下文 · 思考模式${a.thinkingEnabled ? '开' : '关'}`
    },
    {
      label: '知识库',
      value: a.kbIds.length,
      hint: `${a.promptIds.length} 个提示词模板`
    }
  ]

  if (credsFailed.value) {
    rows.push({ label: '凭证', value: '—', hint: '凭证列表读取失败' })
    return rows
  }

  const list = creds.value
  if (!list) return rows

  rows.push({
    label: '凭证',
    value: list.length,
    hint: `启用 ${list.filter((c) => c.enabled && !c.revokedAt).length} · 已撤销 ${
      list.filter((c) => c.revokedAt).length
    }`
  })

  // 访问回执：有凭证才谈得上"访问"，一条都没有时不画 0
  if (list.length) {
    const lastAt = list
      .map((c) => c.lastAccessAt)
      .filter((t): t is string => !!t)
      .sort()
      .at(-1)
    rows.push({
      label: '累计访问',
      value: list.reduce((n, c) => n + c.accessCount, 0).toLocaleString(),
      hint: lastAt ? `最近 ${dayjs(lastAt).format('MM-DD HH:mm')}` : '尚无访问记录'
    })
  }

  return rows
})

/** 状态胶囊由真实读数推导：`published` → OK；未发布 → WARN。 */
const publishStatus = computed<InstrumentStatus>(() => (props.assistant?.published ? 'OK' : 'WARN'))

const open = async () => {
  visible.value = true
  loading.value = true
  try {
    // 父组件是 `current.value = a; overviewRef.value?.open()` **同步连着调**的：
    // 此刻 props.assistant 还是上一次的应用，抽屉 body 也还没挂载（子组件 ref 为 undefined）。
    // 不等这一拍，会同时踩三个坑（实测）：
    //   ① 凭证读数取到的是**上一个应用**的列表 —— 面板显示的却是当前应用；
    //   ② 访问凭证表首次打开是空的（`credRef.value?.fetch()` 被 `?.` 静默跳过）；
    //   ③ API Key 表同上。
    await nextTick()
    await Promise.all([
      loadTrend(),
      credRef.value?.fetch(),
      keyRef.value?.fetch(),
      loadCredentials()
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
