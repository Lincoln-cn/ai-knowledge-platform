<template>
  <div class="panel wrap">
    <div class="hd">
      <span class="h4">提示词类型</span>
      <el-button size="small" type="primary" @click="emit('create')">新建类型</el-button>
    </div>
    <el-table v-loading="loading" :data="list" size="small">
      <el-table-column label="图标" width="60">
        <template #default="{ row }"><span class="icon">{{ row.icon }}</span></template>
      </el-table-column>
      <el-table-column label="类型名称" prop="typeName" min-width="140" />
      <el-table-column label="提示词数" width="100" prop="promptCount" />
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button link size="small" type="primary" @click="emit('edit', row)">编辑</el-button>
          <el-button link size="small" type="danger" @click="emit('remove', row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import type { PromptType } from '@/api/prompts'

defineProps<{
  list: PromptType[]
  loading: boolean
}>()

const emit = defineEmits<{
  create: []
  edit: [PromptType]
  remove: [PromptType]
}>()
</script>

<style scoped>
.wrap {
  padding: var(--space-md);
}

.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-xs);
}

.icon {
  font-size: var(--text-md);
}
</style>
