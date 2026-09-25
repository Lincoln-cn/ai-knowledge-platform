<template>
  <div class="ref-panel">
    <div class="hd">
      <span class="label">引用来源</span>
      <button v-if="closable" type="button" class="x" title="关闭 (Esc)" @click="emit('close')">×</button>
    </div>

    <div v-if="!refs.length" class="empty">
      <p class="micro muted">本条回答没有可回溯的分段。未挂知识库的应用属正常现象，不是故障。</p>
    </div>

    <div v-else class="list scroll-y">
      <div
        v-for="ref in refs"
        :key="ref.index"
        class="ref-card"
        :class="{ active: ref.index === activeIndex }"
        @click="emit('select', ref.index)"
      >
        <div class="row1">
          <span class="idx num">[{{ ref.index }}]</span>
          <span class="name ellipsis">{{ ref.docName }}</span>
          <span class="score num">{{ ref.score.toFixed(2) }}</span>
        </div>
        <div class="title ellipsis">{{ ref.title }}</div>
        <div class="kb micro muted">知识库：{{ ref.kbName }}</div>
        <div v-if="ref.full" class="full micro">{{ ref.full }}</div>
        <div v-else class="full micro muted">
          （契约未返回分段全文，可跳转到分段管理查看原文）
        </div>
        <button type="button" class="jump" @click.stop="emit('jump', ref)">
          跳转到分段管理
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { SourceRef } from '@/api/stream-types'
import type { SourceRefEx } from '../composables/useChatSession'

defineProps<{
  refs: SourceRef[]
  activeIndex?: number | null
  closable?: boolean
}>()

const emit = defineEmits<{
  close: []
  select: [index: number]
  jump: [ref: SourceRefEx]
}>()
</script>

<style scoped>
.ref-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-sm) var(--space-md);
  border-bottom: 1px solid var(--color-rule);
  flex-shrink: 0;
}

.x {
  border: none;
  background: transparent;
  font-size: var(--text-md);
  line-height: 1;
  cursor: pointer;
  color: var(--color-muted);
  padding: var(--space-3xs) var(--space-xs);
  border-radius: var(--radius-sm);
}

.x:hover {
  background: var(--color-hover);
  color: var(--color-ink);
}

.empty {
  padding: var(--space-lg) var(--space-md);
}

.list {
  padding: var(--space-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  min-height: 0;
}

/* 仪器读数：文档名 / 相似度 / 页码走 mono + 等宽数字 */
.ref-card {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  padding: var(--space-sm) var(--space-md);
  cursor: pointer;
  background: var(--color-surface);
  transition:
    border-color var(--dur-micro) var(--ease-out),
    background var(--dur-micro) var(--ease-out);
}

.ref-card:hover {
  border-color: var(--color-accent-line);
}

.ref-card.active {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.row1 {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--text-2xs);
}

.idx {
  color: var(--color-accent);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.name {
  flex: 1;
  color: var(--color-ink-2);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--text-2xs);
}

.score {
  color: var(--color-accent);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--text-2xs);
  flex-shrink: 0;
}

.title {
  margin-top: var(--space-2xs);
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--color-ink);
}

.kb {
  margin-top: var(--space-3xs);
}

.full {
  margin-top: var(--space-xs);
  line-height: var(--leading-body);
  color: var(--color-ink-2);
  display: -webkit-box;
  -webkit-line-clamp: 5;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.jump {
  margin-top: var(--space-xs);
  border: none;
  background: transparent;
  color: var(--color-accent);
  font-size: var(--text-2xs);
  font-family: inherit;
  cursor: pointer;
  padding: 0;
}

.jump:hover {
  text-decoration: underline;
}
</style>
