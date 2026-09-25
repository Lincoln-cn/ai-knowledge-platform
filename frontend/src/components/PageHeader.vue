<template>
  <header class="page-header">
    <div class="hd-main">
      <h2 class="hd-title">
        {{ title }}
        <slot name="after-title" />
      </h2>
      <p v-if="desc" class="hd-desc micro muted">{{ desc }}</p>
    </div>
    <div class="hd-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
/** 页面标题条。全平台统一一处，避免每页各写一遍标题排版。 */
defineProps<{
  title: string
  desc?: string
}>()
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.hd-main {
  min-width: 0;
}

.hd-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}

.hd-desc {
  margin: 5px 0 0;
  line-height: 1.7;
  max-width: 820px;
}

.hd-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
