<template>
  <div class="page">
    <PageHeader
      title="运营看板"
      desc="用量、质量与反馈的总览；没有读数时显示横线而不是 0"
    >
      <template #actions>
        <el-radio-group v-model="rangeKey" size="small" @change="fetchAll">
          <el-radio-button value="7d">近 7 天</el-radio-button>
          <el-radio-button value="30d">近 30 天</el-radio-button>
        </el-radio-group>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchAll">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 错误态 ≠ 空态：请求失败不能伪装成「暂无数据」 -->
    <div v-if="error" class="notice err">
      <b>加载失败：</b>{{ error }}
      <el-button size="small" type="primary" plain style="margin-left: var(--space-sm)" @click="fetchAll">重试</el-button>
    </div>

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
      <!-- 围栏拦截单独一张卡：它是"风险侧"的读数，与上面四个"用量侧"的卡不该混着看 -->
      <div class="kpi panel">
        <div class="kpi-label label">围栏拦截</div>
        <div class="kpi-value num">{{ fmtCount(guardStats?.total) }}</div>
      </div>
    </div>

    <!-- 趋势 + 安全围栏：两张都是"随时间变化"的读数，并排放（用户 2026-11-16 要求）。
         原来是上下各占一整行 —— 看板要滚两屏才看得全，而这两块的信息密度都不高。 -->
    <div class="top-row">
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

      <!-- 安全围栏：拦了多少、在涨还是在落。逐条明细在「拦截记录」页 -->
      <section class="panel block">
        <header class="block-head">
          <h3 class="block-title">安全围栏</h3>
        </header>
        <div v-loading="loading" class="block-body guard-block">
          <div class="guard-head">
            <div class="guard-figure">
              <div class="guard-total">
                <span class="guard-num num">{{ fmtCount(guardStats?.total) }}</span>
                <span class="micro muted">次拦截</span>
              </div>
              <p class="tiny muted guard-note">
                拦下的是"进到模型之前"的提问。数字长期为 0 通常不是没风险，而是规则表还是空的。
              </p>
            </div>
            <!-- ★ 没有分类分布时整块不出现：留一句"还没有数据"会把卡片撑出一片空白，
                 而"零分布"这件事总值那个 0 已经说过了。 -->
            <ul v-if="guardStats?.byCategory.length" class="cat-chips">
              <li v-for="c in guardStats.byCategory" :key="c.categoryCode">
                <span class="cat-chip-name">{{ c.categoryName ?? c.categoryCode }}</span>
                <b class="num">{{ c.hits }}</b>
              </li>
            </ul>
          </div>
          <div class="guard-chart-wrap">
            <GuardTrendChart v-if="guardByDay.length" :points="guardByDay" />
            <div v-else class="empty micro muted">该区间内没有拦截记录</div>
          </div>
        </div>
      </section>
    </div>

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
            <el-table-column prop="name" label="应用" min-width="140">
              <template #default="{ row }">
                <!-- ★ 通用对话是排行里的一行"虚拟应用"（assistant_id 为 null，后端用全零 UUID
                     标出来）。给它一个弱化的标记，别让人以为它是一个真的应用（#284）。 -->
                <span>{{ row.name }}</span>
                <el-tag v-if="row.id === GENERIC_RANK_ID" size="small" effect="plain" class="generic-tag">
                  未绑定
                </el-tag>
              </template>
            </el-table-column>
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
            <!-- 入口按权限过滤（reach.ts 唯一判据），否则无 feedback:handle 的角色会看到通向 403 的按钮 -->
            <el-button
              v-if="canReach(router, '/feedback')"
              size="small" type="primary" plain @click="goFeedback"
            >
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
import { analyticsApi, guardApi } from '@/api'
import type {
  AssistantRankItem,
  AnalyticsRange,
  OverviewMetrics,
  QualityMetrics,
  TrendPoint
} from '@/api/analytics'
import type { GuardStats, GuardTrendPoint } from '@/api/guard'
import PageHeader from '@/components/PageHeader.vue'
import TrendChart from './components/TrendChart.vue'
import GuardTrendChart from './components/GuardTrendChart.vue'
import { canReach } from '@/lib/reach'

