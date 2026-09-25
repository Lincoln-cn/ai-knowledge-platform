<template>
  <div class="page">
    <div class="page-head">
      <div class="head-main">
        <h1 class="h1">工作台</h1>
        <p class="micro muted intro">
          近 {{ rangeDays }} 天（{{ range.from }} ~ {{ range.to }}）的使用情况与常用入口。
          数据来自运营统计接口，接上后端后随 <code>VITE_USE_MOCK</code> 一起切换。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="fetchAll">刷新</el-button>
        <el-button type="primary" @click="router.push('/conversation')">开始问答</el-button>
      </div>
    </div>

    <!-- 指标条：数字是主角，标签走 mono 小标签 -->
    <div class="stats panel">
      <div class="stat">
        <span class="stat-k label">问答次数</span>
        <span class="stat-v num">{{ fmtCount(overview?.questionCount) }}</span>
      </div>
      <div class="stat">
        <span class="stat-k label">活跃用户</span>
        <span class="stat-v num">{{ fmtCount(overview?.activeUserCount) }}</span>
      </div>
      <div class="stat">
        <span class="stat-k label">满意度</span>
        <span class="stat-v num">{{ fmtPct(overview?.satisfaction) }}</span>
      </div>
      <div class="stat">
        <span class="stat-k label">待处理反馈</span>
        <span class="stat-v num">{{ fmtCount(quality?.pendingFeedbackCount) }}</span>
      </div>
    </div>

    <div class="cols">
      <!-- 常用应用：按调用次数排序，点进去直接问答 -->
      <section class="panel">
        <div class="panel-head">
          <h2 class="h2">常用应用</h2>
          <router-link to="/assistants" class="link">全部应用</router-link>
        </div>

        <ul v-if="apps.length" class="apps">
          <li v-for="item in apps" :key="item.appId" class="app-row">
            <router-link :to="`/conversation?appId=${item.appId}`" class="app-name">
              {{ item.name }}
            </router-link>
            <span class="app-metric num">{{ fmtCount(item.callCount) }} 次</span>
            <span class="app-metric num">{{ fmtPct(item.satisfaction) }}</span>
          </li>
        </ul>
        <div v-else class="empty">
          <div class="empty-title">还没有使用记录</div>
          <span>发布一个应用并问答后，这里会列出最常用的几个</span>
        </div>
      </section>

      <!-- 从这里开始：入口按**权限**过滤，与侧栏同一套判据（避免点进去 403） -->
      <section class="panel">
        <div class="panel-head">
          <h2 class="h2">从这里开始</h2>
        </div>
        <ul class="entries">
          <li v-for="e in entries" :key="e.path" class="entry-row">
            <router-link :to="e.path" class="entry">
              <span class="entry-name">{{ e.label }}</span>
              <span class="entry-desc micro muted">{{ e.desc }}</span>
            </router-link>
          </li>
        </ul>
      </section>
    </div>

    <!--
      机器回执 —— 本页的焦点元素，也是本页唯一那一次深色石墨带
      （Cobalt 签名 4 + 8，见 design.md § 仪器面板）。
      数据全部来自**本页已经在拉、但此前没展示**的字段：不新增请求，也不编数字。
      状态胶囊不是装饰：WARN 的条件取自验收口径 B4（首字延迟 ≤ 10 s）。
      ★ 每视口只许一个 —— 它一多，"焦点"就没有了。
    -->
    <InstrumentCard
      v-if="instrumentRows.length"
      title="近 7 天运行回执"
      :status="instrumentStatus"
      :rows="instrumentRows"
    />

    <!--
      待办条是**入口**，所以要按权限过滤 —— 与上面「从这里开始」同一套判据。
      此前只判 `pendingFeedbackCount > 0`，于是**普通用户（无 feedback:handle）
      会看到一条通往 403 的「去处理」链接**。用户看到通向 403 的入口，比"没有入口"更坏。
    -->
    <div
      v-if="(quality?.pendingFeedbackCount ?? 0) > 0 && canReach(router, '/feedback')"
      class="notice info"
    >
      有 <b>{{ quality?.pendingFeedbackCount }}</b> 条反馈待处理 ——
      <router-link to="/feedback" class="link">去处理</router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { analyticsApi } from '@/api'
import type { AnalyticsRange, AssistantRankItem, OverviewMetrics, QualityMetrics } from '@/api/analytics'
import InstrumentCard, { type InstrumentRow, type InstrumentStatus } from '@/components/InstrumentCard.vue'
import { canReach } from '@/lib/reach'

const router = useRouter()

/** 首页固定看近 7 天 —— 需要别的区间去运营看板，那里才是「按区间深看」的地方。 */
const rangeDays = 7

const loading = ref(false)
const overview = ref<OverviewMetrics | null>(null)
const quality = ref<QualityMetrics | null>(null)
const apps = ref<AssistantRankItem[]>([])

const range = computed<AnalyticsRange>(() => {
  const to = dayjs()
  const from = to.subtract(rangeDays - 1, 'day')
  return { from: from.format('YYYY-MM-DD'), to: to.format('YYYY-MM-DD') }
})

