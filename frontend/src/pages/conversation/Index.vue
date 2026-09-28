<template>
  <div class="chat-page">
    <TopBar
      :title="headerTitle"
      :subtitle="headerSubtitle"
      :app-id="appBrief?.id ?? appId"
      :public-mode="false"
      :show-share="canShare"
      @back="onBack"
      @share="openShare()"
    />

    <!-- 应用信息区：仅应用模式展示 -->
    <AppInfoBar v-if="appBrief" :app="appBrief" />

    <div class="body">
      <HistoryPanel
        :groups="history.groups.value"
        :loading="history.loading.value"
        :load-error="history.loadError.value"
        :active-id="activeConversation?.id ?? null"
        @select="openConversation"
        @remove="onRemoveConv"
        @rename="onRenameConv"
        @create="onCreateConv"
        @reload="loadHistory"
      />

      <section class="center panel">
        <MessageList
          ref="msgListRef"
          :messages="chat.messages.value"
          :loading="chat.loadingHistory.value"
          :load-error="chat.loadError.value"
          :empty-text="emptyText"
          :bot-icon="appBrief?.icon || 'AI'"
          :active-ref-index="activeRefIndex"
          :can-share="canShare"
          :has-earlier="chat.hasEarlier.value"
          :loading-earlier="chat.loadingEarlier.value"
          @cite="onCite"
          @copy="onCopy"
          @feedback="onFeedback"
          @regenerate="onRegenerate"
          @continue="onContinue"
          @share="openShare"
          @suggest="onSuggest"
          @retry="reloadMessages"
          @load-earlier="chat.loadEarlier"
        />

        <Composer
          ref="composerRef"
          :generating="chat.generating.value"
          :placeholder="composerPlaceholder"
          :show-thinking="showThinkingToggle"
          :thinking="thinking"
          @update:thinking="thinking = $event"
          @send="onSend"
          @stop="chat.stop"
        />
      </section>

      <!-- 宽屏：右侧引用边注。无引用时不渲染「引用来源」区块（空应用属正常，不是故障） -->
      <aside v-if="wide && activeRefs.length" class="ref-side panel">
        <RefPanel
          :refs="activeRefs"
          :active-index="activeRefIndex"
          @select="selectRef"
          @jump="jumpToSegment"
        />
      </aside>
    </div>

    <!-- 窄屏：引用抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      title="引用来源"
      size="360px"
      :before-close="() => (drawerVisible = false)"
    >
      <RefPanel
        :refs="activeRefs"
        :active-index="activeRefIndex"
        closable
        @close="drawerVisible = false"
        @select="selectRef"
        @jump="jumpToSegment"
      />
    </el-drawer>

    <ShareDialog
      ref="shareRef"
      :conversation-id="shareConversationId"
      :message-id="shareMessageId"
      :message-text="shareText"
    />

    <!-- 生成失败横幅：原因 + 下一步，不静默 -->
    <div v-if="chat.streamError.value" class="stream-fail notice err">
      <span><b>生成中断：</b>{{ chat.streamError.value.reason }}</span>
      <span v-if="chat.streamError.value.hint" class="micro">{{ chat.streamError.value.hint }}</span>
      <el-button size="small" @click="chat.streamError.value = null">知道了</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { conversationsApi } from '@/api'
import type { ChatMessage } from '@/api/conversations'
import { useSessionStore } from '@/stores/session'
import { PERM } from '@/constants/permissions'
import TopBar from './components/TopBar.vue'
import AppInfoBar from './components/AppInfoBar.vue'
import HistoryPanel from './components/HistoryPanel.vue'
import MessageList from './components/MessageList.vue'
import Composer from './components/Composer.vue'
import RefPanel from './components/RefPanel.vue'
import ShareDialog from './components/ShareDialog.vue'
import {
  errText,
  readAppBrief,
  toRefEx,
  useHistoryPanel,
  useChatSession,
  type AppBrief,
  type AttachmentDraft,
  type ConversationEx,
  type SourceRefEx
} from './composables/useChatSession'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const appId = computed(() => (route.query.appId as string) || '')
const history = useHistoryPanel()
const thinking = ref(false)
const chat = useChatSession({
  appId: appId.value || undefined,
  thinking
})

