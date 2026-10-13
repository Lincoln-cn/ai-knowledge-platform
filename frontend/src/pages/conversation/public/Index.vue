<template>
  <div class="public-page">
    <!--
      凭证失效 / 无权限：★ **四态分开呈现**（状态标签 + 为什么 + 怎么办），
      绝不合并成一句"链接无效" —— 已停用可以恢复、已撤销不能，合并会让人按错的假设去等。
      文案真源 = `utils/public-gate.ts`（按后端业务码分四态）；
      应用侧凭证表格的四态文案在 `utils/credential-state.ts` —— 两处**说法同源**：
      已撤销 ⇒ "终态，只能重新生成"、已停用 ⇒ "可再启用"，但措辞各自面向的人不同
      （这里面向访问者，那里面向管理员）。
    -->
    <div v-if="gateError" class="gate">
      <div class="card panel">
        <el-icon class="gate-icon"><Link /></el-icon>
        <div class="gate-status">{{ gateError.status }}</div>
        <h2 class="gate-title">{{ gateError.reason }}</h2>
        <p class="gate-hint">{{ gateError.hint }}</p>
        <div class="notice info">
          <b>怎么办：</b>{{ gateError.action }}
        </div>
        <div class="micro muted gate-extra">
          免登录链接不是"内容快照"，而是一张受限的访问凭证：每次打开都按它冻结的密级上限重新过滤一遍。
          因此它随时可能被停用、撤销或过期，也可能因内容升密而立即失效 —— 这都不是故障。
        </div>
      </div>
    </div>

    <template v-else-if="context">
      <header class="head-band">
        <!-- 页头 section 底 —— 整页只此一片（design.md § Art layer）。 -->
        <span class="head-wash" aria-hidden="true"></span>

        <TopBar
          :title="context.appName"
          :subtitle="'公开对话 · 免登录'"
          :public-mode="true"
          :show-kb-link="false"
          @back="onBack"
        />

        <div class="app-info panel">
          <div class="icon">{{ context.icon }}</div>
          <div class="meta">
            <div class="name">{{ context.appName }}</div>
            <div v-if="context.description" class="desc micro muted">{{ context.description }}</div>
          </div>
          <!--
            ★ 冻结密级上限：让访客知道"这条链接能读到什么级别"（`13 §8.4` 的界面口径）。
            `PublicContext.maxClassification` 在契约里是**可选**字段（注释写明"后端必须返回"）——
            所以这里按可选读：拿得到就显示，拿不到就整块不渲染。
            ★ 不用应用当前的密级冒充，更不把它当字符串直接渲染（那就是"状态码当文案"）。
          -->
          <div v-if="ceiling" class="ceiling">
            <ClassificationBadge :level="ceiling" :labels="CLASSIFICATION_LABELS" />
            <span class="micro muted">你看不到高于这个级别的内容</span>
          </div>
        </div>
      </header>

      <div class="body rise">
        <section class="center panel">
          <!--
            ★ 凭证没问题但消息读失败：**不能**退化成"打开失败"整页，
              否则一个可分页重试的故障会被读成"链接坏了"。这里就地给原因 + 重试。
          -->
          <div v-if="chat.loadError.value" class="history-fail">
            <div class="notice err">
              <div><b>对话记录加载失败：</b>{{ chat.loadError.value.reason }}</div>
              <div v-if="chat.loadError.value.hint" class="micro">{{ chat.loadError.value.hint }}</div>
              <el-button size="small" class="mt" @click="reload">重试</el-button>
            </div>
          </div>

          <MessageList
            v-else
            ref="msgListRef"
            :messages="chat.messages.value"
            :loading="chat.loadingHistory.value"
            :load-error="chat.loadError.value"
            :empty-text="context.prologue || '开始提问吧'"
            :bot-icon="context.icon || 'AI'"
            :public-mode="true"
            :can-share="true"
            :active-ref-index="activeRefIndex"
            @cite="onCite"
            @copy="onCopy"
            @regenerate="onRegenerate"
            @continue="onContinue"
            @share="onShare"
            @suggest="onSuggest"
            @retry="reload"
          />

          <!-- 推荐问题 chip（空会话时展示开场推荐） -->
          <div v-if="!chat.messages.value.length && context.quickQuestions.length" class="quick">
            <div class="micro muted">试试这些问题</div>
            <div class="chips">
              <button
                v-for="q in context.quickQuestions"
                :key="q"
                type="button"
                class="chip"
                @click="onSuggest(q)"
              >
                {{ q }}
              </button>
            </div>
          </div>

          <Composer
            ref="composerRef"
            :generating="chat.generating.value"
            :placeholder="'输入问题，Enter 发送 / Shift+Enter 换行'"
            @send="onSend"
            @stop="chat.stop"
          />
        </section>

        <aside v-if="wide && activeRefs.length" class="ref-side panel">
          <RefPanel
            :refs="activeRefs"
            :active-index="activeRefIndex"
            @select="(n) => (activeRefIndex = n)"
            @jump="jumpToSegment"
          />
        </aside>
      </div>

      <el-drawer v-model="drawerVisible" title="引用来源" size="360px">
        <RefPanel
          :refs="activeRefs"
          :active-index="activeRefIndex"
          closable
          @close="drawerVisible = false"
          @select="(n) => (activeRefIndex = n)"
          @jump="jumpToSegment"
        />
      </el-drawer>

      <div v-if="chat.streamError.value" class="stream-fail notice err">
        <span><b>生成中断：</b>{{ chat.streamError.value.reason }}</span>
        <span v-if="chat.streamError.value.hint" class="micro">{{ chat.streamError.value.hint }}</span>
        <el-button size="small" @click="chat.streamError.value = null">知道了</el-button>
      </div>
    </template>

    <div v-else class="gate">
      <div class="card panel">
        <el-icon class="gate-icon"><Loading /></el-icon>
        <p class="micro muted">正在校验访问凭证…</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Link, Loading } from '@element-plus/icons-vue'