const fetchAll = async () => {
  loading.value = true
  try {
    const q = range.value
    const [o, a, m] = await Promise.all([
      analyticsApi.getOverview(q),
      analyticsApi.listTopAssistants(q, 5),
      analyticsApi.getQualityMetrics(q)
    ])
    overview.value = o
    apps.value = a
    quality.value = m
  } finally {
    loading.value = false
  }
}

onMounted(fetchAll)

/**
 * 入门入口。**按权限过滤** —— 判据走 `canReach()`（与顶栏、命令面板、路由守卫同源），
 * 否则无权限的角色会看到通向 403 的入口：那是比「没有入口」更坏的体验。
 */
const allEntries = [
  { path: '/knowledge', label: '建知识库', desc: '上传文档、切分入库，供应用检索' },
  { path: '/assistants', label: '建应用', desc: '把模型、知识库与提示词组装成问答服务' },
  { path: '/analytics', label: '看运营数据', desc: '问答趋势、应用排行与质量观测' },
  { path: '/feedback', label: '处理反馈', desc: '用户点赞点踩与处理闭环' }
]

const entries = computed(() => allEntries.filter((e) => canReach(router, e.path)))

/**
 * 机器回执的读数行 —— 用**本页已经在拉、但此前没展示**的那几个字段：
 * `quality` 有六个字段，此前只用了 `pendingFeedbackCount`；`overview` 也只用了一半。
 * 所以这里不新增任何请求，也不编数字（仪器面板的第一条纪律）。
 *
 * 空数组时组件什么都不渲染（不画占位、不画 0）—— 「没测到」显示「—」是本工程的通则。
 */
const instrumentRows = computed<InstrumentRow[]>(() => {
  const q = quality.value
  const o = overview.value
  if (!q || !o) return []
  return [
    {
      label: '首字延迟 P50',
      value: `${q.firstTokenLatencyP50} ms`,
      hint: '验收口径 B4 ≤ 10000 ms'
    },
    { label: '平均引用数', value: q.avgRefCount.toFixed(1), hint: '每条回答带回的分段数' },
    {
      label: '结构化输出失败率',
      value: q.artifactFailureRate == null ? '—' : `${(q.artifactFailureRate * 100).toFixed(1)}%`,
      hint: 'MVP 只埋点，V1.1 起考核'
    },
    { label: 'Token 用量', value: o.tokenCount.toLocaleString() },
    { label: '新增用户', value: o.newUserCount },
    { label: '赞 / 踩', value: `${q.upCount} / ${q.downCount}` }
  ]
})

/**
 * 状态胶囊**由真实读数推导**，不是装饰：阈值取自 `docs/系统设计方案/04` 的验收口径
 * B4（首字延迟 ≤ 10 s）。四态里只用了两态 —— 没有数据时上面的 `v-if` 已经不让它渲染。
 */
const instrumentStatus = computed<InstrumentStatus>(() =>
  (quality.value?.firstTokenLatencyP50 ?? 0) > 10000 ? 'WARN' : 'OK'
)

/* 展示格式：「没测到」一律显示「—」，不画成 0 */
const fmtCount = (n: number | null | undefined) => (n == null ? '—' : n.toLocaleString())
const fmtPct = (n: number | null | undefined) =>
  n == null ? '—' : `${Number.isInteger(n) ? n : n.toFixed(1)}%`
</script>

<style scoped>
.head-main {
  min-width: 0;
}

.intro {
  margin: 0;
  line-height: var(--leading-body);
  max-width: 880px;
}

/* 指标条：一条 panel，内部等分。等分是为了铺满 —— 自然宽度只占 27%，
   右侧会留出近千像素空白，看着像没做完。 */
.stats {
  display: flex;
  align-items: stretch;
  overflow: hidden;
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
  padding: var(--space-sm) var(--space-md);
  border-right: 1px solid var(--color-rule-2);
}

.stat:last-child {
  border-right: none;
}

.stat-v {
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--color-ink);
  line-height: 1.2;
}

/* 两栏不等宽：常用应用是主体，入门入口是窄栏 —— 不搞等宽双栏那种模板感 */
.cols {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: var(--space-md);
  align-items: start;
}

@media (max-width: 1100px) {
  .cols {
    grid-template-columns: minmax(0, 1fr);
  }
}

.apps,
.entries {
  list-style: none;
  margin: 0;
  padding: 0;
}

.app-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-md);
  border-bottom: 1px solid var(--color-rule-2);
}

.app-row:last-child {
  border-bottom: none;
}

.app-row:hover {
  background: var(--color-hover);
}

.app-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-ink);
  font-weight: 500;
}

.app-metric {
  color: var(--color-muted);
  flex-shrink: 0;
}

.entry {
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
  padding: var(--space-xs) var(--space-md);
  border-bottom: 1px solid var(--color-rule-2);
}

.entry-row:last-child .entry {
  border-bottom: none;
}

.entry:hover {
  background: var(--color-hover);
}

.entry-name {
  color: var(--color-ink);
  font-weight: 500;
}

/* 文字按钮：accent 色，hover 出下划线 */
.link {
  color: var(--color-accent);
  font-size: var(--text-2xs);
  text-underline-offset: 2px;
}

.link:hover {
  text-decoration: underline;
}

code {
  background: var(--color-sunken);
  padding: 0 var(--space-2xs);
  border-radius: var(--radius-xs);
}
</style>
