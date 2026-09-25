<template>
  <div class="panel wrap">
    <div class="hd">
      <span class="h4">提示词配置</span>
      <div class="row gap-8">
        <el-select v-model="typeId" size="small" placeholder="全部类别" style="width: 140px" @change="emit('filter', typeId)">
          <el-option label="全部类别" value="" />
          <el-option v-for="t in types" :key="t.id" :label="t.typeName" :value="t.id" />
        </el-select>
        <el-input
          v-model="keyword"
          size="small"
          placeholder="搜索角色名称 / 提示词"
          style="width: 200px"
          clearable
          @keyup.enter="emit('search', keyword)"
          @clear="emit('search', '')"
        />
        <el-button size="small" type="primary" @click="emit('create')">新建提示词</el-button>
      </div>
    </div>

    <!-- 六列对齐 workBench/markedSystem/markedConfig.vue 基准 -->
    <el-table v-loading="loading" :data="list" size="small">
      <el-table-column label="图标" width="60">
        <template #default="{ row }"><span class="icon">{{ row.icon }}</span></template>
      </el-table-column>
      <el-table-column label="角色名称" prop="roleName" min-width="120" show-overflow-tooltip />
      <el-table-column label="提示词" prop="callWord" min-width="200" show-overflow-tooltip />
      <el-table-column label="类别" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.typeName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="描述" prop="description" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link size="small" type="primary" @click="emit('edit', row)">编辑</el-button>
          <el-button link size="small" type="danger" @click="emit('remove', row)">删除</el-button>
          <span v-if="row.usageCount > 0" class="micro muted">被 {{ row.usageCount }} 应用引用</span>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        :current-page="page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="(p: number) => emit('page', p)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { PromptConfig, PromptType } from '@/api/prompts'

const props = defineProps<{
  list: PromptConfig[]
  types: PromptType[]
  loading: boolean
  total: number
  page: number
  pageSize: number
  activeTypeId: string
  activeKeyword: string
}>()

const emit = defineEmits<{
  create: []
  edit: [PromptConfig]
  remove: [PromptConfig]
  filter: [string]
  search: [string]
  page: [number]
}>()

const typeId = ref(props.activeTypeId)
const keyword = ref(props.activeKeyword)

watch(() => props.activeTypeId, (v) => (typeId.value = v))
watch(() => props.activeKeyword, (v) => (keyword.value = v))
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
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.icon {
  font-size: var(--text-md);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-xs);
}
</style>