type RangeKey = '7d' | '30d'

const RANGE_DAYS: Record<RangeKey, number> = { '7d': 7, '30d': 30 }

const router = useRouter()

const rangeKey = ref<RangeKey>('7d')
const loading = ref(false)
const error = ref<string | null>(null)

const overview = ref<OverviewMetrics | null>(null)
const trend = ref<TrendPoint[]>([])
const rank = ref<AssistantRankItem[]>([])

/**
 * 通用对话那一行的 id（全零 UUID，由后端 `AnalyticsService.topAssistants` 的 coalesce 产生）。
 *
 * ★ 它必须与后端常量同源 —— 但前端拿不到编译期常量，所以这里写死并在
 *   `analytics` 的契约注释里指向后端。改一处要改两处，已由「两数对得上」这条读数守着。
 */
const GENERIC_RANK_ID = '00000000-0000-0000-0000-000000000000'
const quality = ref<QualityMetrics | null>(null)

/**
 * 安全围栏的聚合读数。
 *
 * ★ 它走的是 `GET /guard/stats` 而不是 `analyticsApi` 下的端点：数据源是
 *   `pf_audit_log` 里的 `guard.block`，那几张表的读取口径只在 guard 模块里有一份。
 *   这里只是消费，不重复实现聚合。
 */
const guardStats = ref<GuardStats | null>(null)

/**
 * 按天补零。
 *
 * ★ 后端只回"有拦截的日期" —— 窗口里有几天只有前端知道（7d / 30d 是页面上选的）。
 *   不补零的话，一条 7 天里只出现 2 天的折线会被画成 7 个点的连线，看的人
 *   会以为中间几天"掉了"。
 */
const guardByDay = computed<GuardTrendPoint[]>(() => {
  const days = RANGE_DAYS[rangeKey.value]
  const hits = new Map((guardStats.value?.byDay ?? []).map((p) => [p.day, p.hits]))
  const start = dayjs().subtract(days - 1, 'day')
  return Array.from({ length: days }, (_, i) => {
    const day = start.add(i, 'day').format('YYYY-MM-DD')
    return { day, hits: hits.get(day) ?? 0 }
  })
})

/**
 * ★ 分类名由后端随每行给出（`/guard/stats` 现场 join 分类表）。
 * 这里**不再写死码→名的映射**：分类是运营配置项，可以新建、可以改名 ——
 * 前端复制一份的话，改名之后看板上还显示旧名，自建分类则只剩机器码。
 * 分类被删掉时 `categoryName` 为 null，回落成码。
 */

const range = computed<AnalyticsRange>(() => {
  const to = dayjs()
  const from = to.subtract(RANGE_DAYS[rangeKey.value] - 1, 'day')
  return { from: from.format('YYYY-MM-DD'), to: to.format('YYYY-MM-DD') }
})

