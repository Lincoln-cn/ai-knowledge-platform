<template>
  <div class="md-renderer" @click="onClick">
    <template v-for="(sec, i) in sections" :key="i">
      <EchartsRander v-if="sec.type === 'echarts' && enableEcharts" :option="sec.content" />
      <FormRander
        v-else-if="sec.type === 'form' && enableForm"
        :form-setting="sec.content"
        :disabled="disabled"
        @submit="onFormSubmit"
      />
      <HtmlRander v-else-if="sec.type === 'html'" :source="sec.content" />
      <MdPreview
        v-else
        :source="sec.content"
        :citations="citations"
        class="md-section"
      />
    </template>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { FormValues } from '@/components/dynamic-form/types'
import EchartsRander from './EchartsRander.vue'
import FormRander from './FormRander.vue'
import HtmlRander from './HtmlRander.vue'
import MdPreview from './MdPreview.vue'
import { citeIndexFromEvent, splitSpecialSections } from './markdown'

/**
 * 多段落 Markdown 渲染器：普通 Markdown + 可选特殊块（图表 / 表单）。
 * 特殊块标签：`<echarts_rander>…</echarts_rander>`、`<form_rander>…</form_rander>`。
 * `<html_rander>` 只作转义文本展示（不再执行 HTML/脚本）。
 * 引注 `[N]` 与会话 AnswerBody.vue 同口径，`citations` 打开后生效。
 */
defineOptions({ name: 'MdRenderer' })

const props = withDefaults(
  defineProps<{
    source?: string
    /** 渲染可点击引注 [N]，抛出 cite */
    citations?: boolean
    enableEcharts?: boolean
    enableForm?: boolean
    disabled?: boolean
  }>(),
  {
    source: '',
    citations: false,
    enableEcharts: true,
    enableForm: true,
    disabled: false
  }
)

const emit = defineEmits<{
  cite: [index: number]
  submit: [data: FormValues]
}>()

const sections = computed(() => splitSpecialSections(props.source || ''))

function onClick(e: MouseEvent) {
  if (!props.citations) return
  const n = citeIndexFromEvent(e)
  if (n != null) emit('cite', n)
}

function onFormSubmit(data: FormValues) {
  emit('submit', data)
}
</script>
<style scoped>
.md-renderer {
  width: 100%;
}

.md-section {
  margin-bottom: var(--space-2xs);
}
</style>
