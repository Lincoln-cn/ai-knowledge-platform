<script setup lang="ts">
/**
 * 提示词评测抽屉（`ROADMAP §11.63`）。
 *
 * 一件事：**同一组问题，用「已保存那版」和「你现在这版」各跑一遍，然后由人来评哪个好**。
 *
 * 三个设计取舍：
 * - 草稿正文在这里也能改（默认带出当前正文）—— 评测常常发生在"还没保存"的那一版上，
 *   逼用户先回表单保存再测，等于替她做了决定。
 * - 必须选执行环境（应用）—— 问题要经过真实检索才有意义，检索范围由应用决定。
 * - 汇总里 `winRate` 为 null 显示「还没评」，不显示 0% —— 0% 会被读成"改坏了"。
 */
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { assistantsApi, promptsApi } from '@/api'
import type {
  EvalVerdict,
  PromptEvalAnswer,
  PromptEvalItem,
  PromptEvalRun,
  PromptEvalSet
} from '@/api/prompts'

const props = defineProps<{
  /** 当前（已保存）正文 —— 草稿的默认值，也是基线那一侧。 */
  currentText: string
  currentName?: string
}>()

const visible = ref(false)
const configId = ref<string | null>(null)

const sets = ref<PromptEvalSet[]>([])
const setId = ref<string | null>(null)
const items = ref<PromptEvalItem[]>([])
const newQuestion = ref('')

const apps = ref<{ id: string; name: string }[]>([])
const appId = ref('')
const draftText = ref('')
const runId = ref<string | null>(null)
const run = ref<PromptEvalRun | null>(null)
const busy = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
// ★ 两个一次性标志：`finished` 防止"DONE 之后还被 start() 建了个 interval 再跑一次"，
//   `notified` 防止同一次结果弹两条提示（实测会叠两条「跑完了，可以开始评」）。
let finished = false
let notified = false

/** 按题把两侧答案配起来：左基线、右草稿。 */
const pairs = computed(() => {
  const rows = run.value?.answers ?? []
  const map = new Map<
    string,
    { ordinal: number; question: string; saved?: PromptEvalAnswer; draft?: PromptEvalAnswer }
  >()
  for (const a of rows) {
    const entry = map.get(a.itemId) ?? { ordinal: a.ordinal, question: a.question }
    if (a.side === 'SAVED') {
      entry.saved = a
    } else {
      entry.draft = a
    }
    map.set(a.itemId, entry)
  }
  return [...map.entries()]
    .map(([itemId, v]) => ({ itemId, ...v }))
    .sort((a, b) => a.ordinal - b.ordinal)
})

const progress = computed(() => {
  if (!run.value) {
    return ''
  }
  if (run.value.status === 'RUNNING') {
    return `跑到 ${run.value.itemDone} / ${run.value.itemTotal} 题`
  }
  if (run.value.status === 'FAILED') {
    return `跑失败：${run.value.failReason ?? '原因未知'}`
  }
  return `跑完了 ${run.value.itemTotal} 题`
})

const summaryText = computed(() => {
  const s = run.value?.summary
  if (!s) {
    return ''
  }
  if (s.winRate === null) {
    return `还没评 —— ${s.totalItems} 题都在等打分`
  }
  const pct = Math.round(s.winRate * 100)
  return `胜率 ${pct}%（更好 ${s.better} · 差不多 ${s.same} · 更差 ${s.worse}，已评 ${s.ratedItems} / ${s.totalItems}）`
})

async function open(id: string, name?: string) {
  // ★ 先让一帧：调用方（配置面板）在同一个 tick 里刚把 `evalRow` 赋成这一行，
  //   props 还没流下来 —— 同步读 `props.currentText` 会拿到空串（第一次）或上一行的正文。
  await nextTick()
  configId.value = id
  setTitle(name)
  visible.value = true
  run.value = null
  runId.value = null
  finished = false
  notified = false
  draftText.value = props.currentText
  setId.value = null
  items.value = []
  await Promise.all([loadSets(), loadApps()])
}

function setTitle(name?: string) {
  appTitle.value = name ? `评测 · ${name}` : '评测'
}
const appTitle = ref('评测')

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = undefined
  }
}

onBeforeUnmount(stopPolling)

async function loadSets() {
  if (!configId.value) {
    return
  }
  try {
    sets.value = await promptsApi.listPromptEvalSets(configId.value)
    if (!setId.value && sets.value.length > 0) {
      setId.value = sets.value[0].id
      await loadItems()
    }
  } catch (e) {
    ElMessage.error(readable(e))
  }
}

async function loadApps() {
  try {
    const page = await assistantsApi.listAssistants({ page: 1, size: 100 })
    apps.value = (page.list ?? []).map((a) => ({ id: a.id, name: a.name }))
    if (!appId.value && apps.value.length > 0) {
      appId.value = apps.value[0].id
    }
  } catch {
    // 应用列表拉不到不挡评测入口 —— 用户可能直接要看历史结果
    apps.value = []
  }
}

