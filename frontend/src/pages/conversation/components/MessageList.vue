<template>
  <div ref="scroller" class="msg-list scroll-y">
    <div v-if="loading" class="state">
      <p class="micro muted">加载消息…</p>
    </div>

    <div v-else-if="loadError" class="notice err">
      <div><b>加载失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" class="mt8" @click="emit('retry')">重试</el-button>
    </div>

    <!-- 空态：两栏（左字右图）。图在右侧用的是消息列本来就留空的负空间
         （正文 max-width 860px），左缘与 QUERY/ANSWER 标签对齐。 -->
    <div v-else-if="!messages.length" class="empty empty-split">
      <div class="empty-copy">
        <div class="empty-title">开始提问</div>
        <p class="micro muted">{{ emptyText }}</p>
      </div>
      <EmptyArt kind="retrieval" />
    </div>

    <template v-else>
      <!-- 长会话：还有更早的一页时给出口，不再把早期消息永久截断 -->
      <div v-if="hasEarlier" class="earlier-bar">
        <el-button size="small" :loading="loadingEarlier" @click="emit('load-earlier')">
          加载更早消息
        </el-button>
      </div>
      <template v-for="(msg, mi) in messages" :key="msg.id">
        <hr v-if="mi > 0" class="rule-2 msg-rule" />
        <article
          class="msg"
          :class="[msg.role, { focused: highlightId === msg.id }]"
          :data-message-id="msg.id"
        >
          <!-- 角色：mono 小标签，不靠气泡左右/颜色区分 -->
          <header class="msg-head">
            <span class="label">{{ msg.role === 'USER' ? 'QUERY' : 'ANSWER' }}</span>
            <span v-if="msg.role !== 'USER' && botIcon" class="bot-name tiny muted ellipsis">{{ botIcon }}</span>
            <span v-if="msg.status === 'GENERATING'" class="stat">
              <span class="dot ok" /><span class="tiny muted">生成中</span>
            </span>
            <span v-else-if="msg.status === 'STOPPED'" class="stat">
              <span class="dot warn" /><span class="tiny muted">已停止</span>
            </span>
            <span v-else-if="msg.status === 'FAILED'" class="stat">
              <span class="dot err" /><span class="tiny muted">失败</span>
            </span>
          </header>

          <div class="msg-body">
            <template v-if="msg.role === 'USER'">
              <div class="user-text">{{ msg.content }}</div>
              <div v-if="attsOf(msg).length" class="msg-atts">
                <span v-for="a in attsOf(msg)" :key="a.id" class="msg-att">
                  <span class="att-name ellipsis">{{ a.name }}</span>
                  <span class="att-size num faint">{{ formatBytes(a.size) }}</span>
                </span>
              </div>
            </template>
            <template v-else>
              <div v-if="msg.status === 'GENERATING' && !msg.content" class="thinking">
                <span class="dot ok" />
                <span class="micro muted">正在检索并生成…</span>
              </div>
              <!-- 空回答：给提示，不渲染一片空白还照出操作条 -->
              <div v-else-if="isEmptyAnswer(msg)" class="empty-answer notice warn">
                <div><b>本次没有返回内容。</b></div>
                <div v-if="msg.failHint" class="micro">{{ msg.failHint }}</div>
                <div class="fail-actions">
                  <el-button size="small" type="primary" @click="emit('regenerate', msg)">
                    重新生成
                  </el-button>
                </div>
              </div>
              <AnswerBody
                v-else
                :text="msg.content"
                :streaming="msg.status === 'GENERATING'"
                @cite="(n) => emit('cite', msg, n)"
              />

              <div v-if="msg.status === 'FAILED'" class="fail-box notice err">
                <div><b>生成失败：</b>{{ msg.failReason || '未知原因' }}</div>
                <div v-if="msg.failHint" class="micro">{{ msg.failHint }}</div>
                <div class="fail-actions">
                  <el-button size="small" type="primary" @click="emit('regenerate', msg)">
                    重新生成
                  </el-button>
                </div>
              </div>
            </template>
          </div>

          <!-- 引用来源：空应用 / 无引用时不显示 -->
          <div v-if="msg.role !== 'USER' && msg.refs?.length" class="ref-block">
            <div class="label">SOURCES</div>
            <div class="ref-chips">
              <button
                v-for="ref in msg.refs"
                :key="ref.index"
                type="button"
                class="ref-chip"
                :class="{ active: activeRefIndex === ref.index }"
                @click="emit('cite', msg, ref.index)"
              >
                <span class="num">[{{ ref.index }}]</span>
                <span class="ref-name ellipsis">{{ ref.docName }}</span>
                <span class="num score">{{ ref.score.toFixed(2) }}</span>
              </button>
            </div>
          </div>

          <!-- 操作条：空回答只留提示内的「重新生成」，不照常出整条 -->
          <div
            v-if="msg.role !== 'USER' && msg.status !== 'GENERATING' && !isEmptyAnswer(msg)"
            class="actions"
          >
            <button type="button" class="act" @click="emit('copy', msg)">复制</button>
            <template v-if="!publicMode">
              <button
                type="button"
                class="act"
                :class="{ on: msg.feedback === 'UP' }"
                @click="emit('feedback', msg, 'UP')"
              >
                赞
              </button>
              <button
                type="button"
                class="act"
                :class="{ on: msg.feedback === 'DOWN' }"
                @click="emit('feedback', msg, 'DOWN')"
              >
                踩
              </button>
            </template>
            <button type="button" class="act" @click="emit('regenerate', msg)">重新生成</button>
            <button
              v-if="canShare"
              type="button"
              class="act"
              @click="emit('share', msg)"
            >分享</button>
            <template v-if="msg.status === 'STOPPED'">
              <button type="button" class="act" @click="emit('continue', msg)">继续</button>
            </template>
            <span v-if="msg.tokens" class="meta num faint">
              {{ msg.tokens }} tokens · {{ (msg.durationMs / 1000).toFixed(1) }}s
            </span>
          </div>

          <!-- 推荐问题 -->
          <div v-if="msg.role !== 'USER' && msg.suggestions?.length && isLast(msg)" class="suggestions">
            <div class="label">猜你想问</div>
            <div class="chips">
              <button
                v-for="s in msg.suggestions"
                :key="s"
                type="button"
                class="chip"
                @click="emit('suggest', s)"
              >
                {{ s }}
              </button>
            </div>
          </div>
        </article>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { ChatAttachment, ChatMessage } from '@/api/conversations'