const activeConversation = ref<ConversationEx | null>(null)
const appBrief = ref<AppBrief | null>(null)
const activeRefIndex = ref<number | null>(null)
const activeMessage = ref<ChatMessage | null>(null)
const drawerVisible = ref(false)
const wide = ref(true)
const shareRef = ref<InstanceType<typeof ShareDialog>>()
const shareText = ref('')
const shareMessageId = ref<string | null>(null)
const msgListRef = ref<InstanceType<typeof MessageList>>()
const composerRef = ref<InstanceType<typeof Composer>>()

/** 分享入口按权限点显隐（03 权限点词汇：conversation:share） */
const canShare = computed(() => session.can(PERM.conversationShare))
const shareConversationId = computed(() => activeConversation.value?.id ?? null)

/* ── 顶栏 ── */

const headerTitle = computed(() => {
  if (activeConversation.value && activeConversation.value.title !== '新建会话') {
    return activeConversation.value.title
  }
  return appBrief.value?.name || '通用问答'
})

const headerSubtitle = computed(() => {
  if (appBrief.value) return appBrief.value.type
  return '通用问答'
})

const emptyText = computed(() => {
  if (appBrief.value?.prologue) return appBrief.value.prologue
  // 不要在空态文案里写「引用来源」四字 —— 文本断言会把说明当成引用块假阳
  return '开始提问吧。未挂知识库时回答只有模型自身知识，属正常现象。'
})

const composerPlaceholder = computed(
  () => '输入问题，Enter 发送 / Shift+Enter 换行'
)

const showThinkingToggle = computed(
  () => !!appBrief.value?.thinkingEnabled && !!appBrief.value?.modelName
)

/* ── 引用 ── */

const activeRefs = computed(() => {
  const msg = activeMessage.value ?? chat.lastAssistant.value
  return (msg?.refs ?? []).map(toRefEx)
})

const onCite = (msg: ChatMessage, index: number) => {
  activeMessage.value = msg
  activeRefIndex.value = index
  if (!wide.value) drawerVisible.value = true
}

const selectRef = (index: number) => {
  activeRefIndex.value = index
}

const jumpToSegment = (ref: SourceRefEx) => {
  // 契约 SourceRef 未强制带 segmentId/documentId/kbId；有则深链，无则进知识库列表
  const query: Record<string, string> = {}
  if (ref.documentId) query.doc = ref.documentId
  if (ref.segmentId) query.segment = ref.segmentId
  if (ref.kbId) query.kb = ref.kbId
  router.push({ path: '/knowledge', query })
}

const onEsc = (e: KeyboardEvent) => {
  if (e.key === 'Escape') drawerVisible.value = false
}

/* ── 响应式：宽屏右侧边注 / 窄屏抽屉 ── */

const mq = window.matchMedia('(min-width: 1100px)')
const syncWide = () => {
  wide.value = mq.matches
  if (wide.value) drawerVisible.value = false
}
mq.addEventListener('change', syncWide)

/* ── 会话 ── */

const loadHistory = () => history.load(appId.value || undefined)

const applyAppBrief = (conv: ConversationEx | null, fallbackAppId?: string) => {
  const fromConv = readAppBrief(conv?.app)
  if (fromConv) {
    appBrief.value = fromConv
    thinking.value = fromConv.thinkingEnabled && fromConv.modelName.includes('8b')
    return
  }
  // 会话上没有应用信息时，用 appId 对应的简要信息（mock 附加字段）
  if (conv?.appId && conv.app) {
    appBrief.value = readAppBrief(conv.app)
    return
  }
  if (!conv?.appId && !fallbackAppId) {
    appBrief.value = null
    return
  }
  // 保持上次的应用简要（若同 appId）
  if (appBrief.value && (appBrief.value.id === conv?.appId || appBrief.value.id === fallbackAppId)) {
    return
  }
  appBrief.value = null
}