async function loadItems() {
  if (!setId.value) {
    items.value = []
    return
  }
  try {
    items.value = await promptsApi.listPromptEvalItems(setId.value)
  } catch (e) {
    ElMessage.error(readable(e))
  }
}

async function onSetChange(value: string) {
  setId.value = value
  await loadItems()
}

async function createSet() {
  if (!configId.value) {
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('给这套题起个名字', '新建评测集', {
      inputPlaceholder: '比如「常见口径 20 题」',
      confirmButtonText: '新建',
      cancelButtonText: '取消'
    })
    const created = await promptsApi.createPromptEvalSet(configId.value, value)
    await loadSets()
    setId.value = created.id
    await loadItems()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(readable(e))
    }
  }
}

async function removeSet() {
  if (!setId.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      '删掉这套题会连它的历史运行与评分一起删掉，不可恢复。',
      '删除评测集',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await promptsApi.deletePromptEvalSet(setId.value)
    setId.value = null
    run.value = null
    await loadSets()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(readable(e))
    }
  }
}

async function addItem() {
  if (!setId.value) {
    ElMessage.warning('先建一套评测集')
    return
  }
  const q = newQuestion.value.trim()
  if (!q) {
    return
  }
  try {
    await promptsApi.addPromptEvalItem(setId.value, q)
    newQuestion.value = ''
    await loadItems()
  } catch (e) {
    ElMessage.error(readable(e))
  }
}

async function removeItem(item: PromptEvalItem) {
  try {
    await promptsApi.deletePromptEvalItem(item.id)
    await loadItems()
  } catch (e) {
    ElMessage.error(readable(e))
  }
}

async function start() {
  if (!setId.value) {
    ElMessage.warning('先建一套评测集')
    return
  }
  if (!appId.value) {
    ElMessage.warning('选一个应用作为执行环境 —— 问题要经过真实检索')
    return
  }
  if (!draftText.value.trim()) {
    ElMessage.warning('草稿正文是空的')
    return
  }
  busy.value = true
  try {
    const created = await promptsApi.startPromptEvalRun(setId.value, appId.value, draftText.value)
    runId.value = created.id
    finished = false
    notified = false
    await poll(true)
    stopPolling()
    // 初始那次可能已经跑完（题少时）—— 那就别再建 interval
    if (!finished) {
      timer = setInterval(() => void poll(false), 2000)
    }
  } catch (e) {
    ElMessage.error(readable(e))
  } finally {
    busy.value = false
  }
}

async function poll(initial: boolean) {
  if (!runId.value) {
    return
  }
  try {
    run.value = await promptsApi.getPromptEvalRun(runId.value)
    if (run.value.status !== 'RUNNING') {
      finished = true
      stopPolling()
      if (!initial && !notified) {
        notified = true
        ElMessage.success(run.value.status === 'FAILED' ? '跑失败了' : '跑完了，可以开始评')
      }
    }
  } catch (e) {
    stopPolling()
    ElMessage.error(readable(e))
  }
}

async function rate(itemId: string, verdict: EvalVerdict) {
  if (!runId.value) {
    return
  }
  try {
    await promptsApi.ratePromptEvalAnswer(runId.value, itemId, verdict)
    await poll(false)
  } catch (e) {
    ElMessage.error(readable(e))
  }
}

async function clearRate(itemId: string) {
  if (!runId.value) {
    return
  }
  try {
    await promptsApi.clearPromptEvalRating(runId.value, itemId)
    await poll(false)
  } catch (e) {
    ElMessage.error(readable(e))
  }
}

function readable(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e) {
    return String((e as { message: unknown }).message)
  }
  return String(e)
}

defineExpose({ open })
</script>