import AnswerBody from './AnswerBody.vue'
import EmptyArt from '@/components/EmptyArt.vue'
import { formatBytes, isEmptyAnswer } from '../composables/useChatSession'

const props = defineProps<{
  messages: ChatMessage[]
  loading?: boolean
  loadError?: { reason: string; hint?: string } | null
  emptyText?: string
  botIcon?: string
  publicMode?: boolean
  activeRefIndex?: number | null
  /** 分享入口显隐（权限点：conversation:share） */
  canShare?: boolean
  /**
   * 还有更早的消息可加载 / 正在加载。
   *
   * ⚠️ 这两个 prop 与下面的 `load-earlier` emit 是**补上的**：
   * 上一轮 agent 在重构中途被取消，只改了模板与 composable，
   * 漏了本文件的 props/emits 与父组件接线 ⇒ `vue-tsc` 报三处 TS2339/TS2345。
   * 实现都在 `useChatSession`（`hasEarlier` / `loadingEarlier` / `loadEarlier`），这里只做接线。
   */
  hasEarlier?: boolean
  loadingEarlier?: boolean
}>()

const emit = defineEmits<{
  cite: [msg: ChatMessage, index: number]
  copy: [msg: ChatMessage]
  feedback: [msg: ChatMessage, type: 'UP' | 'DOWN']
  regenerate: [msg: ChatMessage]
  continue: [msg: ChatMessage]
  share: [msg: ChatMessage]
  suggest: [text: string]
  retry: []
  'load-earlier': []
}>()

const scroller = ref<HTMLElement>()

/** 契约 ChatMessage.attachments 为可选；缺省空数组 */
const attsOf = (msg: ChatMessage): ChatAttachment[] =>
  ((msg as ChatMessage & { attachments?: ChatAttachment[] }).attachments ?? [])

const isLast = (msg: ChatMessage) => msg.id === props.messages[props.messages.length - 1]?.id

const scrollToBottom = (smooth = true) => {
  const el = scroller.value
  if (!el) return
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
}

/**
 * 深链定位：滚到目标消息并短暂高亮（与 assistants `?focus=` 同一手法）。
 * 找不到目标时静默留在原地 —— 消息可能已被删，页面仍有可读内容。
 */
const highlightId = ref('')
const scrollToMessage = (id: string) => {
  const el = scroller.value
  if (!el) return
  const target = el.querySelector(`[data-message-id="${id}"]`)
  if (!target) return
  target.scrollIntoView({ behavior: 'smooth', block: 'center' })
  highlightId.value = id
  window.setTimeout(() => {
    if (highlightId.value === id) highlightId.value = ''
  }, 2600)
}

watch(
  () => props.messages.map((m) => (m.content ?? '').length + m.status).join('|'),
  async () => {
    await nextTick()
    scrollToBottom(false)
  }
)

watch(
  () => props.messages.length,
  async () => {
    await nextTick()
    scrollToBottom()
  }
)

defineExpose({ scrollToBottom, scrollToMessage })
</script>

