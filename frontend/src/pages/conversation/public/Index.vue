<template>
  <div class="public-page">
    <!-- 凭证校验失败：可读原因 + 下一步，不静默 -->
    <div v-if="gateError" class="gate">
      <div class="card panel">
        <el-icon class="gate-icon"><Link /></el-icon>
        <h2 class="gate-title">{{ gateError.reason }}</h2>
        <p v-if="gateError.hint" class="micro muted">{{ gateError.hint }}</p>
        <div class="notice info micro">
          若你是访问者，请向分享者索取新的公开链接；若你是管理员，可到「应用管理 → 访问凭证」重新生成。
        </div>
      </div>
    </div>

    <template v-else-if="context">
      <TopBar
        :title="context.appName"
        :subtitle="'公开问答 · 免登录'"
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
      </div>

      <div class="body rise">
        <section class="center panel">
          <MessageList
            ref="msgListRef"
            :messages="chat.messages.value"
            :loading="chat.loadingHistory.value"
            :load-error="chat.loadError.value"
            :empty-text="context.prologue || '开始提问吧'"
            :bot-icon="context.icon || 'AI'"
            :public-mode="true"
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
import TopBar from '../components/TopBar.vue'
import MessageList from '../components/MessageList.vue'
import Composer from '../components/Composer.vue'
import RefPanel from '../components/RefPanel.vue'
import {
  errText,
  toRefEx,
  useChatSession,
  type AttachmentDraft,
  type SourceRefEx
} from '../composables/useChatSession'

const route = useRoute()
const router = useRouter()

const token = computed(() => String(route.params.token || ''))
const context = ref<PublicContext | null>(null)
const gateError = ref<{ reason: string; hint?: string } | null>(null)

const chat = useChatSession({
  credentialToken: token.value || undefined
})

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
  try {
    await navigator.clipboard.writeText(msg.content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

const onShare = async (msg: ChatMessage) => {
  try {
    await navigator.clipboard.writeText(`${window.location.href}\n\n${msg.content}`)
    ElMessage.success('已复制链接与内容')
  } catch {
    ElMessage.warning('复制失败，请手动复制地址栏链接')
  }
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
  try {
    context.value = await conversationsApi.resolvePublicContext(token.value)
    await chat.loadHistory(token.value)
  } catch (error) {
    gateError.value = errText(error, '访问凭证无效')
  }
})

watch(token, () => {
  // token 变化（理论上少见）时重新校验
  gateError.value = null
  context.value = null
  conversationsApi
    .resolvePublicContext(token.value)
    .then(async (ctx) => {
      context.value = ctx
      await chat.loadHistory(token.value)
    })
    .catch((error) => {
      gateError.value = errText(error, '访问凭证无效')
    })
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

/* 页头：本页唯一一次深色石墨拍子 */
.app-info {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  margin: var(--space-sm) var(--space-sm) 0;
  flex-shrink: 0;
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

.card {
  max-width: 420px;
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

.gate-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-md);
  font-weight: 600;
  line-height: var(--leading-title);
  color: var(--color-ink);
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
