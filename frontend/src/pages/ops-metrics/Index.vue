<template>
  <div class="page">
    <PageHeader
      title="运营指标"
      desc="质量与用量的可核对读数；没有数据源的一律显示横线，不拿别的数冒充"
    >
      <template #actions>
        <el-radio-group v-model="rangeKey" size="small" @change="fetchAll">
          <el-radio-button value="7d">近 7 天</el-radio-button>
          <el-radio-button value="30d">近 30 天</el-radio-button>
        </el-radio-group>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchAll">刷新</el-button>
      </template>
    </PageHeader>

    <div v-if="error" class="notice err">
      <b>加载失败：</b>{{ error }}
      <el-button size="small" type="primary" plain class="retry" @click="fetchAll">重试</el-button>
    </div>

    <!-- ── 质量 ────────────────────────────────────────────── -->
    <section class="panel block">
      <header class="block-head">
        <h3 class="block-title">质量</h3>
        <span class="micro muted">{{ range.from }} ~ {{ range.to }}</span>
      </header>

      <div v-loading="loading" class="kpi-row">
        <div class="kpi">
          <div class="kpi-label">回答耗时 P50</div>
          <div class="kpi-value num">{{ fmtSec(metrics?.durationP50Ms) }}</div>
          <div class="kpi-note micro muted">整轮耗时，不是首字延迟</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">回答耗时 P95</div>
          <div class="kpi-value num">{{ fmtSec(metrics?.durationP95Ms) }}</div>
          <div class="kpi-note micro muted">长尾；B4 门槛是单跳 ≤10s</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">平均引用条数</div>
          <div class="kpi-value num">{{ fmtAvg(metrics?.avgRefCount) }}</div>
          <div class="kpi-note micro muted">引用越多不代表越准</div>
        </div>
        <div class="kpi" :class="{ warn: (metrics?.noRefRate ?? 0) > 10 }">
          <div class="kpi-label">无引用回答占比</div>
          <div class="kpi-value num">{{ fmtPct(metrics?.noRefRate) }}</div>
          <div class="kpi-note micro muted">
            分母是成功回答：{{ fmtCount(metrics?.answersWithoutRefs) }} / {{ fmtCount(metrics?.generatedAnswerCount) }} 条
          </div>
        </div>
        <div class="kpi">
          <div class="kpi-label">失败回答</div>
          <div class="kpi-value num">{{ fmtCount(metrics?.failedAnswerCount) }}</div>
          <div class="kpi-note micro muted">占全部回答 {{ fmtPct(failRate) }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">首字延迟 P50</div>
          <div class="kpi-value num">—</div>
          <div class="kpi-note micro muted">cv_message 没有首 token 时间戳，不拿总耗时冒充</div>
        </div>
      </div>
    </section>

    <!-- ── 用量 ────────────────────────────────────────────── -->
    <section class="panel block">
      <header class="block-head">
        <h3 class="block-title">用量</h3>
        <span class="micro muted">同一区间</span>
      </header>

      <div v-loading="loading" class="kpi-row">
        <div class="kpi">
          <div class="kpi-label">提问数</div>
          <div class="kpi-value num">{{ fmtCount(metrics?.questionCount) }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">回答数</div>
          <div class="kpi-value num">{{ fmtCount(metrics?.answerCount) }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">Token 消耗</div>
          <div class="kpi-value num">{{ fmtCount(metrics?.tokenCount) }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">活跃应用</div>
          <div class="kpi-value num">{{ fmtCount(metrics?.activeAssistantCount) }}</div>
          <div class="kpi-note micro muted">有消息往来的应用数</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">活跃用户</div>
          <div class="kpi-value num">{{ fmtCount(metrics?.activeUserCount) }}</div>
          <div class="kpi-note micro muted">有消息往来的用户数</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">满意度</div>
          <div class="kpi-value num">{{ fmtPct(metrics?.satisfactionRate) }}</div>
          <div class="kpi-note micro muted">
            赞 {{ fmtCount(metrics?.upCount) }} · 踩 {{ fmtCount(metrics?.downCount) }}
          </div>
        </div>
      </div>
    </section>

    <!--
      评测读数（离线）。★ 刻意与上面两块分开写、并写明口径：
        运行期接口算不出 B1/B2/B3（那是拿标注集跑离线脚本的读数），
        把两者摆在一起而不标口径，等于让读者以为"B3 = 90% 是刚才那个区间跑出来的"。
    -->
    <section class="panel block">
      <header class="block-head">
        <h3 class="block-title">评测读数（离线，非本区间）</h3>
        <span class="micro muted">真语料 20 篇 356 条 · 2026-11-18</span>
      </header>
      <div class="block-body">
        <div class="eval-row">
          <div class="eval-item">
            <div class="kpi-label">B1 命中率</div>
            <div class="kpi-value num ok">95.5%</div>
            <div class="kpi-note micro muted">门槛 70% · 340/356</div>
          </div>
          <div class="eval-item">
            <div class="kpi-label">B2 答案可用率</div>
            <div class="kpi-value num partial">95.7%</div>
            <div class="kpi-note micro muted">门槛 75% · LLM 口径 + 20 条人工校准</div>
          </div>
          <div class="eval-item">
            <div class="kpi-label">B3 引用准确率</div>
            <div class="kpi-value num ok">89.9%</div>
            <div class="kpi-note micro muted">门槛 80% · 320/356</div>
          </div>
        </div>
        <p class="eval-note micro muted">
          这三行来自 <code>docs/检索基线读数.md</code> §十二 的离线评测，<b>不随上面的时间区间变化</b>。
          B2 是 LLM-as-judge 口径（人工抽 20 条校准，可用/不可用一致 20/20），不是真人验收读数。
          已知缺口：跨体裁召回被泛词抢占（<code>#349</code>）。
        </p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { Refresh } from '@element-plus/icons-vue'
import { analyticsApi } from '@/api'
import type { AnalyticsRange, OpsMetrics } from '@/api/analytics'
import PageHeader from '@/components/PageHeader.vue'

type RangeKey = '7d' | '30d'

const RANGE_DAYS: Record<RangeKey, number> = { '7d': 7, '30d': 30 }

const rangeKey = ref<RangeKey>('7d')
const loading = ref(false)
const error = ref<string | null>(null)
const metrics = ref<OpsMetrics | null>(null)

const range = computed<AnalyticsRange>(() => {
  const days = RANGE_DAYS[rangeKey.value]
  return { from: dayjs().subtract(days - 1, 'day').format('YYYY-MM-DD'), to: dayjs().format('YYYY-MM-DD') }
})

/** 失败占比：分母是全部回答（含失败），与后端 noRefRate 的分母刻意不同 —— 各说各的事 */
const failRate = computed<number | null>(() => {
  const m = metrics.value
  if (!m || m.answerCount <= 0) return null
  return Math.round((m.failedAnswerCount / m.answerCount) * 1000) / 10
})

const fmtCount = (value: number | null | undefined) =>
  value == null ? '—' : value.toLocaleString('zh-CN')

const fmtPct = (value: number | null | undefined) =>
  value == null ? '—' : `${value.toFixed(1)}%`

const fmtAvg = (value: number | null | undefined) =>
  value == null ? '—' : value.toFixed(1)

/** 毫秒 → 秒；★ 一律保留 1 位，避免"8.5s 与 8498ms"在两处看起来不一样 */
const fmtSec = (ms: number | null | undefined) =>
  ms == null ? '—' : `${(ms / 1000).toFixed(1)}s`

const fetchAll = async () => {
  loading.value = true
  error.value = null
  try {
    metrics.value = await analyticsApi.getOpsMetrics(range.value)
  } catch (e) {
    metrics.value = null
    error.value = e instanceof Error ? e.message : '加载运营指标失败'
  } finally {
    loading.value = false
  }
}

onMounted(fetchAll)
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

.notice.err {
  padding: var(--space-md);
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-md);
  background: var(--color-danger-bg, transparent);
  color: var(--color-danger);
}

.retry {
  margin-left: var(--space-sm);
}

.block {
  display: flex;
  flex-direction: column;
}

.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--color-border);
}

.block-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.block-body {
  padding: var(--space-lg);
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1px;
  background: var(--color-border);
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
  padding: var(--space-md) var(--space-lg);
  background: var(--color-surface);
}

.kpi.warn .kpi-value {
  color: var(--color-warn);
}

.kpi-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.kpi-value {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
}

.kpi-value.ok {
  color: var(--color-ok);
}

.kpi-value.partial {
  color: var(--color-warn);
}

.kpi-note {
  line-height: 1.5;
}

.eval-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-lg);
}

.eval-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.eval-note {
  margin: var(--space-md) 0 0;
  line-height: 1.7;
}

.eval-note code {
  padding: 0 4px;
  border-radius: var(--radius-sm, 4px);
  background: var(--color-sunken);
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}
</style>