import { conversationsApi } from '@/api'
import type { ChatMessage, PublicContext } from '@/api/conversations'
import type { ClassificationLevel } from '@/api/types'
import ClassificationBadge from '@/components/ClassificationBadge.vue'
import { CLASSIFICATION_LABELS } from '@/utils/classification'
import { publicGateError, type PublicGateError } from '@/utils/public-gate'
import { copyText } from '@/utils/clipboard'
import TopBar from '../components/TopBar.vue'
import MessageList from '../components/MessageList.vue'
import Composer from '../components/Composer.vue'
import RefPanel from '../components/RefPanel.vue'
import {
  toRefEx,
  useChatSession,
  type AttachmentDraft,
  type SourceRefEx
} from '../composables/useChatSession'

const route = useRoute()
const router = useRouter()

const token = computed(() => String(route.params.token || ''))
const context = ref<PublicContext | null>(null)
const gateError = ref<PublicGateError | null>(null)

const chat = useChatSession({
  credentialToken: token.value || undefined
})

/* ★ 冻结密级上限：只有**这一条链接**的冻结值才算数，不用应用当前的密级冒充 */
const VALID_LEVELS: ClassificationLevel[] = ['PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL']
const ceiling = ref<ClassificationLevel | null>(null)

/**
 * 读 `PublicContext.maxClassification`（契约里是可选字段）。
 * ★ 仍**逐值校验**再显示：后端理论上必须给四档之一，但万一给了别的字符串，
 *   直接渲染出去就是把机器码当文案（本项目栽过）。不认识 ⇒ 整块不渲染。
 */
const readCeiling = (raw: PublicContext): ClassificationLevel | null => {
  const value = raw.maxClassification
  return typeof value === 'string' && (VALID_LEVELS as string[]).includes(value)
    ? (value as ClassificationLevel)
    : null
}

const activeRefIndex = ref<number | null>(null)
const activeMessage = ref<ChatMessage | null>(null)
const drawerVisible = ref(false)
const wide = ref(true)
const composerRef = ref<InstanceType<typeof Composer>>()

const activeRefs = computed(() => {
  const msg = activeMessage.value ?? chat.lastAssistant.value
  return (msg?.refs ?? []).map(toRefEx)
})