<style scoped>
.msg-list {
  flex: 1;
  min-height: 0;
  padding: var(--space-md) var(--space-lg);
  display: flex;
  flex-direction: column;
  gap: 0;
}

.msg-rule {
  margin: var(--space-md) 0;
}

.msg {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  /* ★ 左右对话式：助手贴左、用户贴右（`05 §4.1` 的线框） */
  align-items: flex-start;
}

.msg.USER {
  align-items: flex-end;
}

/* 深链定位到的那条消息：滚动到视野内后短暂描边，让人看见「落在了哪一个」 */
.msg.focused {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
}

/* 角色行：mono 小标签，替代左右气泡 */
.msg-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  min-height: 20px;
}

/* 用户一侧：状态/操作栏贴右（气泡在右，标签也该在右） */
.msg.USER .msg-head {
  justify-content: flex-end;
}

.bot-name {
  max-width: 180px;
}

.stat {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
}

/*
 * 气泡（`05 §4.1` 线框：用户是浅蓝底圆角块贴右，助手是浅灰底贴左）。
 *
 * ★ 宽度上限按"可读行宽"给：助手回答可能很长，铺满 1180px 会很难读；
 *   用户提问通常很短，用 `fit-content` 让它自然收缩成一块。
 * ★ 圆角用工程里的 `--radius-lg`（8px）——与 `.panel` 同一档，不新造值。
 */
.msg-body {
  max-width: min(860px, 78%);
  width: fit-content;
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-lg);
  background: var(--color-sunken);
}

.msg.USER .msg-body {
  background: var(--color-accent-soft);
}

/* 助手一侧的"角"压在左上、用户一侧压在右上 —— 气泡贴住它那一侧的边 */
.msg .msg-body {
  border-top-left-radius: var(--radius-sm);
}

.msg.USER .msg-body {
  border-top-left-radius: var(--radius-lg);
  border-top-right-radius: var(--radius-sm);
}

/* 提问：墨色更重，像仪器输入读数 */
.msg.user .msg-body {
  color: var(--color-ink);
  font-weight: 500;
}

.msg.user .msg-head .label {
  color: var(--color-ink-2);
}

.user-text {
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-atts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2xs);
  margin-top: var(--space-xs);
}

.msg-att {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  max-width: 280px;
  background: var(--color-sunken);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  padding: var(--space-3xs) var(--space-xs);
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  color: var(--color-ink-2);
}

.msg-att .att-name {
  max-width: 160px;
}

.empty-answer {
  max-width: 860px;
}

.thinking {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

.fail-box {
  margin-top: var(--space-xs);
}

.fail-actions {
  margin-top: var(--space-xs);
}

.mt8 {
  margin-top: var(--space-xs);
}

.state {
  padding: var(--space-lg);
  text-align: center;
}

/* 空态两栏：把全局 .empty 的居中排布换成左起的两栏。
   padding 归零 —— 左缘要与消息列（QUERY / ANSWER 标签）严格对齐，
   否则整个空态会比正文右移一格。 */
.empty-split {
  flex: 1;
  min-height: 0;
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: var(--space-xl);
  padding: 0;
  text-align: left;
}

.empty-copy {
  /* 本组件的行宽旋钮（页面上没有测度令牌；写成一个局部变量而不是散落数值） */
  --empty-copy-measure: 380px;

  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  max-width: var(--empty-copy-measure);
}

/* 引用角标条 */
.ref-block {
  max-width: 860px;
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.ref-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.ref-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  max-width: 320px;
  border: 1px solid var(--color-rule);
  background: var(--color-surface);
  color: var(--color-ink-2);
  font-family: var(--font-body);
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  padding: var(--space-2xs) var(--space-xs);
  border-radius: var(--radius);
  cursor: pointer;
  transition:
    border-color var(--dur-micro) var(--ease-out),
    background var(--dur-micro) var(--ease-out);
}

.ref-chip:hover {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
}

.ref-chip.active {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.ref-chip .score {
  color: var(--color-accent);
  flex-shrink: 0;
}

.ref-name {
  min-width: 0;
}

/* 操作条 */
.actions {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  flex-wrap: wrap;
  max-width: 860px;
}

.act {
  border: none;
  background: transparent;
  color: var(--color-muted);
  font-size: var(--text-2xs);
  font-family: inherit;
  cursor: pointer;
  padding: var(--space-3xs) var(--space-xs);
  border-radius: var(--radius-sm);
  transition:
    background var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.act:hover {
  background: var(--color-hover);
  color: var(--color-ink-2);
}

.act.on {
  color: var(--color-accent);
  font-weight: 500;
}

.meta {
  margin-left: var(--space-2xs);
  font-size: var(--text-2xs);
}

/* 推荐问题 */
.suggestions {
  max-width: 860px;
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
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
</style>
