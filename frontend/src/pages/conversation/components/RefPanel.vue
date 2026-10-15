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
        <!-- ★ 标签按来源类型切换：知识库来的引用写「知识库：」，会话临时附件写「来源：」。
             会话附件没有知识库行，写成「知识库：会话附件」读起来是错的。 -->
        <div class="kb micro muted">{{ isConversationDomain(ref) ? '来源：' : '知识库：' }}{{ ref.kbName }}</div>
        <div v-if="ref.full" class="full micro">{{ ref.full }}</div>
        <div v-else-if="!isConversationDomain(ref)" class="full micro muted">
          （契约未返回分段全文，可跳转到分段管理查看原文）
        </div>
        <div v-else class="full micro muted">（契约未返回分段全文）</div>
        <div class="row-actions">
          <button type="button" class="jump" @click.stop="emit('back', ref.index)">
            回到原句
          </button>
          <!-- ★ 会话域引用不出现这个按钮：它没有知识库，跳过去只会是空页 ——
               本项目最防的静默 no-op（#91 同族）。「回到原句」仍然有意义，保留。 -->
          <button
            v-if="!isConversationDomain(ref)"
            type="button"
            class="jump"
            @click.stop="emit('jump', ref)"
          >
            跳转到分段管理
          </button>
        </div>
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
  /** 回到原句：滚回正文里的 [N] 角标 */
  back: [index: number]
}>()

/**
 * ★ 会话域判据（本文件唯一一处实现）—— **结构性**，不是拿 `kbName` 去比字符串。
 *
 * 会话临时附件**没有知识库行**（`kb_document.scope_kind = 'CONVERSATION'`，库 id 为空），
 * 所以它的引用永远拿不到 `kbId`；而真有某个知识库叫「会话附件」时，它的引用照样带着自己的
 * `kbId` ⇒ 用「缺 kbId」判会话域不会误判，而字符串判据会（那是这条口径唯一的坑）。
 *
 * ★ 前提（本批实测，逐字见交付报告）：**知识库来的引用必须真的带上 `kbId`**。
 *   契约 `SourceRef.kbId?` 是**可选**字段，后端自己的测试夹具都填了它
 *   （`StreamFixture` / `MessagePersistenceTest`），但生产装配点 `ChatService.retrieve`
 *   目前把 `documentId` / `kbId` 一起传了 `null` ⇒ 这条判据要等后端在装配处补上 `kbId`
 *   （左连接里 `k.id` 已经是现成的）才在真栈上成立。
 */
const isConversationDomain = (ref: SourceRef): boolean => !ref.kbId
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

.row-actions {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-top: var(--space-xs);
}

.jump {
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
