<template>
  <div class="debug">
    <div class="dbg-head">
      <span class="h4">调试预览</span>
      <el-tag size="small" effect="plain" :type="published ? 'success' : 'info'">
        {{ published ? '已发布' : '配置未发布' }}
      </el-tag>
    </div>

    <div ref="listEl" class="dbg-list">
      <div v-for="(turn, idx) in turns" :key="idx" class="turn">
        <div class="q">{{ turn.question }}</div>
        <div class="a">
          <p class="a-text">{{ turn.answer }}</p>
          <div class="a-meta micro muted">
            引用分段 {{ turn.refs.length }} · 耗时 {{ (turn.elapsedMs / 1000).toFixed(1) }}s · 消耗 {{ turn.tokens }} token
          </div>
          <div v-if="turn.refs.length" class="a-refs">
            <div v-for="(r, i) in turn.refs" :key="i" class="ref micro">
              [{{ i + 1 }}] {{ r.title }}（{{ r.score }}）
            </div>
          </div>
          <div v-else class="micro muted">本次无引用 —— 应用未挂知识库时属正常</div>
        </div>
      </div>
      <div v-if="!turns.length" class="dbg-empty micro muted">
        输入调试问题，验证当前草稿配置的效果
      </div>
    </div>

    <div class="dbg-input">
      <el-input
        v-model="question"
        size="small"
        placeholder="输入调试问题…"
        :disabled="sending"
        @keyup.enter="send"
      />
      <el-button size="small" type="primary" :loading="sending" @click="send">发送</el-button>
    </div>

    <p class="dbg-note micro muted">调试使用当前草稿配置，<b>不产生正式会话记录</b></p>

    <div v-if="warnings.length" class="notice warn">
      <div v-for="(w, i) in warnings" :key="i">⚠ 发布前校验：{{ w }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { assistantsApi } from '@/api'
import { ApiError } from '@/api/types'

interface DebugRef {
  title: string
  score: number
}

interface DebugTurn {
  question: string
  answer: string
  refs: DebugRef[]
  elapsedMs: number
  tokens: number
}

const props = defineProps<{
  appId: string | null
  published: boolean
  warnings: string[]
}>()

const question = ref('')
const sending = ref(false)
const turns = ref<DebugTurn[]>([])
const listEl = ref<HTMLElement>()

const send = async () => {
  const q = question.value.trim()
  if (!q || !props.appId) return
  sending.value = true
  try {
    const result = await assistantsApi.debugAssistant(props.appId, q)
    turns.value.push({
      question: q,
      answer: result.answer,
      refs: (result.refs ?? []) as DebugRef[],
      elapsedMs: result.elapsedMs,
      tokens: result.tokens
    })
    question.value = ''
    await nextTick()
    listEl.value?.scrollTo({ top: listEl.value.scrollHeight })
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '调试失败'
    ElMessage.error(message)
  } finally {
    sending.value = false
  }
}

const reset = () => {
  turns.value = []
  question.value = ''
}

defineExpose({ reset })
</script>

<style scoped>
.debug {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  height: 100%;
  min-height: 0;
}

.dbg-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.turn .q {
  align-self: flex-end;
  max-width: 90%;
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-accent-soft);
  border-radius: var(--radius);
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.turn .a {
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  margin-top: var(--space-xs);
}

.a-text {
  margin: 0;
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
  white-space: pre-wrap;
}

.a-meta {
  margin-top: var(--space-xs);
  padding-top: var(--space-xs);
  border-top: 1px solid var(--color-rule-2);
}

.a-refs {
  margin-top: var(--space-2xs);
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
}

.ref {
  color: var(--color-accent);
}

.dbg-empty {
  padding: var(--space-lg) 0;
  text-align: center;
}

.dbg-list {
  flex: 1;
  min-height: 220px;
  max-height: 420px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-2xs);
}

.dbg-input {
  display: flex;
  gap: var(--space-xs);
}

.dbg-note {
  margin: 0;
}

.dbg-note b {
  color: var(--color-warn);
  font-weight: 600;
}
</style>