const openConversation = async (conv: ConversationEx, opts: { aroundMessageId?: string } = {}) => {
  if (chat.generating.value) chat.stop()
  activeConversation.value = conv
  applyAppBrief(conv)
  activeMessage.value = null
  activeRefIndex.value = null
  await chat.loadHistory(conv.id, opts.aroundMessageId ? { around: opts.aroundMessageId } : {})
  // 深链定位：消息加载完再滚/高亮，否则目标节点还不存在
  if (opts.aroundMessageId) {
    await nextTick()
    msgListRef.value?.scrollToMessage(opts.aroundMessageId)
  }
  // 同步 URL，便于刷新后回到同一会话；深链保留 messageId，普通打开清掉它
  router.replace({
    path: '/conversation',
    query: {
      ...(conv.appId ? { appId: conv.appId } : {}),
      conversationId: conv.id,
      ...(opts.aroundMessageId ? { messageId: opts.aroundMessageId } : {})
    }
  })
}

const reloadMessages = () => {
  if (activeConversation.value) chat.loadHistory(activeConversation.value.id)
}

const onCreateConv = async () => {
  if (chat.generating.value) chat.stop()
  try {
    const conv = (await conversationsApi.createConversation({
      appId: appId.value || undefined
    })) as ConversationEx
    if (!conv.app && appId.value) {
      // createConversation mock 会带 app；若无则尝试从现有列表找同应用简要
      const sibling = history.list.value.find((c) => c.appId === appId.value && c.app)
      if (sibling?.app) conv.app = sibling.app
    }
    history.prepend(conv)
    activeConversation.value = conv
    applyAppBrief(conv, appId.value)
    chat.messages.value = []
    activeMessage.value = null
    activeRefIndex.value = null
    router.replace({
      path: '/conversation',
      query: {
        ...(conv.appId ? { appId: conv.appId } : {}),
        conversationId: conv.id
      }
    })
    composerRef.value?.focus()
  } catch (error) {
    const { reason, hint } = errText(error, '新建会话失败')
    ElMessage.error(hint ? `${reason}（${hint}）` : reason)
  }
}

