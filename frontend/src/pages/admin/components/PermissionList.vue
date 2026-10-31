<template>
  <div class="perm-list">
    <div class="notice info">
      权限点是<b>代码常量</b>（<code>src/constants/permissions.ts</code>），启动时由后端幂等同步。
      本清单只读 —— 新增功能时自动多出一项，再在「角色管理」里分配给角色即可。
    </div>

    <div class="filters panel">
      <el-input
        v-model="keyword"
        size="small"
        placeholder="搜索名称、编码或模块"
        style="width: 230px"
        clearable
        :prefix-icon="Search"
      />
      <span class="micro muted">共 {{ filtered.length }} 项</span>
    </div>

    <div class="panel" style="overflow: hidden">
      <el-table v-loading="loading" :data="filtered" style="width: 100%">
        <!-- 空态分两件事：搜不到 vs 一条都没有。权限点是代码里定义的目录，不是可增删的数据 -->
        <template #empty>
          <div class="empty">
            <template v-if="keyword">
              <div class="empty-title">没有匹配「{{ keyword }}」的权限点</div>
              <div>换个关键词，或清空搜索看全部</div>
              <el-button link size="small" type="primary" @click="keyword = ''">清空搜索</el-button>
            </template>
            <template v-else>
              <div class="empty-title">还没有取到权限点</div>
              <div>权限点由平台在代码里定义（前端 `permissions.ts` + 后端权限目录），不在这里新增</div>
            </template>
          </div>
        </template>
        <el-table-column label="编码" min-width="220">
          <template #default="{ row }">
            <code class="micro">{{ row.code }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="label" label="名称" min-width="160" />
        <el-table-column label="模块" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.module }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { identityApi } from '@/api'
import type { PermissionItem } from '@/api/identity'

const list = ref<PermissionItem[]>([])
const loading = ref(false)
const keyword = ref('')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter(
    (p) =>
      p.code.toLowerCase().includes(kw) ||
      p.label.toLowerCase().includes(kw) ||
      p.module.toLowerCase().includes(kw)
  )
})

onMounted(async () => {
  loading.value = true
  try {
    list.value = await identityApi.listPermissions()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.perm-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
}
</style>
