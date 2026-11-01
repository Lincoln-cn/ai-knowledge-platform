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

    <!--
      「没有可用模型 → 去模型管理」（`ROADMAP §五` F2 的第一步）。
      此前这句话只散落在局部：建库向导筛候选、应用发布前置、编排节点校验 ——
      每一处只看自己那个动作，**没有一个地方回答「这个平台现在能不能干活」**。
      全新用户看到的是一个信息密度很高的工作台，却不知道第一步缺什么。

      判据全部在 `lib/modelReadiness.ts`（含「什么不该显示」两条）：
      拉取失败不显示、用户修不了不显示。文案按缺哪一档分开 —— 缺对话是提问失败，
      缺向量是建库/入库失败，两件事的下一步不一样（一个去加载模型，一个去登记）。
    -->
    <div v-if="readinessNotice" :class="['notice', readinessNotice.tone]">
      {{ readinessNotice.text }} ——
      <router-link to="/models" class="link">去模型管理</router-link>
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
                <span class="vote-tag" :class="f.type === 'UP' ? 'up' : 'down'">
                  {{ f.type === 'UP' ? '赞' : '踩' }}
                </span>
                <span class="app-name" :title="f.question">{{ f.question }}</span>
                <span class="status-tag" :class="feedbackStatusClass(f.status)">{{ labelOf(FEEDBACK_STATUS_LABELS, f.status) }}</span>
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

      <!--
        首次引导（`ROADMAP §五` F2）：还有没走完的步骤时显示「上手路径」+ 进度；
        四步都做完就退回原来的入口列表 —— 引导条不许变成常驻横幅
        （什么时候该消失的判据在 `lib/onboarding.ts`，不在模板里）。
        两种形态都按权限过滤：不归他管的步骤整条不出现
        （「你没有知识库」+ 无权建库 = 一个解不开的暗号）。
      -->
      <section class="panel">
        <div class="panel-head">
          <h2 class="h2">{{ onboarding ? '上手路径' : '从这里开始' }}</h2>
          <span v-if="onboarding" class="micro muted">
            已完成 {{ onboarding.doneCount }}/{{ onboarding.totalCount }}
          </span>
        </div>
        <ul v-if="onboarding" class="entries">
          <li v-for="s in onboarding.steps" :key="s.key" class="step-row" :class="s.status">
            <span
              class="step-mark"
              :title="
                s.status === 'done' ? '已完成' : s.status === 'todo' ? '待完成' : '暂未读到读数'
              "
              >{{ s.status === 'done' ? '✓' : s.status === 'todo' ? '○' : '—' }}</span
            >
            <router-link :to="s.path" class="entry">
              <span class="entry-name">{{ s.label }}</span>
              <span class="entry-desc micro muted">{{ s.desc }}</span>
            </router-link>
          </li>
        </ul>
        <ul v-else class="entries">
          <li v-for="e in entries" :key="e.path" class="entry-row">
            <router-link :to="e.path" class="entry">
              <span class="entry-name">{{ e.label }}</span>
              <span class="entry-desc micro muted">{{ e.desc }}</span>
            </router-link>
          </li>
        </ul>
        <!--
          显示路径**不等于**取消入口：路径之外那两条（看运营数据、处理反馈）降级成一行文字链接。
          少了这一行，一个还没建完库的管理员就再也找不到「处理反馈」了。
        -->
        <div v-if="onboarding && otherEntries.length" class="other-links">
          <span class="micro muted">其他</span>
          <router-link v-for="e in otherEntries" :key="e.path" :to="e.path" class="link">
            {{ e.label }}
          </router-link>
        </div>
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
import { analyticsApi, assistantsApi, conversationsApi, feedbackApi, knowledgeApi, modelsApi } from '@/api'
import type { AnalyticsRange, AssistantRankItem, MeSummary, OverviewMetrics, QualityMetrics } from '@/api/analytics'
import type { Conversation } from '@/api/conversations'
import type { FeedbackItem, FeedbackStatus } from '@/api/feedback'
import InstrumentCard, { type InstrumentRow, type InstrumentStatus } from '@/components/InstrumentCard.vue'
import { PERM } from '@/constants/permissions'
import { canReach } from '@/lib/reach'
import { assessModelReadiness, modelReadinessNotice, type ModelReadiness } from '@/lib/modelReadiness'
import {
  buildOnboarding,
  ONBOARDING_STEP_PATHS,
  type OnboardingCounts,
  type OnboardingStepKey,
  type OnboardingView
} from '@/lib/onboarding'
import { useSessionStore } from '@/stores/session'
import { FEEDBACK_STATUS_LABELS, labelOf } from '@/constants/labels'

const router = useRouter()
const session = useSessionStore()