const fetchAll = async () => {
  loading.value = true
  error.value = null
  try {
    const q = range.value
    const [o, t, a, m, g] = await Promise.all([
      analyticsApi.getOverview(q),
      analyticsApi.getTrend(q),
      analyticsApi.listTopAssistants(q, 10),
      analyticsApi.getQualityMetrics(q),
      // 围栏读数单独 catch：它读不到不该让整页报错（下面那三块仍然可用）
      guardApi.getGuardStats(RANGE_DAYS[rangeKey.value]).catch(() => null)
    ])
    overview.value = o
    trend.value = t
    rank.value = a
    quality.value = m
    guardStats.value = g
  } catch (e) {
    // 错误态 ≠ 空态：请求失败不能伪装成「暂无数据」（一.1）
    error.value = e instanceof Error ? e.message : '加载运营数据失败，请检查网络后重试'
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
/* 通用对话那一行的「未绑定」标记：弱化，别与真实应用的标签抢注意力 */
.generic-tag {
  margin-left: var(--space-xs);
  transform: scale(0.9);
}

.kpi-row {
  display: grid;
  /* 卡片数量会随读数增减（围栏那张是后加的），用 auto-fit 而不是写死 5 列 */
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
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
  /* ★ stretch 而不是 start：两张卡要一样高（用户 2026-11-16 明确要求）。
     start 会让每张卡各自按内容定高，于是「应用排行」比「质量观测」矮一截。 */
  align-items: stretch;
}

/*
 * 趋势 + 安全围栏并排。
 *
 * ★ 趋势图是主读数，给它宽的那一半；安全围栏的信息密度低（一个数字 + 几条分类 + 一条线），
 *   窄的一半够用。`minmax(0, …)` 里的 0 是必需的：不写的话网格列会被内容的最小宽度撑开，
 *   窄屏时不会塌。
 */
.top-row {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr);
  gap: var(--space-sm);
  align-items: stretch;
}

/* 右侧窄了之后，安全围栏那块改成单列：数字在上、分类 chips 在中、折线在下 */
.top-row .guard-head {
  grid-template-columns: 1fr;
  gap: var(--space-sm);
}

/* 两块共享同一个行高，让图表吃掉卡片里剩下的高度，别在下方留一条空白 */
.top-row .block .block-body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 左侧：整块就是一张图（或一句空态），让它撑满。
   ★ min-height 不能写 0 —— 那会覆盖组件自己给图的保底高度，右侧内容矮时图就被压扁了 */
.top-row .block .block-body > .trend-chart {
  flex: 1 1 auto;
  min-height: 280px;
}

.top-row .block .block-body > .empty {
  flex: 1 1 auto;
  min-height: 0;
}

/* 右侧：上面那三行文字按自身高度，余下的高度全给折线 */
.top-row .guard-block > .guard-head {
  flex: 0 0 auto;
}

.top-row .guard-block > .guard-chart-wrap {
  flex: 1 1 auto;
  min-height: 220px;
  display: flex;
  flex-direction: column;
}

.top-row .guard-block > .guard-chart-wrap > * {
  flex: 1 1 auto;
  min-height: 0;
}


/*
 * 两卡等高之后，内容多的一方不能把卡片撑破：给一个高度上界，超出在卡片内部滚。
 *
 * ★ 为什么是 520px：`.block` 没有自然的高度上界（它由内容决定），而"一样高"要求
 *   两卡共享同一个高度。这个值放在这里是为了让「应用排行」在 10 行以上时不再往下长 ——
 *   要改就改这一处，别在子元素上再写一份 max-height。
 */
.two-col .block {
  max-height: 520px;
}

/* 卡片内部滚动：body 吃掉剩余高度，内容超出就在这张卡里滚，页面长度不变 */
.two-col .block .block-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
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

/* ── 安全围栏：上面一行是总量与分类分布，下面是趋势 ── */
.guard-head {
  display: grid;
  grid-template-columns: minmax(220px, 300px) 1fr;
  gap: var(--space-lg);
  align-items: start;
}

.guard-total {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
}

.guard-num {
  font-size: 32px;
  font-weight: 600;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.guard-note {
  margin-top: var(--space-xs);
  line-height: 1.7;
}

.guard-chart-wrap {
  margin-top: var(--space-md);
}

/*
 * 按分类的分布做成一行 chips。
 *
 * ★ 原来是「分类名 + 条形 + 次数」的竖排列表 —— 在并排布局里它把卡片撑得很高，
 *   而没数据时那句"还没有数据"会在一片空白正中飘着。一行 chips 的高度与"一个分类"
 *   还是"五个分类"差不太多，也不会因为空分布而占位。
 */
.cat-chips {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2xs);
}

.cat-chips li {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--color-sunken);
  font-size: var(--text-sm);
  color: var(--color-ink-2);
}

.cat-chip-name {
  color: var(--color-ink-2);
}

.cat-chips b {
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1100px) {
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
  }

  .two-col {
    grid-template-columns: 1fr;
  }

  .top-row {
    grid-template-columns: 1fr;
  }

  .guard-head {
    grid-template-columns: 1fr;
  }
}</style>
