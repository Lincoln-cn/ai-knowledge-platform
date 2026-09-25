<template>
  <div class="html-rander">
    <!--
      旧版直接 innerHTML + 执行 script。内容来自模型时绝不能当 HTML 用，
      这里只作纯文本展示（模板插值自动转义）。
    -->
    <pre class="html-source">{{ source }}</pre>
  </div>
</template>
<script setup lang="ts">
/** 安全的 HTML 片段占位：转义展示，不注入、不执行脚本。 */
defineOptions({ name: 'HtmlRander' })

defineProps<{
  source?: string
}>()
</script>
<style scoped>
.html-rander {
  width: 100%;
}

.html-source {
  margin: 0;
  padding: var(--space-sm) var(--space-md);
  background: var(--color-sunken);
  border: 1px dashed var(--color-rule);
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-ink-2);
}
</style>