<template>
  <el-drawer v-model="visible" :title="appTitle" size="920px" destroy-on-close>
    <div class="pe">
      <!-- 左：题面 -->
      <aside class="pe-left">
        <div class="pe-block">
          <div class="pe-label">评测集</div>
          <div class="pe-row">
            <el-select
              :model-value="setId ?? ''"
              placeholder="还没有评测集"
              size="small"
              style="flex: 1"
              @change="onSetChange"
            >
              <el-option v-for="s in sets" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-button size="small" @click="createSet">新建</el-button>
            <el-button v-if="setId" size="small" text type="danger" @click="removeSet">
              删
            </el-button>
          </div>
        </div>

        <div class="pe-block pe-grow">
          <div class="pe-label">题目（{{ items.length }}）</div>
          <div class="pe-items">
            <div v-for="it in items" :key="it.id" class="pe-item">
              <span class="pe-ordinal">{{ it.ordinal }}</span>
              <span class="pe-question">{{ it.question }}</span>
              <el-button text size="small" type="danger" @click="removeItem(it)">×</el-button>
            </div>
            <div v-if="items.length === 0" class="pe-empty">
              还没有题目。写几句你平时真的会问的话 —— 泛泛的题测不出角色设定的差别。
            </div>
          </div>
          <div class="pe-add">
            <el-input
              v-model="newQuestion"
              size="small"
              placeholder="加一道题，回车"
              :disabled="!setId"
              @keyup.enter="addItem"
            />
          </div>
        </div>
      </aside>

      <!-- 右：跑 -->
      <section class="pe-right">
        <div class="pe-run">
          <div class="pe-row">
            <span class="pe-label">执行环境</span>
            <el-select v-model="appId" size="small" placeholder="选一个应用" style="width: 220px">
              <el-option v-for="a in apps" :key="a.id" :label="a.name" :value="a.id" />
            </el-select>
            <span class="micro muted">问题走这个应用的检索与模型</span>
          </div>
          <div class="pe-label" style="margin-top: var(--space-xs)">草稿正文</div>
          <el-input
            v-model="draftText"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 8 }"
            placeholder="你现在想试的那一版"
          />
          <div class="pe-row" style="margin-top: var(--space-xs)">
            <el-button type="primary" :loading="busy" @click="start">跑评测</el-button>
            <span v-if="progress" class="micro" :class="{ 'pe-bad': run?.status === 'FAILED' }">
              {{ progress }}
            </span>
          </div>
        </div>

        <div v-if="run" class="pe-summary">
          <span class="pe-win">{{ summaryText }}</span>
        </div>

        <div class="pe-answers">
          <div v-for="p in pairs" :key="p.itemId" class="pe-card">
            <div class="pe-card-hd">
              <span class="pe-ordinal">{{ p.ordinal }}</span>
              <span class="pe-question">{{ p.question }}</span>
            </div>
            <div class="pe-sides">
              <div class="pe-side">
                <div class="pe-side-tag">已保存</div>
                <div class="pe-answer">{{ p.saved?.answer || '—' }}</div>
              </div>
              <div class="pe-side">
                <div class="pe-side-tag">草稿</div>
                <div class="pe-answer">{{ p.draft?.answer || '—' }}</div>
              </div>
            </div>
            <div class="pe-rate">
              <el-button
                size="small"
                :type="p.draft?.verdict === 'BETTER' ? 'primary' : 'default'"
                @click="rate(p.itemId, 'BETTER')"
              >
                草稿更好
              </el-button>
              <el-button
                size="small"
                :type="p.draft?.verdict === 'SAME' ? 'primary' : 'default'"
                @click="rate(p.itemId, 'SAME')"
              >
                差不多
              </el-button>
              <el-button
                size="small"
                :type="p.draft?.verdict === 'WORSE' ? 'primary' : 'default'"
                @click="rate(p.itemId, 'WORSE')"
              >
                草稿更差
              </el-button>
              <el-button v-if="p.draft?.verdict" text size="small" @click="clearRate(p.itemId)">
                清空
              </el-button>
            </div>
          </div>
          <div v-if="run && pairs.length === 0" class="pe-empty">
            跑完了但还没有答案 —— 看看上面那句失败原因。
          </div>
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<style scoped>
.pe {
  display: flex;
  gap: var(--space-md);
  height: 100%;
  min-height: 0;
}

.pe-left {
  width: 300px;
  flex: 0 0 300px;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  min-height: 0;
}

.pe-right {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  min-height: 0;
  overflow-y: auto;
}

.pe-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.pe-grow {
  flex: 1;
  min-height: 0;
}

.pe-label {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.pe-row {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  flex-wrap: wrap;
}

.pe-items {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-2xs);
}

.pe-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2xs);
  padding: 4px 0;
  border-bottom: 1px solid var(--color-border-subtle, var(--color-border));
}

.pe-item:last-child {
  border-bottom: none;
}

.pe-ordinal {
  flex: 0 0 20px;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  text-align: right;
}

.pe-question {
  flex: 1;
  font-size: var(--text-sm);
  line-height: 1.5;
  word-break: break-word;
}

.pe-add {
  display: flex;
}

.pe-run {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-xs);
}

.pe-summary {
  padding: var(--space-2xs) 0;
}

.pe-win {
  font-size: var(--text-sm);
  font-variant-numeric: tabular-nums;
}

.pe-answers {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.pe-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-xs);
}

.pe-card-hd {
  display: flex;
  gap: var(--space-2xs);
  align-items: baseline;
  margin-bottom: var(--space-2xs);
}

.pe-sides {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-xs);
}

.pe-side-tag {
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  margin-bottom: 2px;
}

.pe-answer {
  font-size: var(--text-sm);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow-y: auto;
}

.pe-rate {
  display: flex;
  gap: var(--space-2xs);
  margin-top: var(--space-2xs);
}

.pe-empty {
  padding: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.pe-bad {
  color: var(--color-danger);
}
</style>
