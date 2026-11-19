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
            引用分段 {{ turn.refs.length }} · 耗时 {{ (turn.elapsedMs / 1000).toFixed(1) }}s ·
            消耗 {{ turn.tokens === null ? '未上报' : `${turn.tokens} token` }}
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
      <!--
        ★ 「对比」只在表单脏着时有意义：没有改动，两版就是同一版（点了也只会跑一次）。
           它不是"另一个发送按钮"，而是"用同一个问题跑两遍给我看"。
      -->
      <el-button
        size="small"
        :loading="comparing"
        :disabled="!dirty || !question.trim()"
        :title="dirty ? '用同一个问题各跑一次：已保存的那版 vs 你现在改的这版' : '表单没有改动，两版是同一版'"
        @click="compare"
      >
        对比两版
      </el-button>
    </div>

    <div v-if="comparison" class="cmp">
      <div class="cmp-head">
        <span class="h4">对比结果</span>
        <span class="micro muted">同一个问题：{{ comparison.question }}</span>
        <el-button link size="small" class="cmp-close" @click="comparison = null">收起</el-button>
      </div>
      <div v-if="!comparison.draftRan" class="cmp-same micro muted">
        你现在改的那版与已保存的逐字相同，跑出来的会是同一份答案 —— 没有需要对比的差异。
      </div>
      <div v-else class="cmp-grid">
        <div class="cmp-col">
          <div class="cmp-col-head">
            <span class="cmp-tag">已保存的</span>
            <span class="micro muted">
              {{ (comparison.saved.elapsedMs / 1000).toFixed(1) }}s ·
              {{ comparison.saved.tokens === null ? '用量未上报' : `${comparison.saved.tokens} token` }}
            </span>
          </div>
          <p class="cmp-text">{{ comparison.saved.answer }}</p>
        </div>
        <div class="cmp-col">
          <div class="cmp-col-head">
            <span class="cmp-tag draft">你现在改的</span>
            <span class="micro muted">
              {{ (comparison.draft!.elapsedMs / 1000).toFixed(1) }}s ·
              {{ comparison.draft!.tokens === null ? '用量未上报' : `${comparison.draft!.tokens} token` }}
            </span>
          </div>
          <p class="cmp-text">{{ comparison.draft!.answer }}</p>
        </div>
      </div>
      <p class="cmp-note micro muted">
        两栏用的是同一个问题、同一个模型与同一批检索结果 —— 差别只来自角色设定。
        「你现在改的」那版<b>没有保存</b>，关掉抽屉就没了。
      </p>
    </div>

    <p class="dbg-note micro muted">调试使用当前草稿配置，<b>不产生正式会话记录</b></p>

    <!-- ★ 错误态必须留在面板里（#184）：此前只有一条几秒后消失的 toast，
         用户会以为「点了没反应」，也无从判断是端点不存在还是模型失败。 -->
    <div v-if="error" class="notice err dbg-error">
      <span><b>调试失败：</b>{{ error }}</span>
      <el-button link size="small" @click="error = null">知道了</el-button>
    </div>

    <div v-if="warnings.length" class="notice warn">
      <div v-for="(w, i) in warnings" :key="i">⚠ 发布前校验：{{ w }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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
  /** ★ `null` = 上游没有上报用量（不是 0）；见 #142 */
  tokens: number | null
}

const props = defineProps<{
  appId: string | null
  published: boolean
  warnings: string[]
  /**
   * 表单里有未保存的改动。
   *
   * ★ 这个面板调的 `POST /assistants/{id}/debug` 读的是**已保存**的配置（后端拒绝在请求体里收配置：
   * 那等于允许"调试用 A、线上用 B"，是 `BR-02-08` 要防的）。所以表单脏着点发送，测的是旧配置 ——
   * 而界面上看不出任何区别，用户会以为自己刚改的那句生效了。
   */
  dirty?: boolean
  /**
   * 表单里当前的「角色设定」。
   *
   * ★ 对比调试用它作为**对照项**：请求体里带过去的那版不落库、不影响任何后续轮，
   * 只用于"同一个问题再跑一遍"。与"请求体里收配置"（`BR-02-08` 禁止）的区别是：
   * 这里两份都跑、两份都返回，且界面上明确标出哪栏是已保存的。
   */
  draftRolePrompt?: string
}>()

