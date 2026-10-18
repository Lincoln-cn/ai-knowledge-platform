<template>
  <aside class="history panel">
    <div class="hd">
      <span class="label">历史会话</span>
    </div>

    <div class="list scroll-y">
      <div v-if="loading" class="state micro muted">加载中…</div>
      <div v-else-if="loadError" class="notice err micro">
        {{ loadError.reason }}
        <el-button size="small" class="mt8" @click="emit('reload')">重试</el-button>
      </div>
      <template v-else>
        <div v-for="[label, items] in groups" :key="label" class="group">
          <div class="group-label label">{{ label }}</div>
          <template v-for="c in items" :key="c.id">
            <div
              class="item"
              :class="{ active: c.id === activeId }"
              @click="emit('select', c)"
            >
              <span class="name ellipsis">{{ c.title }}</span>
              <span class="ops">
                <button type="button" class="op" title="重命名" @click.stop="startRename(c)">
                  <el-icon><Edit /></el-icon>
                </button>
                <button type="button" class="op" title="删除" @click.stop="emit('remove', c)">
                  <el-icon><Delete /></el-icon>
                </button>
              </span>
            </div>
            <!-- 行内重命名 -->
            <div v-if="renamingId === c.id" class="rename-row">
              <el-input
                v-model="renameText"
                size="small"
                placeholder="会话名称"
                @keyup.enter="confirmRename"
                @keyup.esc="cancelRename"
              />
              <el-button size="small" type="primary" @click="confirmRename">确定</el-button>
              <el-button size="small" @click="cancelRename">取消</el-button>
            </div>
          </template>
        </div>
        <div v-if="!groups.length" class="state micro muted">暂无历史会话</div>
      </template>
    </div>

    <div class="ft">
      <el-button size="small" class="new-btn" @click="emit('create')">
        <el-icon><Plus /></el-icon><span>新建会话</span>
      </el-button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Delete, Edit, Plus } from '@element-plus/icons-vue'
import type { ConversationEx } from '../composables/useChatSession'

defineProps<{
  groups: Array<[string, ConversationEx[]]>
  loading?: boolean
  loadError?: { reason: string; hint?: string } | null
  activeId?: string | null
}>()

const emit = defineEmits<{
  select: [conv: ConversationEx]
  remove: [conv: ConversationEx]
  rename: [conv: ConversationEx, title: string]
  create: []
  reload: []
}>()

const renamingId = ref<string | null>(null)
const renameTarget = ref<ConversationEx | null>(null)
const renameText = ref('')

const startRename = (c: ConversationEx) => {
  renamingId.value = c.id
  renameTarget.value = c
  renameText.value = c.title
}

const cancelRename = () => {
  renamingId.value = null
  renameTarget.value = null
}

const confirmRename = () => {
  const target = renameTarget.value
  const title = renameText.value.trim()
  if (!target || !title) return
  emit('rename', target, title)
  cancelRename()
}
</script>

<style scoped>
.history {
  width: var(--history-w, 220px);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.hd {
  padding: var(--space-sm) var(--space-md) var(--space-xs);
  flex-shrink: 0;
  border-bottom: 1px solid var(--color-rule);
}

.list {
  flex: 1;
  min-height: 0;
  padding: var(--space-xs) var(--space-xs) var(--space-xs);
}

.state {
  padding: var(--space-sm);
}

.mt8 {
  margin-top: var(--space-xs);
}

.group {
  margin-bottom: var(--space-2xs);
}

.group-label {
  padding: var(--space-xs) var(--space-xs) var(--space-2xs);
}

/* 选中态：accent 左竖条 + ink 文字，不整块彩底 */
.item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius);
  cursor: pointer;
  font-size: var(--text-xs);
  color: var(--color-ink-2);
  transition: background var(--dur-micro) var(--ease-out);
}

.item:hover {
  background: var(--color-hover);
}

.item.active {
  background: transparent;
  color: var(--color-ink);
  font-weight: 500;
}

.item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: var(--space-2xs);
  bottom: var(--space-2xs);
  width: 2px;
  background: var(--color-accent);
  border-radius: var(--radius-xs);
}

.name {
  flex: 1;
  min-width: 0;
}

.ops {
  display: none;
  gap: var(--space-3xs);
}

.item:hover .ops {
  display: inline-flex;
}

.op {
  border: none;
  background: transparent;
  cursor: pointer;
  padding: var(--space-3xs);
  border-radius: var(--radius-xs);
  color: var(--color-muted);
  display: inline-flex;
  align-items: center;
}

.op:hover {
  background: var(--color-rule-2);
  color: var(--color-ink);
}

.rename-row {
  display: flex;
  gap: var(--space-2xs);
  padding: var(--space-2xs) var(--space-xs) var(--space-xs);
  align-items: center;
}

.ft {
  padding: var(--space-sm);
  border-top: 1px solid var(--color-rule);
  flex-shrink: 0;
}

.new-btn {
  width: 100%;
}
</style>