const onCite = (msg: ChatMessage, index: number) => {
  activeMessage.value = msg
  activeRefIndex.value = index
  if (!wide.value) drawerVisible.value = true
}

const jumpToSegment = (ref: SourceRefEx) => {
  const query: Record<string, string> = {}
  if (ref.documentId) query.doc = ref.documentId
  if (ref.segmentId) query.segment = ref.segmentId
  if (ref.kbId) query.kb = ref.kbId
  router.push({ path: '/knowledge', query })
}

const onEsc = (e: KeyboardEvent) => {
  if (e.key === 'Escape') drawerVisible.value = false
}

const mq = window.matchMedia('(min-width: 1100px)')
const syncWide = () => {
  wide.value = mq.matches
  if (wide.value) drawerVisible.value = false
}
mq.addEventListener('change', syncWide)

const reload = () => {
  if (token.value) chat.loadHistory(token.value)
}

/**
 * 一次门禁 + 取数。`resolvePublicContext` 与 `loadHistory` **分开 try**：
 * 前者失败 = 链接这一态（四态文案）；后者失败 = 记录读不到（可就地重试），
 * 两者合并成一个 catch 会把"暂时读不到记录"说成"链接无效"。
 */
const gate = async () => {
  gateError.value = null
  context.value = null
  ceiling.value = null
  try {
    const ctx = await conversationsApi.resolvePublicContext(token.value)
    context.value = ctx
    ceiling.value = readCeiling(ctx)
  } catch (error) {
    gateError.value = publicGateError(error)
    return
  }
  await chat.loadHistory(token.value)
}

const onSend = async (text: string, attachments: AttachmentDraft[]) => {
  // 免登录会话 id 由 token 决定，后端按凭证隔离
  await chat.send({
    conversationId: `public:${token.value}`,
    question: text,
    attachments
  })
}

const onRegenerate = (msg: ChatMessage) => {
  activeMessage.value = msg
  chat.regenerate(msg)
}

const onContinue = (msg: ChatMessage) => {
  activeMessage.value = msg
  chat.continueGenerate(msg)
}

const onCopy = async (msg: ChatMessage) => {
  const ok = await copyText(msg.content)
  if (ok) ElMessage.success('已复制')
  else ElMessage.warning('复制失败，请手动选中复制')
}

const onShare = async (msg: ChatMessage) => {
  const ok = await copyText(`${window.location.href}\n\n${msg.content}`)
  if (ok) ElMessage.success('已复制链接与内容')
  else ElMessage.warning('复制失败，请手动复制地址栏链接')
}

const onSuggest = (text: string) => {
  composerRef.value?.sendText(text)
}

const onBack = () => {
  // 公开页无平台导航，返回即留在本页或回首页
  router.push('/')
}

onMounted(async () => {
  syncWide()
  document.addEventListener('keydown', onEsc)
  await gate()
})

watch(token, () => {
  // token 变化（理论上少见）时重新校验
  void gate()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onEsc)
  mq.removeEventListener('change', syncWide)
  if (chat.generating.value) chat.stop()
})
</script>

<style scoped>
.public-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--color-paper);
  position: relative;
}

/* 页头 section —— 本页唯一一片色场，也是唯一一次深色石墨拍子。

   ★ 这一节**故意不写 isolation**：multiply 的合成对象必须落在页面的冷调纸面上。
     实测：把本 section 隔离成独立合成组后，组内画布是透明的 ⇒ multiply 退化成
     "直接贴一张 60% 的图"，颜色发白发粉，且不再随纸面色阶变化。

   ★ TopBar 也放进本 section，并在下面把它的 surface 底透掉 ——
     第一版只把 wash 铺在石墨卡片周围那一圈，实测**开/关色块全页平均像素差
     0.11/255**（= 肉眼看不见）。色场的面积太小，等于没放。 */
.head-band {
  position: relative;
  flex-shrink: 0;
  padding: var(--space-sm) var(--space-md) var(--space-lg);
}

