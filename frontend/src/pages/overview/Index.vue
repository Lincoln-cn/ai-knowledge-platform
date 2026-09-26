<template>
  <div class="page">
    <div class="page-head">
      <div class="head-main">
        <h1 class="h1">工作台</h1>
        <p class="micro muted intro">
          <template v-if="consumer">
            近 {{ rangeDays }} 天（{{ range.from }} ~ {{ range.to }}）的使用情况。
            选一个应用开始提问，或继续上次的对话。
          </template>
          <template v-else>
            近 {{ rangeDays }} 天（{{ range.from }} ~ {{ range.to }}）的使用情况与常用入口。
            数据来自运营统计接口，接上后端后随 <code>VITE_USE_MOCK</code> 一起切换。
          </template>
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="fetchAll">刷新</el-button>
        <el-button type="primary" @click="router.push('/conversation')">开始对话</el-button>
      </div>
    </div>

    <!-- 指标条：消费者看「我的」口径，建设者/运营看平台口径（07 §五） -->
    <div v-if="consumer" class="stats panel">
      <div class="stat">
        <span class="stat-k label">我的提问</span>
        <span class="stat-v num">{{ fmtCount(mySummary?.myQuestionCount) }}</span>
      </div>
      <div class="stat">
        <span class="stat-k label">我的对话</span>
        <span class="stat-v num">{{ fmtCount(mySummary?.myConversationCount) }}</span>
      </div>
      <div class="stat">
        <span class="stat-k label">我的赞</span>
        <span class="stat-v num">{{ fmtCount(mySummary?.myUp) }}</span>
      </div>
      <div class="stat">
        <span class="stat-k label">我的踩</span>
        <span class="stat-v num">{{ fmtCount(mySummary?.myDown) }}</span>
      </div>
    </div>
    <div v-else class="stats panel">
      <div class="stat">
        <span class="stat-k label">对话次数</span>
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
      <!-- 消费者左栏：我的对话 + 我提的反馈；建设者/运营：常用应用 -->
      <template v-if="consumer">
        <div class="consumer-main">
          <section class="panel">
            <div class="panel-head">
              <h2 class="h2">我的对话</h2>
              <router-link to="/conversation" class="link">全部对话</router-link>
            </div>
            <ul v-if="myConversations.length" class="apps">
              <li v-for="c in myConversations" :key="c.id" class="app-row">
                <router-link :to="`/conversation?conversationId=${c.id}`" class="app-name">
                  {{ c.title || '未命名对话' }}
                </router-link>
                <span class="app-metric micro muted">{{ c.appName }}</span>
                <span class="app-metric micro muted">{{ c.updatedAt }}</span>
              </li>
            </ul>
            <div v-else class="empty">
              <div class="empty-title">还没有对话记录</div>
              <span>选一个应用开始提问，这里会列出最近的对话</span>
            </div>
          </section>

          <section class="panel">
            <div class="panel-head">
              <h2 class="h2">我提的反馈</h2>
            </div>
            <ul v-if="myFeedbacks.length" class="apps">
              <li v-for="f in myFeedbacks" :key="f.id" class="app-row">
                <span class="vote-tag" :class="f.type === 'up' ? 'up' : 'down'">
                  {{ f.type === 'up' ? '赞' : '踩' }}
                </span>
                <span class="app-name" :title="f.question">{{ f.question }}</span>
                <span class="status-tag" :class="feedbackStatusClass(f.status)">{{ f.status }}</span>
              </li>
            </ul>
            <div v-else class="empty">
              <div class="empty-title">还没有提交过反馈</div>
              <span>在对话页点赞或点踩后，这里能看到处理进展</span>
            </div>
          </section>
        </div>
      </template>
      <template v-else>
        <section class="panel">
          <div class="panel-head">
            <h2 class="h2">常用应用</h2>
            <router-link v-if="canReach(router, '/assistants')" to="/assistants" class="link">全部应用</router-link>
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
            <span>发布一个应用并对话后，这里会列出最常用的几个</span>
          </div>
        </section>
      </template>

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
      消费者口径换成「我的」读数而不是删掉（07 §五）。
      状态胶囊由真实读数推导，不是装饰。
      ★ 每视口只许一个 —— 它一多，"焦点"就没有了。
    -->
    <InstrumentCard
      v-if="instrumentRows.length"
      :title="consumer ? '我的使用回执' : '近 7 天运行回执'"
      :status="instrumentStatus"
      :rows="instrumentRows"
    />

    <!--
      待办条是**入口**，所以要按权限过滤 —— 与上面「从这里开始」同一套判据。
      此前只判 `pendingFeedbackCount > 0`，于是**普通用户（无 feedback:handle）
      会看到一条通往 403 的「去处理」链接**。用户看到通向 403 的入口，比"没有入口"更坏。
    -->
    <div
      v-if="!consumer && (quality?.pendingFeedbackCount ?? 0) > 0 && canReach(router, '/feedback')"
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
import { analyticsApi, conversationsApi, feedbackApi } from '@/api'
import type { AnalyticsRange, AssistantRankItem, MeSummary, OverviewMetrics, QualityMetrics } from '@/api/analytics'
import type { Conversation } from '@/api/conversations'
import type { FeedbackItem, FeedbackStatus } from '@/api/feedback'
import InstrumentCard, { type InstrumentRow, type InstrumentStatus } from '@/components/InstrumentCard.vue'
import { canReach } from '@/lib/reach'
import { useSessionStore } from '@/stores/session'

