<template>
  <el-dialog
    v-model="visible"
    :title="`解析结果 · ${doc?.name ?? ''}`"
    width="720px"
    :close-on-click-modal="false"
    append-to-body
    @closed="reset"
  >
    <!-- 失败态：failReason / failHint 必须原文展示（DOC-I2）——
         不代写、不吞掉；0 字符/扫描件的提示就靠这里可见 -->
    <div v-if="doc && (doc.failReason || doc.failHint)" class="notice err fail-block">
      <div class="fail-title">{{ doc.name }} · 失败原因</div>
      <!-- ★ 原文：{{ doc.failReason }}，不截断、不替换 -->
      <p class="fail-reason">{{ doc.failReason || '未提供失败原因' }}</p>
      <p v-if="doc.failHint" class="fail-hint">{{ doc.failHint }}</p>
    </div>

    <div class="result-body">
      <div v-if="!hasContent" class="empty-tip micro muted">
        {{ emptyTip }}
      </div>
      <!-- ★ 按结构渲染 Markdown（标题/列表/表格），不是整段纯文本 ——
           复用共享 MdPreview（components/markdown/），它已支持标题/列表/表格 -->
      <MdPreview v-else :source="doc?.parsedContent || ''" />
    </div>

    <template #footer>
      <el-button size="small" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DocumentItem } from '@/api/ingestion'
import { MdPreview } from '@/components/markdown'

/**
 * 文档「解析结果」弹窗（0.2.1 批 3）。
 *
 * 两件事：
 *  1. `parsedContent` 按 Markdown 结构渲染（标题/列表/表格），供用户核对解析质量；
 *  2. 失败态把 `failReason` + `failHint` **原文**摆出来 —— 0 字符/扫描件的可读提示
 *     （「未检测到文本层…」「建议：转为可检索 PDF…」）必须能看到，不允许静默失败。
 */
defineOptions({ name: 'ParseResultDialog' })

const visible = ref(false)
const doc = ref<DocumentItem | null>(null)

const hasContent = computed(() => Boolean((doc.value?.parsedContent ?? '').trim()))

const emptyTip = computed(() => {
  const d = doc.value
  if (!d) return ''
  if (d.failReason) return '本次解析未得到有效正文（见上方失败原因）。部分内容可能已保留为空。'
  if (d.charCount === 0) return '解析结果为空（0 字符）——可能是扫描件无文本层或空白文档。'
  return '暂无解析正文。'
})

const open = (row: DocumentItem) => {
  doc.value = row
  visible.value = true
}

const reset = () => {
  doc.value = null
}

defineExpose({ open })
</script>

<style scoped>
.fail-block {
  margin-bottom: var(--space-sm);
}

.fail-title {
  font-size: var(--text-2xs);
  font-weight: 500;
  line-height: var(--leading-tight);
  margin-bottom: var(--space-2xs);
  color: var(--color-ink);
}

.fail-reason {
  margin: 0;
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.fail-hint {
  margin: var(--space-2xs) 0 0;
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
  opacity: 0.85;
}

.result-body {
  max-height: 52vh;
  overflow-y: auto;
  padding: var(--space-xs) var(--space-2xs);
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm);
}

.empty-tip {
  padding: var(--space-md);
  text-align: center;
}
</style>