/* 色场：偏左上、比 section 更大、multiply、不透明度取硬上限 0.6。
   `0% 13%` 不是估的 —— 对 7 组候选（位置 × 尺寸 × 羽化开关）做了截图像素差实测，
   这一组在 band 内的平均差最高（1.50/255、最大差 24/255），读数见交付报告。
   mask 那一条只做一件事：让色场的下沿**羽化**收掉，不在纸面上留一条
   被裁直的水彩色边（那会读成"贴了两张纸"。mask 里的 black/transparent
   是遮罩的 alpha，不是颜色 —— 不占色板。 */
.head-wash {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-image: var(--art-wash);
  background-repeat: no-repeat;
  background-position: 0% 13%;
  background-size: 118% auto;
  mix-blend-mode: multiply;
  opacity: var(--art-opacity-wash);
  mask-image: linear-gradient(to bottom, black 78%, transparent);
  -webkit-mask-image: linear-gradient(to bottom, black 78%, transparent);
}

/* 顶栏的 surface 底透掉，让 bar 与石墨卡片共享同一片色场；
   它自己带一条底部发丝线，分面照旧。 */
.head-band :deep(.topbar) {
  position: relative;
  z-index: 1;
  background: transparent;
}

.app-info {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  background: var(--color-graphite);
  border: 1px solid var(--color-graphite-rule);
  border-radius: var(--radius-lg);
}

.icon {
  width: 32px;
  height: 32px;
  border-radius: var(--radius);
  background: var(--color-graphite-2);
  color: var(--color-graphite-ink);
  display: grid;
  place-items: center;
  font-size: var(--text-sm);
  font-weight: 600;
}

.name {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-graphite-ink);
  line-height: var(--leading-title);
}

.desc {
  margin-top: var(--space-3xs);
  color: var(--color-graphite-muted);
}

.body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: var(--space-sm);
  padding: var(--space-sm);
}

/* 唯一一次入场 */
.body.rise {
  animation-delay: 60ms;
}

.center {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.ref-side {
  width: 280px;
  flex-shrink: 0;
  min-height: 0;
  overflow: hidden;
}

.quick {
  padding: 0 var(--space-lg) var(--space-sm);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
  margin-top: var(--space-xs);
}

.chip {
  border: 1px solid var(--color-rule);
  background: var(--color-surface);
  color: var(--color-ink-2);
  font-size: var(--text-2xs);
  font-family: inherit;
  line-height: var(--leading-tight);
  padding: var(--space-2xs) var(--space-sm);
  border-radius: var(--radius);
  cursor: pointer;
  transition:
    border-color var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.chip:hover {
  border-color: var(--color-accent-line);
  color: var(--color-accent);
}

.gate {
  flex: 1;
  display: grid;
  place-items: center;
  padding: var(--space-lg);
}

/* 卡片比默认 420px 宽一档：四段文字（状态 / 为什么 / 怎么办 / 说明）挤在 420px 里
   会变成一堵墙，反而没人读 */
.card {
  max-width: 520px;
  padding: var(--space-lg);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
}

.gate-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-lg);
  background: var(--color-sunken);
  color: var(--color-muted);
  display: grid;
  place-items: center;
  font-size: var(--text-lg);
}

/* 状态标签：四态在卡片最上面一行就分开，不与"为什么"揉进同一句 */
.gate-status {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  letter-spacing: var(--tracking-label);
  color: var(--color-muted);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm);
  padding: var(--space-3xs) var(--space-xs);
  background: var(--color-sunken);
}

.gate-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-md);
  font-weight: 600;
  line-height: var(--leading-title);
  color: var(--color-ink);
}

.gate-hint {
  margin: 0;
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-ink-2);
  text-align: left;
}

.gate-extra {
  text-align: left;
  line-height: var(--leading-body);
}

.gate .notice {
  text-align: left;
}

.ceiling {
  margin-left: auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-3xs);
  flex-shrink: 0;
}

.history-fail {
  padding: var(--space-md);
}

.history-fail .mt {
  margin-top: var(--space-xs);
}

.stream-fail {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: var(--space-3xl);
  z-index: var(--z-sticky);
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  box-shadow: var(--shadow-float);
  max-width: 80%;
}
</style>