const router = useRouter()
const session = useSessionStore()

/**
 * 消费者形态（07 §五）：`shared` 范围 = 只用别人做好的。
 * 本页对他是「能用什么 + 我该去哪」，不是「平台运营成什么样」。
 */
const consumer = computed(() => session.dataScope === 'shared')

/** 首页固定看近 7 天 —— 需要别的区间去运营看板，那里才是「按区间深看」的地方。 */
const rangeDays = 7

const loading = ref(false)
const overview = ref<OverviewMetrics | null>(null)
const quality = ref<QualityMetrics | null>(null)
const apps = ref<AssistantRankItem[]>([])
const mySummary = ref<MeSummary | null>(null)
const myConversations = ref<Conversation[]>([])
const myFeedbacks = ref<FeedbackItem[]>([])

const range = computed<AnalyticsRange>(() => {
  const to = dayjs()
  const from = to.subtract(rangeDays - 1, 'day')
  return { from: from.format('YYYY-MM-DD'), to: to.format('YYYY-MM-DD') }
})

/**
 * 按口径拉数：消费者走 `me/summary` + `mine=true`，建设者/运营走平台口径。
 * 两套口径是**不同的接口**，不可互换（07 §五）。
 */
const fetchAll = async () => {
  loading.value = true
  try {
    const q = range.value
    if (consumer.value) {
      const [s, convs, fbs] = await Promise.all([
        analyticsApi.getMySummary(q),
        conversationsApi.listConversations({ mine: true, size: 5 }),
        feedbackApi.listFeedbacks({ mine: true, size: 5 })
      ])
      mySummary.value = s
      myConversations.value = convs.list
      myFeedbacks.value = fbs.list
    } else {
      const [o, a, m] = await Promise.all([
        analyticsApi.getOverview(q),
        analyticsApi.listTopAssistants(q, 5),
        analyticsApi.getQualityMetrics(q)
      ])
      overview.value = o
      apps.value = a
      quality.value = m
    }
  } finally {
    loading.value = false
  }
}

onMounted(fetchAll)

/**
 * 入门入口。**按权限 + 数据范围过滤** —— 判据走 `canReach()`（与顶栏、命令面板、路由守卫同源），
 * 否则无权限的角色会看到通向 403 的入口：那是比「没有入口」更坏的体验。
 */
