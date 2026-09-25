<template>
  <div class="tooltip-label">
    <span>{{ label }}</span>
    <el-tooltip v-if="tooltip" effect="dark" placement="right">
      <template #content>
        <div class="tip-body">{{ tooltip }}</div>
      </template>
      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
    </el-tooltip>
  </div>
</template>
<script setup lang="ts">
import { QuestionFilled } from '@element-plus/icons-vue'

defineProps<{
  label: string
  tooltip?: string
}>()
</script>
<style scoped>
.tooltip-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  min-width: 0;
}

.tip-icon {
  flex-shrink: 0;
  color: var(--color-muted);
  cursor: help;
  transition: color var(--dur-micro) var(--ease-out);
}

.tip-icon:hover {
  color: var(--color-accent);
}

.tip-body {
  width: 200px;
}
</style>