/**
 * 消费者形态（07 §五）：`shared` 范围 = 只用别人做好的。
 * 本页对他是「能用什么 + 我该去哪」，不是「平台运营成什么样」。
 */
const consumer = computed(() => session.dataScope === 'SHARED')

/** 首页固定看近 7 天 —— 需要别的区间去运营看板，那里才是「按区间深看」的地方。 */
const rangeDays = 7

const loading = ref(false)
const overview = ref<OverviewMetrics | null>(null)
const quality = ref<QualityMetrics | null>(null)
const apps = ref<AssistantRankItem[]>([])
const mySummary = ref<MeSummary | null>(null)
const myConversations = ref<Conversation[]>([])
const myFeedbacks = ref<FeedbackItem[]>([])

/**
 * 模型可用性。**单独加载、单独失败**（`null` = 不知道）。
 *
 * ★ 刻意不放进下面 `fetchAll` 的 `Promise.all`：引导条是锦上添花，
 * 它拉不到不许连坐工作台的三块指标。同理，它在 `fetchAll` 里也只是被
 * `void` 掉 —— 指标等它，等于让一个次要读数拖慢首屏。
 */
const modelReadiness = ref<ModelReadiness | null>(null)

/**
 * 「这个用户能不能自己把模型补上」—— 与 `buildEntries` 同一套双重过滤：
 * `canReach` 判页面进得去（`/models` 要 `model:manage`），`session.can` 判动作做得了。
 * 只判一条就是空承诺：看得到「去模型管理」，点进去 403。
 */
const canManageModels = () => canReach(router, '/models') && session.can(PERM.modelManage)

const readinessNotice = computed(() => modelReadinessNotice(modelReadiness.value, canManageModels()))

const loadModelReadiness = async () => {
  // 修不了的人不拉：省两次请求。★ 判据本身仍在纯函数里，这里只是短路。
  if (!canManageModels()) return
  try {
    // 按 kind 分两次各拉满一页（后端上限 100，见 `ModelService.MAX_PAGE_SIZE`）。
    // 用 kind 过滤而不是拉全部再筛 —— 否则模型总数超过一页时会误报「缺」。
    const [chatPage, embeddingPage] = await Promise.all([
      modelsApi.listModels({ kind: 'CHAT', size: 100 }),
      modelsApi.listModels({ kind: 'EMBEDDING', size: 100 })
    ])
    modelReadiness.value = assessModelReadiness([...chatPage.list, ...embeddingPage.list])
  } catch {
    // 「不知道」≠「没有」：拉不到就什么都别说（否则一个健康平台会顶着假故障提示）
    modelReadiness.value = null
  }
}

/**
 * 首次引导（`ROADMAP §五` F2）：「建知识库 → 传文档 → 建应用 → 试问答」四步 + 进度。
 * 判据（含"什么时候该消失"）在 `lib/onboarding.ts`，这里只负责取四个读数。
 *
 * 只对建设者：消费者有他自己的一组入口（`consumerEntries`），他不需要"先建个库"。
 */
const onboarding = ref<OnboardingView | null>(null)

/** 「这一步归不归他管」—— 与入口列表同一套双重过滤（路由可达 + 动作权限） */
const onboardingAllowed = (key: OnboardingStepKey): boolean => {
  switch (key) {
    case 'kb':
      return canReach(router, '/knowledge') && session.can(PERM.knowledgeCreate)
    case 'document':
      return canReach(router, '/knowledge') && session.can(PERM.documentUpload)
    case 'assistant':
      return canReach(router, '/assistants') && session.can(PERM.assistantEdit)
    case 'ask':
      return canReach(router, '/conversation') && session.can(PERM.conversationView)
  }
}

/** 单块失败不许连坐另外几块：`null` 就是"没测到"（判据会把它算成 unknown，不算未完成） */
const settleCount = async <T>(fn: () => Promise<T>): Promise<T | null> => {
  try {
    return await fn()
  } catch {
    return null
  }
}

const loadOnboarding = async () => {
  if (consumer.value) return
  const [kbPage, appPage, askPage] = await Promise.all([
    // 取满一页：`documentCount` 要逐库求和，「传文档」才判得出来。
    // ★ 超过 100 个库时求和会偏小 —— 但判据是「有没有 > 0」，只要前 100 个库里有文档就算做完。
    settleCount(() => knowledgeApi.listKnowledgeBases({ page: 1, size: 100 })),
    settleCount(() => assistantsApi.listAssistants({ page: 1, size: 1 })),
    // `mine`：这一步问的是「**你**试过问答没有」，不是平台有没有人问过
    settleCount(() => conversationsApi.listConversations({ mine: true, page: 1, size: 1 }))
  ])
  // ★ 用 `total` 而不是 `list.length`：`size: 1` 时 list 恒长 1，按它判会人人"已完成"
  const counts: OnboardingCounts = {
    kbCount: kbPage ? kbPage.total : null,
    docCount: kbPage ? kbPage.list.reduce((n, kb) => n + (kb.documentCount ?? 0), 0) : null,
    appCount: appPage ? appPage.total : null,
    askCount: askPage ? askPage.total : null
  }
  onboarding.value = buildOnboarding(counts, onboardingAllowed)
}

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
    // 模型状态跟着「刷新」一起更新，但不参与上面的等待（互不连坐）。
    // ★ 放在 finally 里：指标接口挂了也要刷新它 —— 两块的成败必须互不相干，
    //   否则「后端只挂了统计接口」会连带让引导条停在旧读数上。
    void loadModelReadiness()
    void loadOnboarding()
  }
}

