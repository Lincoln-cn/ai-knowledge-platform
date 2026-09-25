<template>
  <div class="app-info panel">
    <div class="icon">{{ app.icon }}</div>
    <div class="meta">
      <div class="name">{{ app.name }}</div>
      <div v-if="app.description" class="desc micro muted ellipsis">{{ app.description }}</div>
    </div>
    <div class="grow" />
    <el-tag v-if="app.modelName" size="small" effect="plain" type="info" class="model">
      {{ app.modelName }}
    </el-tag>
  </div>
</template>

<script setup lang="ts">
import type { AppBrief } from '../composables/useChatSession'

defineProps<{ app: AppBrief }>()
</script>

<style scoped>
.app-info {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-md);
  flex-shrink: 0;
}

.icon {
  width: 32px;
  height: 32px;
  border-radius: var(--radius);
  background: var(--color-accent-soft);
  color: var(--color-accent);
  display: grid;
  place-items: center;
  font-size: var(--text-sm);
  font-weight: 600;
  flex-shrink: 0;
}

.meta {
  min-width: 0;
}

.name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-ink);
  line-height: var(--leading-tight);
}

.desc {
  margin-top: var(--space-3xs);
  max-width: 520px;
}

.model {
  flex-shrink: 0;
}
</style>