const emit = defineEmits<{ (e: 'request-save'): void }>()

const question = ref('')
const sending = ref(false)
const error = ref<string | null>(null)
const turns = ref<DebugTurn[]>([])
const listEl = ref<HTMLElement>()
const comparing = ref(false)
const comparison = ref<null | {
  question: string
  saved: DebugTurn
  draft: DebugTurn | null
  draftRan: boolean
}>(null)

const send = async () => {
  const q = question.value.trim()
  if (!q || !props.appId || sending.value) return
  if (props.dirty) {
    // 不直接发：先把"你看到的是旧配置"这件事说清，并给一条能走下去的路（保存并调试）。
    // 不给"仍然用旧配置"这个选项 —— 那正是这个面板原先的隐性行为。
    try {
      await ElMessageBox.confirm(
        '调试读的是已保存的配置。你刚改的内容还没保存，现在发出去测的是旧的那一版。',
        '先保存再调试',
        { confirmButtonText: '保存并调试', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
    emit('request-save')
    return
  }
  await sendNow()
}

/**
 * 真正发送。`request-save` 的接收方保存成功后会调这里，避免"保存了但还要再点一次发送"。
 */
const sendNow = async () => {
  const q = question.value.trim()
  if (!q || !props.appId || sending.value) return
  sending.value = true
  error.value = null
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
  } catch (failure) {
    const message = failure instanceof ApiError ? failure.message : '调试失败'
    // ★ 面板内留一条可读错误；toast 仍然给，但它会消失，不能是唯一痕迹
    error.value = message
    ElMessage.error(message)
  } finally {
    sending.value = false
  }
}

const reset = () => {
  turns.value = []
  question.value = ''
  error.value = null
  comparison.value = null
}

/**
 * 对比调试：同一个问题跑两遍（已保存的 / 你现在改的）。
 *
 * ★ 它**不**走 `send()` 的"先保存再调试"拦截 —— 那套拦截的前提是"你想测的是刚改的配置，但后端只读已保存的"。
 * 对比恰好把这件事摆明了：两版都跑，谁是谁写在标签上。所以这里既不保存、也不弹确认框。
 */
const compare = async () => {
  const q = question.value.trim()
  if (!q || !props.appId || comparing.value) return
  comparing.value = true
  error.value = null
  try {
    const r = await assistantsApi.compareAssistant(props.appId, q, props.draftRolePrompt ?? null)
    const toTurn = (a: {
      answer: string
      refs: unknown[]
      elapsedMs: number
      tokens: number | null
    }): DebugTurn => ({
      question: q,
      answer: a.answer,
      refs: (a.refs ?? []) as DebugRef[],
      elapsedMs: a.elapsedMs,
      tokens: a.tokens
    })
    comparison.value = {
      question: q,
      saved: toTurn(r.saved),
      draft: r.draft ? toTurn(r.draft) : null,
      draftRan: r.draftRan
    }
  } catch (failure) {
    const message = failure instanceof ApiError ? failure.message : '对比失败'
    error.value = message
    ElMessage.error(message)
  } finally {
    comparing.value = false
  }
}

defineExpose({ reset, sendNow })
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

/*
 * 对比结果：两栏等宽并排。
 * ★ 不用「左 A 右 B」的对比色块 —— 两栏同底色、只有标签不同，因为这里没有"哪个更好"，
 *   只有"哪个是你刚改的"。给某一边加绿/红会让读者以为系统在判优劣。
 */
.cmp {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  padding: var(--space-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.cmp-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
}

.cmp-close {
  margin-left: auto;
}

.cmp-same {
  border-left: 2px solid var(--color-rule);
  padding-left: var(--space-xs);
}

.cmp-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm);
}

.cmp-col {
  min-width: 0;
  border-top: 1px solid var(--color-rule);
  padding-top: var(--space-2xs);
}

.cmp-col-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2xs);
  margin-bottom: var(--space-3xs);
}

.cmp-tag {
  font-size: var(--text-2xs);
  font-weight: 600;
  color: var(--color-ink-2);
}

.cmp-tag.draft {
  color: var(--color-accent);
}

.cmp-text {
  margin: 0;
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.cmp-note {
  margin: 0;
}

.dbg-note {
  margin: 0;
}

.dbg-note b {
  color: var(--color-warn);
  font-weight: 600;
}
</style>