onMounted(fetchAll)

/**
 * 入门入口。**按路由可达性 + 动作权限双重过滤** ——
 * `canReach()` 判「页面进得去」（路由 meta.permission 与 dataScope），
 * `session.can(perm)` 判「进去后**那个动作**做得了」。
 *
 * 为什么两道都要：路由门禁是**页面级**（如 `/knowledge` 要 `knowledge:view`），
 * 而入口承诺的是**动作**（建知识库要 `knowledge:create`）。只判前者就是
 * 「空承诺」：知识运营看得到「建应用」、点进去却没有「新建应用」按钮。
 * 与 `lib/reach.ts` 同族的理由 —— 用户看到通向「做不到」的入口，比没有入口更坏。
 */
const buildEntries = [
  {
    path: '/knowledge',
    label: '建知识库',
    desc: '上传文档、切分入库，供应用检索',
    perm: PERM.knowledgeCreate
  },
  {
    path: '/assistants',
    label: '建应用',
    desc: '把模型、知识库与提示词组装成对话服务',
    perm: PERM.assistantEdit
  },
  {
    path: '/analytics',
    label: '看运营数据',
    desc: '对话趋势、应用排行与质量观测',
    perm: PERM.dashboardView
  },
  {
    path: '/feedback',
    label: '处理反馈',
    desc: '用户点赞点踩与处理闭环',
    perm: PERM.feedbackHandle
  }
]

/**
 * 消费者看到的是**另一组入口**，不是同一组里少几项（07 §五 判据 1）。
 *
 * 上面四个入口全是「建 / 处理」动作：按 canReach 过滤后消费者只剩「建应用」一项 ——
 * 那仍然是一个建设入口，读起来还是"工地"。所以整块换成他要的两个动作：去问、看看有什么能用。
 */
const consumerEntries = [
  {
    path: '/conversation',
    label: '开始对话',
    desc: '选一个应用直接提问，答案带引用',
    perm: PERM.conversationView
  },
  {
    path: '/market',
    label: '应用市场',
    desc: '看看现在有哪些应用可以用',
    perm: PERM.assistantView
  }
]

const entries = computed(() =>
  (consumer.value ? consumerEntries : buildEntries).filter(
    (e) => canReach(router, e.path) && session.can(e.perm)
  )
)

/**
 * 上手路径显示时，把**路径里已经有的入口**从入口列表里摘掉 —— 否则「建知识库」在页面上出现两次。
 * 剩下的（看运营数据、处理反馈）不是首次引导的动作，但也不能因此消失：
 * 显示路径不等于取消入口。它们降级成路径下方的一行文字链接。
 */
const otherEntries = computed(() => {
  const inPath = new Set(ONBOARDING_STEP_PATHS)
  return entries.value.filter((e) => !inPath.has(e.path))
})

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
  if (status === 'RESOLVED') return 'st-ok'
  if (status === 'IGNORED') return 'st-err'
  if (status === 'PENDING') return 'st-warn'
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

/*
 * 上手路径：四步 + 状态标记。
 * 标记只用文字与令牌色，不引彩色块 —— 四步不该长成一块仪表盘。
 */
.step-row {
  display: flex;
  align-items: flex-start;
}

.step-row .entry {
  flex: 1;
  min-width: 0;
}

.step-row:last-child .entry {
  border-bottom: none;
}

.step-mark {
  flex-shrink: 0;
  width: 2.4em;
  padding: var(--space-xs) 0 var(--space-xs) var(--space-md);
  color: var(--color-muted);
  text-align: center;
}

.step-row.done .step-mark {
  color: var(--color-accent);
}

/* 做完的一步退到次要色：剩下的还没做的才是要他看的 */
.step-row.done .entry-name {
  color: var(--color-muted);
}

/* 路径之外的其他入口：一行文字链接，不占一块 panel */
.other-links {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-md);
  padding: var(--space-xs) var(--space-md);
  border-top: 1px solid var(--color-rule-2);
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
