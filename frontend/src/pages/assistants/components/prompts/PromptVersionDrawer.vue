<script setup lang="ts">
/**
 * 提示词的历史版本抽屉（`ROADMAP §11.62`）。
 *
 * 交互只有一件事：**挑一版，看它变成现在这个样子改了什么**。
 * 所以不需要"选两版"——左侧列表选中某一版，右侧就是「那一版 → 当前」的逐行差异。
 *
 * 另一件事是回滚，但它不是主路径：只在"越改越糟"时才用，所以放右上角、带确认。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { promptsApi } from '@/api'
import type {
  PromptConfigVersionDetail,
  PromptConfigVersionSummary
} from '@/api/prompts'
import { diffLines } from './diff'

const props = defineProps<{
  /** 当前（最新）正文 —— 用来和选中的历史版对比。 */
  currentText: string
  /** 当前名称，显示在标题里。 */
  currentName?: string
}>()

const emit = defineEmits<{ (e: 'restored'): void }>()

const visible = ref(false)
const configId = ref<string | null>(null)
const list = ref<PromptConfigVersionSummary[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const selectedNo = ref<number | null>(null)
const detail = ref<PromptConfigVersionDetail | null>(null)
const detailLoading = ref(false)

const rows = computed(() => {
  if (!detail.value) {
    return []
  }
  return diffLines(detail.value.callWord, props.currentText)
})

const changedCount = computed(() => rows.value.filter((r) => r.kind !== 'same').length)

async function open(id: string, name?: string) {
  configId.value = id
  visible.value = true
  list.value = []
  detail.value = null
  selectedNo.value = null
  error.value = null
  loading.value = true
  try {
    list.value = await promptsApi.listPromptConfigVersions(id)
    // 默认看"最近一次改动"：列表是新的在前，第一条就是刚被换下来的那一版。
    if (list.value.length > 0) {
      await select(list.value[0].versionNo)
    }
  } catch (e) {
    error.value = errText(e)
  } finally {
    loading.value = false
  }
  void name
}

async function select(versionNo: number) {
  if (!configId.value) {
    return
  }
  selectedNo.value = versionNo
  detailLoading.value = true
  try {
    detail.value = await promptsApi.getPromptConfigVersion(configId.value, versionNo)
  } catch (e) {
    detail.value = null
    ElMessage.error(errText(e))
  } finally {
    detailLoading.value = false
  }
}

async function restore() {
  if (!configId.value || selectedNo.value == null) {
    return
  }
  const no = selectedNo.value
  try {
    await ElMessageBox.confirm(
      `把「v${no}」的正文写回成当前版本。历史不会丢 —— 这会生成一个新版本，v${no} 仍然留在列表里。`,
      '回滚到这个版本',
      { confirmButtonText: '回滚', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await promptsApi.restorePromptConfigVersion(configId.value, no)
    ElMessage.success(`已用 v${no} 的内容生成新版本`)
    visible.value = false
    emit('restored')
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

function errText(e: unknown): string {
  const anyError = e as { response?: { data?: { message?: string } }; message?: string }
  return anyError?.response?.data?.message || anyError?.message || '读取失败'
}

function fmtTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

defineExpose({ open })
</script>

<template>
  <el-drawer v-model="visible" :size="720" :with-header="true" title="版本历史">
    <template #header>
      <div class="vh-head">
        <span class="vh-title">版本历史</span>
        <span v-if="currentName" class="vh-name micro muted ellipsis">{{ currentName }}</span>
      </div>
    </template>

    <div v-if="error" class="notice err">{{ error }}</div>
    <div v-else-if="loading" class="state muted">正在读取版本…</div>
    <div v-else-if="list.length === 0" class="state">
      <div class="state-title">还没有历史版本</div>
      <div class="micro muted">
        改一次之后，改之前的那一版会出现在这里 —— 现在这条提示词只有建出来时的样子。
      </div>
    </div>

    <div v-else class="vh-body">
      <!-- 左：版本列表 -->
      <aside class="vh-list">
        <div class="vh-list-title micro muted">共 {{ list.length }} 个历史版本</div>
        <button
          v-for="v in list"
          :key="v.versionNo"
          class="vh-item"
          :class="{ on: selectedNo === v.versionNo }"
          type="button"
          @click="select(v.versionNo)"
        >
          <span class="vh-no num">v{{ v.versionNo }}</span>
          <span class="vh-meta micro muted">{{ fmtTime(v.savedAt) }}</span>
          <span class="vh-preview micro">{{ v.preview }}</span>
        </button>
      </aside>

      <!-- 右：该版 → 当前 -->
      <section class="vh-diff">
        <header class="vh-diff-head">
          <div>
            <span class="vh-diff-title">v{{ selectedNo }} → 当前</span>
            <span class="micro muted vh-diff-sum">
              {{ changedCount === 0 ? '正文没有变化' : `${changedCount} 行有差异` }}
            </span>
          </div>
          <el-button size="small" :disabled="selectedNo == null" @click="restore">
            回滚到 v{{ selectedNo }}
          </el-button>
        </header>
        <div v-if="detailLoading" class="state muted">正在读取内容…</div>
        <div v-else class="vh-lines">
          <div
            v-for="(row, i) in rows"
            :key="i"
            class="vh-line"
            :class="row.kind"
          >
            <span class="vh-mark">{{ row.kind === 'added' ? '+' : row.kind === 'removed' ? '−' : ' ' }}</span>
            <span class="vh-text">{{ row.text || ' ' }}</span>
          </div>
        </div>
        <div class="micro muted vh-foot">
          绿底 = 当前新增的；红底删除线 = 这一版有、现在没有的。只比正文，名称与分类的改动看列表时间。
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<style scoped>
.vh-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  min-width: 0;
}

.vh-title {
  font-size: var(--text-md);
  font-weight: 600;
  letter-spacing: 0;
  color: var(--color-ink);
}

.vh-name {
  min-width: 0;
}

.state {
  padding: var(--space-lg) 0;
}

.state-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-ink);
  margin-bottom: var(--space-2xs);
}

.vh-body {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: var(--space-md);
  min-height: 0;
}

.vh-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
  border-right: 1px solid var(--color-rule);
  padding-right: var(--space-sm);
  max-height: 62vh;
  overflow-y: auto;
}

.vh-list-title {
  padding-bottom: var(--space-2xs);
}

.vh-item {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--space-3xs) var(--space-2xs);
  text-align: left;
  border: 1px solid var(--color-rule);
  background: var(--color-surface);
  border-radius: var(--radius-sm);
  padding: var(--space-2xs) var(--space-xs);
  cursor: pointer;
}