const buildEntries = [
  { path: '/knowledge', label: '建知识库', desc: '上传文档、切分入库，供应用检索' },
  { path: '/assistants', label: '建应用', desc: '把模型、知识库与提示词组装成对话服务' },
  { path: '/analytics', label: '看运营数据', desc: '对话趋势、应用排行与质量观测' },
  { path: '/feedback', label: '处理反馈', desc: '用户点赞点踩与处理闭环' }
]

/**
 * 消费者看到的是**另一组入口**，不是同一组里少几项（07 §五 判据 1）。
 *
 * 上面四个入口全是「建 / 处理」动作：按 canReach 过滤后消费者只剩「建应用」一项 ——
 * 那仍然是一个建设入口，读起来还是"工地"。所以整块换成他要的两个动作：去问、看看有什么能用。
 */
const consumerEntries = [
  { path: '/conversation', label: '开始对话', desc: '选一个应用直接提问，答案带引用' },
  { path: '/market', label: '应用市场', desc: '看看现在有哪些应用可以用' }
]

const entries = computed(() =>
  (consumer.value ? consumerEntries : buildEntries).filter((e) => canReach(router, e.path))
)

/**
 * 机器回执的读数行 —— 消费者换「我的」读数（07 §五），建设者/运营用平台口径。
 * 数据必须真实、不编数字（仪器面板的第一条纪律）。
 * 空数组时组件什么都不渲染（不画占位、不画 0）—— 「没测到」显示「—」是本工程的通则。
 */
const instrumentRows = computed<InstrumentRow[]>(() => {
  if (consumer.value) {
    const s = mySummary.value
    if (!s) return []
    return [
      { label: '我的提问', value: s.myQuestionCount.toLocaleString(), hint: '近 7 天' },
      { label: '我的对话', value: s.myConversationCount.toLocaleString(), hint: '近 7 天' },
      { label: '我的赞 / 踩', value: `${s.myUp} / ${s.myDown}`, hint: '赞踩互斥且可撤销' },
      { label: '最近活跃', value: s.lastActiveAt ?? '—' }
    ]
  }
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
 * 状态胶囊**由真实读数推导**，不是装饰。
 * 建设者/运营：阈值取自验收口径 B4（首字延迟 ≤ 10 s）。
 * 消费者：踩多于赞时给 WARN —— 他对答案不满意，需要关注。
 */
const instrumentStatus = computed<InstrumentStatus>(() => {
  if (consumer.value) {
    const s = mySummary.value
    if (!s) return 'OK'
    return s.myDown > s.myUp ? 'WARN' : 'OK'
  }
  return (quality.value?.firstTokenLatencyP50 ?? 0) > 10000 ? 'WARN' : 'OK'
})

/** 反馈状态的视觉映射：与反馈页共用同一套语义 */
const feedbackStatusClass = (status: FeedbackStatus) => {
  if (status === '已处理') return 'st-ok'
  if (status === '已忽略') return 'st-err'
  if (status === '待处理') return 'st-warn'
  return ''
}

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

/* 两栏不等宽：常用应用/我的对话是主体，入门入口是窄栏 —— 不搞等宽双栏那种模板感 */
.cols {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: var(--space-md);
  align-items: start;
}

/* 消费者左栏：两块纵向排列 */
.consumer-main {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  min-width: 0;
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

/* 反馈状态/赞踩标签 */
.vote-tag {
  flex-shrink: 0;
  font-size: var(--text-2xs);
  font-weight: 500;
}

.vote-tag.up {
  color: var(--color-ok);
}

.vote-tag.down {
  color: var(--color-danger);
}

.status-tag {
  flex-shrink: 0;
  font-size: var(--text-2xs);
  padding: 0 var(--space-2xs);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-xs);
  color: var(--color-muted);
}

.status-tag.st-ok {
  color: var(--color-ok);
  border-color: var(--color-ok);
}

.status-tag.st-warn {
  color: var(--color-warn);
  border-color: var(--color-warn);
}

.status-tag.st-err {
  color: var(--color-danger);
  border-color: var(--color-danger);
}
</style>