const onRemoveConv = async (conv: ConversationEx) => {
  try {
    await ElMessageBox.confirm(
      `删除后该会话的全部消息将不可恢复。确认删除「${conv.title}」？`,
      '确认删除会话',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch {
    return
  }
  try {
    await history.remove(conv.id)
    ElMessage.success('已删除')
    if (activeConversation.value?.id === conv.id) {
      activeConversation.value = null
      chat.messages.value = []
    }
  } catch (error) {
    const { reason, hint } = errText(error, '删除失败')
    ElMessage.error(hint ? `${reason}（${hint}）` : reason)
  }
}

const onRenameConv = async (conv: ConversationEx, title: string) => {
  try {
    await history.rename(conv.id, title)
    if (activeConversation.value?.id === conv.id) activeConversation.value.title = title
    ElMessage.success('已重命名')
  } catch (error) {
    const { reason, hint } = errText(error, '重命名失败')
    ElMessage.error(hint ? `${reason}（${hint}）` : reason)
  }
}

/* ── 发送 / 停止 / 重生成 ── */

const ensureConversation = async (): Promise<ConversationEx | null> => {
  if (activeConversation.value) return activeConversation.value
  const conv = (await conversationsApi.createConversation({
    appId: appId.value || undefined
  })) as ConversationEx
  if (!conv.app && appId.value) {
    const sibling = history.list.value.find((c) => c.appId === appId.value && c.app)
    if (sibling?.app) conv.app = sibling.app
  }
  history.prepend(conv)
  activeConversation.value = conv
  applyAppBrief(conv, appId.value)
  return conv
}

const onSend = async (text: string, _attachments: AttachmentDraft[]) => {
  try {
    const conv = await ensureConversation()
    if (!conv) return
    history.touch(conv)
    // 多轮上下文：同一 conversationId 连续提问，后端按会话组装历史
    await chat.send({
      conversationId: conv.id,
      question: text,
      attachments: _attachments
    })
  } catch (error) {
    const { reason, hint } = errText(error, '发送失败')
    ElMessage.error(hint ? `${reason}（${hint}）` : reason)
  }
}

const onRegenerate = (msg: ChatMessage) => {
  activeMessage.value = msg
  chat.regenerate(msg)
}

const onContinue = (msg: ChatMessage) => {
  activeMessage.value = msg
  chat.continueGenerate(msg)
}

const onFeedback = async (msg: ChatMessage, type: 'UP' | 'DOWN') => {
  try {
    await chat.toggleFeedback(msg, type)
  } catch (error) {
    const { reason, hint } = errText(error, '反馈失败')
    ElMessage.error(hint ? `${reason}（${hint}）` : reason)
  }
}

const onCopy = async (msg: ChatMessage) => {
  try {
    await navigator.clipboard.writeText(msg.content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

const openShare = (msg?: ChatMessage) => {
  // 消息级分享带上 messageId + 正文；顶栏入口是会话级，不带 messageText
  shareText.value = msg?.content ?? ''
  shareMessageId.value = msg?.id ?? null
  if (!shareConversationId.value) {
    ElMessage.warning('请先选择或发送一条消息，再生成分享链接')
    return
  }
  shareRef.value?.open()
}

const onSuggest = (text: string) => {
  composerRef.value?.sendText(text)
}

const onBack = () => {
  router.push('/')
}

/* ── 启动：按 URL 恢复 ── */

onMounted(async () => {
  syncWide()
  document.addEventListener('keydown', onEsc)
  await loadHistory()

  const convId = route.query.conversationId as string
  // 深链定位：URL 带 messageId 时用 around 取窗口，再滚/高亮到那条消息
  const deepMessageId = (route.query.messageId as string) || ''
  if (convId) {
    // 深链：不在前 50 条也要打开那一条 —— 直接按 id 取，不再在列表里找
    const found = history.list.value.find((c) => c.id === convId)
    if (found) {
      await openConversation(found, deepMessageId ? { aroundMessageId: deepMessageId } : {})
      return
    }
    try {
      const conv = (await conversationsApi.getConversation(convId)) as ConversationEx
      history.prepend(conv)
      await openConversation(conv, deepMessageId ? { aroundMessageId: deepMessageId } : {})
      return
    } catch (error) {
      // 真的不存在 / 无权访问：给可读提示并回落到空态，
      // **不**静默改开「同 appId 第一条」——那会打开别人的会话
      const { reason, hint } = errText(error, '打开会话失败')
      ElMessage.error(hint ? `${reason}（${hint}）` : reason)
      applyAppBrief(null, appId.value || undefined)
      return
    }
  }
  if (appId.value) {
    // 应用模式（无深链）：从同应用历史会话取一条，或保持空会话并展示应用信息
    const same = history.list.value.find((c) => c.appId === appId.value)
    if (same) {
      await openConversation(same)
      return
    }
    applyAppBrief(null, appId.value)
    // 无历史时创建一条，保证应用简要可用
    await onCreateConv()
  }
})

watch(
  () => route.query.appId,
  async () => {
    // 切换应用时重载
    if (route.name !== 'conversation') return
    await loadHistory()
  }
)

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onEsc)
  mq.removeEventListener('change', syncWide)
  if (chat.generating.value) chat.stop()
})
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  position: relative;
}

/* 三栅不等宽：左 220 / 中自适应（输入区，给足）/ 右 280 */
.body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: var(--space-sm);
  padding: var(--space-sm);
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
