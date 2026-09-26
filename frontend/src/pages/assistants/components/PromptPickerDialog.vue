<template>
  <el-dialog v-model="visible" title="选择提示词" width="720px" :close-on-click-modal="false">
    <div class="filters">
      <el-select v-model="typeId" size="small" placeholder="全部类型" style="width: 150px" @change="fetch">
        <el-option label="全部类型" value="" />
        <el-option v-for="t in types" :key="t.id" :label="t.typeName" :value="t.id" />
      </el-select>
      <el-input
        v-model="keyword"
        size="small"
        placeholder="搜索角色名称或提示词"
        style="width: 220px"
        clearable
        @keyup.enter="fetch"
        @clear="fetch"
      />
      <span class="grow" />
      <span class="micro muted">已选 {{ selectedIds.length }} 条</span>
    </div>

    <div v-loading="loading" class="list">
      <label
        v-for="item in list"
        :key="item.id"
        class="pick-item"
        :class="{ on: selectedIds.includes(item.id) }"
      >
        <el-checkbox
          :model-value="selectedIds.includes(item.id)"
          @change="toggle(item.id)"
        />
        <div class="pi-main">
          <div class="pi-head">
            <span class="pi-name">{{ item.icon }} {{ item.roleName }}</span>
            <el-tag size="small" effect="plain">{{ item.typeName }}</el-tag>
          </div>
          <p class="pi-call micro muted ellipsis">{{ item.callWord }}</p>
        </div>
      </label>
      <div v-if="!loading && !list.length" class="empty-hint micro muted">没有匹配的提示词</div>
    </div>

    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { promptsApi } from '@/api'
import type { PromptConfig, PromptType } from '@/api/prompts'

const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [string[]]; picked: [PromptConfig[]] }>()

const visible = ref(false)
const loading = ref(false)
const list = ref<PromptConfig[]>([])
const types = ref<PromptType[]>([])
const keyword = ref('')
const typeId = ref('')
const selectedIds = ref<string[]>([])
let pickedRows: PromptConfig[] = []

const fetch = async () => {
  loading.value = true
  try {
    const result = await promptsApi.listPromptConfigs({
      keyword: keyword.value || undefined,
      typeId: typeId.value || undefined,
      page: 1,
      size: 50
    })
    list.value = result.list
  } finally {
    loading.value = false
  }
}

const toggle = (id: string) => {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) selectedIds.value.splice(idx, 1)
  else selectedIds.value.push(id)
  pickedRows = list.value.filter((c) => selectedIds.value.includes(c.id))
}

const confirm = () => {
  emit('update:modelValue', [...selectedIds.value])
  emit('picked', pickedRows)
  visible.value = false
}

const open = async () => {
  selectedIds.value = [...props.modelValue]
  visible.value = true
  const typeResult = await promptsApi.listPromptTypes({ page: 1, size: 50 })
  types.value = typeResult.list
  await fetch()
  pickedRows = list.value.filter((c) => selectedIds.value.includes(c.id))
}

defineExpose({ open })
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-sm);
}

.list {
  max-height: 380px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  min-height: 120px;
}

.pick-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  cursor: pointer;
  transition: border-color var(--dur-micro) var(--ease-out),
    background-color var(--dur-micro) var(--ease-out);
}

.pick-item:hover {
  background: var(--color-hover);
}

.pick-item.on {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.pi-main {
  min-width: 0;
  flex: 1;
}

.pi-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.pi-name {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--color-ink);
}

.pi-call {
  margin: var(--space-2xs) 0 0;
}

.empty-hint {
  padding: var(--space-lg) 0;
  text-align: center;
}
</style>
