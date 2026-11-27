<script setup lang="ts">
/**
 * 第 1 层机械断言编辑框（`ROADMAP §11.63.2`）。
 *
 * 一件事：把「这个角色设定里必须满足的要求」写成几条能机器判的条目，挂在这道题上。
 *
 * 四个设计取舍：
 * - **一次保存整张表**：断言是一组互相配合的判据，点一次保存才是完整的一份；逐条即时保存会留下"改了两条、第三条没存"的半截状态。
 * - **每条只比一件事**：`CONTAINS` 与 `MAX_CHARS` 混成一条，读数就只能告诉你"没过"，说不出"哪条要求没被满足"。
 * - **只影响以后的运行**：已跑完的读数是当时的快照，改定义不会回头改写它们（要看新定义就重跑一次）。
 * - **这一层不判语气**：它只回答"有没有第七条""有没有分点""有没有说不知道"，答得好不好不在这里。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { promptsApi } from '@/api'
import type { AssertionKind, PromptAssertion, PromptEvalItem } from '@/api/prompts'

const props = defineProps<{ item: PromptEvalItem | null }>()
const emit = defineEmits<{ (e: 'saved', item: PromptEvalItem): void }>()

const visible = ref(false)
const rows = ref<PromptAssertion[]>([])
const saving = ref(false)

/** 需要填值的类型；`LIST` / `UNCERTAIN` 本身就是判据，没有参数。 */
const NEEDS_VALUE: AssertionKind[] = ['CONTAINS', 'NOT_CONTAINS', 'REGEX', 'MAX_CHARS', 'MIN_CHARS']

const KINDS: { value: AssertionKind; label: string; hint: string }[] = [
  { value: 'CONTAINS', label: '必须出现', hint: '答案里要找的一段话' },
  { value: 'NOT_CONTAINS', label: '不许出现', hint: '比如敷衍话、内部编号' },
  { value: 'REGEX', label: '匹配正则', hint: '找得到就算过，不要求整段匹配' },
  { value: 'MAX_CHARS', label: '不超过 N 字', hint: '按去掉首尾空白后的长度算' },
  { value: 'MIN_CHARS', label: '不少于 N 字', hint: '防止一句话打发' },
  { value: 'LIST', label: '要分点', hint: '至少两个列表项' },
  { value: 'UNCERTAIN', label: '要说不知道', hint: '资料里没有时要承认' }
]

const kindHint = computed(() => {
  return (kind: AssertionKind) => KINDS.find((k) => k.value === kind)?.hint ?? ''
})

function needsValue(kind: AssertionKind) {
  return NEEDS_VALUE.includes(kind)
}

function open() {
  rows.value = (props.item?.assertions ?? []).map((a) => ({ ...a }))
  visible.value = true
}

function close() {
  visible.value = false
}

function addRow() {
  if (rows.value.length >= 20) {
    ElMessage.warning('一道题最多 20 条断言 —— 再多就不会有人逐条看了')
    return
  }
  rows.value.push({ id: '', label: '', kind: 'CONTAINS', value: '' })
}

function removeRow(index: number) {
  rows.value.splice(index, 1)
}

/** 换类型时把不适用的值清掉：留着旧的「必须出现」文本会让人以为它仍然生效。 */
function onKindChange(row: PromptAssertion) {
  if (!needsValue(row.kind)) {
    row.value = ''
  }
}

async function save() {
  if (!props.item) {
    return
  }
  // 空值先拦在本地：请求发出去再 400，用户要多读一句后端文案才知道是哪条
  for (let i = 0; i < rows.value.length; i++) {
    const r = rows.value[i]
    if (needsValue(r.kind) && !r.value.trim()) {
      ElMessage.warning(`第 ${i + 1} 条还没填内容`)
      return
    }
  }
  saving.value = true
  try {
    const saved = await promptsApi.replacePromptEvalAssertions(
      props.item.id,
      rows.value.map((r) => ({
        id: r.id ?? '',
        label: r.label.trim(),
        kind: r.kind,
        value: r.value.trim()
      }))
    )
    emit('saved', saved)
    ElMessage.success(rows.value.length === 0 ? '已清空断言' : `已保存 ${rows.value.length} 条断言`)
    close()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '断言没保存成功')
  } finally {
    saving.value = false
  }
}

watch(
  () => props.item?.id,
  () => {
    if (visible.value) {
      visible.value = false
    }
  }
)

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="断言（第 1 层）" width="680px" append-to-body>
    <p class="ad-lead">
      这一层只判<strong>形式</strong>：有没有出现关键结论、有没有分点、多少字、资料里没有时有没有说实话。
      语气是否得体、判断是否符合业务口径，这一层测不了。
    </p>

    <div class="ad-rows">
      <div v-for="(row, i) in rows" :key="row.id || `new-${i}`" class="ad-row">
        <span class="ad-idx">{{ i + 1 }}</span>
        <el-select v-model="row.kind" size="small" class="ad-kind" @change="onKindChange(row)">
          <el-option v-for="k in KINDS" :key="k.value" :label="k.label" :value="k.value" />
        </el-select>
        <el-input
          v-if="needsValue(row.kind)"
          v-model="row.value"
          size="small"
          class="ad-value"
          :placeholder="kindHint(row.kind)"
        />
        <span v-else class="ad-void micro muted">{{ kindHint(row.kind) }}</span>
        <el-input
          v-model="row.label"
          size="small"
          class="ad-label"
          placeholder="给它起个名字（可留空）"
        />
        <el-button text size="small" type="danger" @click="removeRow(i)">×</el-button>
      </div>

      <div v-if="rows.length === 0" class="ad-empty">
        还没有断言。<br />
        先想一句「这条答案必须满足什么」—— 比如必须列出第几条、必须分点、说不知道时要承认。
      </div>
    </div>

    <el-button size="small" @click="addRow">加一条</el-button>

    <template #footer>
      <span class="micro muted ad-foot">
        改定义只影响以后的运行；已经跑完的那次读数是当时的快照。
      </span>
      <el-button size="small" @click="close">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ad-lead {
  margin: 0 0 var(--space-sm);
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--color-ink-2);
}

.ad-rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  margin-bottom: var(--space-sm);
  max-height: 46vh;
  overflow-y: auto;
}

.ad-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.ad-idx {
  flex: 0 0 18px;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--color-ink-faint);
  text-align: right;
}

.ad-kind {
  flex: 0 0 130px;
}

.ad-value {
  flex: 1 1 auto;
  min-width: 0;
}

.ad-void {
  flex: 1 1 auto;
  min-width: 0;
}

.ad-label {
  flex: 0 0 170px;
}

.ad-empty {
  padding: var(--space-md);
  border: 1px dashed var(--color-rule);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--color-ink-muted);
  text-align: center;
}

.ad-foot {
  margin-right: auto;
  float: left;
  line-height: 2;
}
</style>