.vh-item:hover {
  border-color: var(--color-accent-line);
}

.vh-item.on {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
}

.vh-no {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-ink);
}

.vh-meta {
  text-align: right;
}

.vh-preview {
  grid-column: 1 / -1;
  color: var(--color-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vh-diff {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.vh-diff-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--color-rule);
  margin-bottom: var(--space-xs);
}

.vh-diff-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-ink);
}

.vh-diff-sum {
  margin-left: var(--space-xs);
}

.vh-lines {
  flex: 1;
  overflow-y: auto;
  max-height: 56vh;
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}

.vh-line {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  gap: var(--space-2xs);
  padding: 2px var(--space-xs);
  font-size: var(--text-sm);
  line-height: 1.7;
}

.vh-mark {
  color: var(--color-muted);
  text-align: center;
  user-select: none;
}

.vh-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.vh-line.added {
  background: color-mix(in oklch, var(--color-ok) 12%, transparent);
}

.vh-line.added .vh-mark {
  color: var(--color-ok);
}

.vh-line.removed {
  background: color-mix(in oklch, var(--color-danger) 10%, transparent);
}

.vh-line.removed .vh-text {
  text-decoration: line-through;
  color: var(--color-muted);
}

.vh-line.removed .vh-mark {
  color: var(--color-danger);
}

.vh-foot {
  padding-top: var(--space-2xs);
}
</style>
