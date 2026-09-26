<template>
  <el-drawer
    v-model="visible"
    :title="assistant?.name ?? '应用详情'"
    size="420px"
    class="detail-drawer"
  >
    <div v-if="assistant" class="detail">
      <div class="d-head">
        <span class="d-icon">{{ assistant.icon }}</span>
        <div class="d-head-text">
          <div class="d-name-row">
            <span class="h3">{{ assistant.name }}</span>
            <el-tag v-if="originOf(assistant) === 'official'" size="small" effect="plain" type="info">官方</el-tag>
            <el-tag v-else-if="originOf(assistant) === 'mine'" size="small" effect="plain" type="success">我的</el-tag>
            <el-tag v-else size="small" effect="plain" type="warning">
              由 {{ assistant.ownerName || '他人' }} 分享
            </el-tag>
            <el-tag size="small" effect="plain" :type="assistant.published ? 'success' : 'info'">
              {{ assistant.published ? '已发布' : '草稿' }}
            </el-tag>
          </div>
        </div>
      </div>

      <!-- 介绍 -->
      <section class="d-section">
        <h4 class="d-label label">介绍</h4>
        <p class="prose d-desc">{{ assistant.description || '暂无介绍' }}</p>
      </section>

      <!-- 能力 -->
      <section class="d-section">
        <h4 class="d-label label">能力</h4>
        <dl class="d-facts">
          <div class="fact">
            <dt class="label">类型</dt>
            <dd>{{ assistant.type === 'workflow' ? '编排应用' : '简单应用' }}</dd>
          </div>
          <template v-if="assistant.type === 'workflow'">
            <div class="fact">
              <dt class="label">节点数</dt>
              <dd><span class="num">{{ assistant.workflow?.nodeCount ?? 0 }}</span> 个</dd>
            </div>
            <div class="fact">
              <dt class="label">版本</dt>
              <dd>
                <span v-if="assistant.workflow?.publishedVersion" class="num">v{{ assistant.workflow.publishedVersion }}</span>
                <span v-else class="faint">未发布</span>
              </dd>
            </div>
          </template>
          <template v-else>
            <div class="fact">
              <dt class="label">模型</dt>
              <dd>{{ assistant.modelName || '—' }}</dd>
            </div>
            <div class="fact">
              <dt class="label">知识库</dt>
              <dd><span class="num">{{ assistant.kbIds.length }}</span> 个</dd>
            </div>
          </template>
          <div class="fact">
            <dt class="label">调用</dt>
            <dd><span class="num">{{ assistant.callCount.toLocaleString() }}</span> 次</dd>
          </div>
        </dl>
      </section>

      <!-- 标签 -->
      <section v-if="assistant.tags?.length" class="d-section">
        <h4 class="d-label label">分类</h4>
        <div class="d-tags">
          <span v-for="t in assistant.tags" :key="t" class="tag-chip">{{ t }}</span>
        </div>
      </section>

      <!-- 示例问 -->
      <section v-if="assistant.quickQuestions?.length" class="d-section">
        <h4 class="d-label label">示例问</h4>
        <ul class="d-examples">
          <li v-for="q in assistant.quickQuestions" :key="q">
            <button type="button" class="example-chip" @click="emit('start', assistant.id)">{{ q }}</button>
          </li>
        </ul>
      </section>

      <!-- 开场白 -->
      <section v-if="assistant.prologue" class="d-section">
        <h4 class="d-label label">开场白</h4>
        <p class="prose d-desc">{{ assistant.prologue }}</p>
      </section>
    </div>

    <template #footer>
      <div v-if="assistant" class="d-footer">
        <el-button
          :type="assistant.favorited ? 'warning' : undefined"
          @click="emit('favorite', assistant)"
        >{{ assistant.favorited ? '已收藏' : '收藏' }}</el-button>
        <el-button type="primary" @click="emit('start', assistant.id)">开始对话</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Assistant, AssistantOrigin } from '@/api/assistants'
import { useSessionStore } from '@/stores/session'

const props = defineProps<{ assistant: Assistant | null }>()
const emit = defineEmits<{
  start: [appId: string]
  favorite: [assistant: Assistant]
}>()

const session = useSessionStore()
const visible = ref(false)

const meId = computed(() => session.user?.id ?? '')

const originOf = (a: Assistant): AssistantOrigin => {
  if (a.builtin) return 'official'
  return a.ownerId && a.ownerId === meId.value ? 'mine' : 'shared'
}

const open = (a: Assistant) => {
  visible.value = true
  void props.assistant // 保持响应式依赖
  void a
}

defineExpose({ open })
</script>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

.d-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
}

.d-icon {
  font-size: var(--text-2xl);
  line-height: 1;
}

.d-head-text {
  min-width: 0;
}

.d-name-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.d-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.d-label {
  margin: 0;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
}

.d-desc {
  margin: 0;
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-ink-2);
}

.d-facts {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.fact {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  font-size: var(--text-xs);
  line-height: var(--leading-ui);
}

.fact dt {
  width: 4.5em;
  flex-shrink: 0;
  color: var(--color-muted);
}

.fact dd {
  margin: 0;
  min-width: 0;
  color: var(--color-ink-2);
}

.d-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2xs);
}

.tag-chip {
  font-size: var(--text-2xs);
  line-height: var(--leading-ui);
  padding: 0 var(--space-2xs);
  border: 1px solid var(--color-rule-2);
  border-radius: var(--radius-sm);
  color: var(--color-muted);
  background: var(--color-sunken);
}

.d-examples {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.example-chip {
  display: block;
  width: 100%;
  text-align: left;
  padding: var(--space-2xs) var(--space-sm);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm);
  background: var(--color-sunken);
  cursor: pointer;
  font-family: inherit;
  font-size: var(--text-xs);
  line-height: var(--leading-ui);
  color: var(--color-ink-2);
  transition:
    background var(--dur-micro) var(--ease-out),
    border-color var(--dur-micro) var(--ease-out);
}

.example-chip:hover {
  background: var(--color-hover);
  border-color: var(--color-accent-line);
  color: var(--color-ink);
}

.example-chip:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 1px;
}

.d-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-xs);
}

.num {
  font-variant-numeric: tabular-nums;
}

.faint {
  color: var(--color-faint);
}
</style>
