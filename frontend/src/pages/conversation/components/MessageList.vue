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

    <div v-else-if="!messages.length" class="empty">
      <div class="empty-title">开始提问</div>
      <p class="micro muted">{{ emptyText }}</p>
    </div>

    <template v-else>
      <template v-for="(msg, mi) in messages" :key="msg.id">
        <hr v-if="mi > 0" class="rule-2 msg-rule" />
        <article class="msg" :class="msg.role">
          <!-- 角色：mono 小标签，不靠气泡左右/颜色区分 -->
          <header class="msg-head">
            <span class="label">{{ msg.role === 'user' ? 'QUERY' : 'ANSWER' }}</span>
            <span v-if="msg.role !== 'user' && botIcon" class="bot-name tiny muted ellipsis">{{ botIcon }}</span>
            <span v-if="msg.status === '生成中'" class="stat">
              <span class="dot ok" /><span class="tiny muted">生成中</span>
            </span>
            <span v-else-if="msg.status === '已停止'" class="stat">
              <span class="dot warn" /><span class="tiny muted">已停止</span>
            </span>
            <span v-else-if="msg.status === '失败'" class="stat">
              <span class="dot err" /><span class="tiny muted">失败</span>
            </span>
          </header>

          <div class="msg-body">
            <template v-if="msg.role === 'user'">
              <div class="user-text">{{ msg.content }}</div>
            </template>
            <template v-else>
              <div v-if="msg.status === '生成中' && !msg.content" class="thinking">
                <span class="dot ok" />
                <span class="micro muted">正在检索并生成…</span>
              </div>
              <AnswerBody
                v-else
                :text="msg.content"
                :streaming="msg.status === '生成中'"
                @cite="(n) => emit('cite', msg, n)"
              />

              <div v-if="msg.status === '失败'" class="fail-box notice err">
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
          <div v-if="msg.role !== 'user' && msg.refs.length" class="ref-block">
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

          <!-- 操作条 -->
          <div v-if="msg.role !== 'user' && msg.status !== '生成中'" class="actions">
            <button type="button" class="act" @click="emit('copy', msg)">复制</button>
            <template v-if="!publicMode">
              <button
                type="button"
                class="act"
                :class="{ on: msg.feedback === 'up' }"
                @click="emit('feedback', msg, 'up')"
              >
                赞
              </button>
              <button
                type="button"
                class="act"
                :class="{ on: msg.feedback === 'down' }"
                @click="emit('feedback', msg, 'down')"
              >
                踩
              </button>
            </template>
            <button type="button" class="act" @click="emit('regenerate', msg)">重新生成</button>
            <button type="button" class="act" @click="emit('share', msg)">分享</button>
            <template v-if="msg.status === '已停止'">
              <button type="button" class="act" @click="emit('continue', msg)">继续</button>
            </template>
            <span v-if="msg.tokens" class="meta num faint">
              {{ msg.tokens }} tokens · {{ (msg.durationMs / 1000).toFixed(1) }}s
            </span>
          </div>

          <!-- 推荐问题 -->
          <div v-if="msg.role !== 'user' && msg.suggestions.length && isLast(msg)" class="suggestions">
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
import type { ChatMessage } from '@/api/conversations'
import AnswerBody from './AnswerBody.vue'

const props = defineProps<{
  messages: ChatMessage[]
  loading?: boolean
  loadError?: { reason: string; hint?: string } | null
  emptyText?: string
  botIcon?: string
  publicMode?: boolean
  activeRefIndex?: number | null
}>()

const emit = defineEmits<{
  cite: [msg: ChatMessage, index: number]
  copy: [msg: ChatMessage]
  feedback: [msg: ChatMessage, type: 'up' | 'down']
  regenerate: [msg: ChatMessage]
  continue: [msg: ChatMessage]
  share: [msg: ChatMessage]
  suggest: [text: string]
  retry: []
}>()

const scroller = ref<HTMLElement>()

const isLast = (msg: ChatMessage) => msg.id === props.messages[props.messages.length - 1]?.id

const scrollToBottom = (smooth = true) => {
  const el = scroller.value
  if (!el) return
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
}

watch(
  () => props.messages.map((m) => m.content.length + m.status).join('|'),
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

defineExpose({ scrollToBottom })
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
}

/* 角色行：mono 小标签，替代左右气泡 */
.msg-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  min-height: 20px;
}

.bot-name {
  max-width: 180px;
}

.stat {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
}

.msg-body {
  max-width: 860px;
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
