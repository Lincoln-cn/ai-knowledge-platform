<template>
  <div class="keys panel">
    <div class="sec-hd">
      <span class="h4">API Key</span>
      <el-button size="small" type="primary" :loading="creating" @click="regenerate">生成 / 轮换</el-button>
    </div>
    <p class="micro muted">
      API Key 用于<b>程序化调用</b>，与上方「访问凭证」（免登录公开链接）是<b>两套机制</b>。
      轮换会生成新 Key，旧 Key 保留可单独停用。
    </p>

    <el-table v-loading="loading" :data="list" size="small">
      <el-table-column label="API Key" min-width="220">
        <template #default="{ row }">
          <div class="token-line">
            <code class="ellipsis">{{ row.key }}</code>
            <el-button link size="small" type="primary" @click="copy(row.key)">复制</el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="最近使用" width="150">
        <template #default="{ row }">
          <span v-if="row.lastUsedAt" class="micro">{{ formatTime(row.lastUsedAt) }}</span>
          <span v-else class="micro muted">未使用</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.enabled" size="small" type="success" effect="plain">启用</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link size="small" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { assistantsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { ApiKeyItem } from '@/api/assistants'

const props = defineProps<{ appId: string }>()

const list = ref<ApiKeyItem[]>([])
const loading = ref(false)
const creating = ref(false)

const formatTime = (iso: string) => dayjs(iso).format('YYYY-MM-DD HH:mm')

const copy = async (text: string) => {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

const fetch = async () => {
  loading.value = true
  try {
    list.value = await assistantsApi.listApiKeys(props.appId)
  } finally {
    loading.value = false
  }
}

const regenerate = async () => {
  creating.value = true
  try {
    await assistantsApi.regenerateApiKey(props.appId)
    ElMessage.success('已生成新 API Key')
    await fetch()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '生成失败')
  } finally {
    creating.value = false
  }
}

const toggle = async (row: ApiKeyItem) => {
  try {
    await assistantsApi.setApiKeyEnabled(props.appId, row.id, !row.enabled)
    ElMessage.success(row.enabled ? '已停用，该 Key 将不再通过鉴权' : '已启用')
    await fetch()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

defineExpose({ fetch })
</script>

<style scoped>
.keys {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.sec-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.token-line {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

code {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
}
</style>
