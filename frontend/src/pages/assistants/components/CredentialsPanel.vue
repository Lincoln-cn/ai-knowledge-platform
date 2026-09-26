<template>
  <div class="cred panel">
    <div class="sec-hd">
      <span class="h4">公开链接与访问凭证</span>
      <el-button size="small" type="primary" @click="openCreate">生成凭证</el-button>
    </div>
    <p class="micro muted">
      访问凭证用于<b>免登录公开链接</b>；与下方 API Key 是两套机制，互不替代。
      <b>撤销是终态</b>：撤销后不可重新启用，只能重新生成。
    </p>

    <el-table v-loading="loading" :data="list" size="small">
      <el-table-column label="链接 / 令牌" min-width="220">
        <template #default="{ row }">
          <div class="token-line">
            <code class="ellipsis">{{ shareUrl(row.token) }}</code>
            <el-button link size="small" type="primary" @click="copy(shareUrl(row.token))">复制</el-button>
          </div>
          <div class="micro muted">token: {{ row.token }}</div>
        </template>
      </el-table-column>
      <el-table-column label="有效期" width="140">
        <template #default="{ row }">
          <span v-if="row.expiresAt">{{ formatTime(row.expiresAt) }}</span>
          <span v-else class="micro muted">长期有效</span>
        </template>
      </el-table-column>
      <el-table-column label="访问次数" width="90" prop="accessCount" />
      <el-table-column label="最近访问" width="150">
        <template #default="{ row }">
          <span v-if="row.lastAccessAt" class="micro">{{ formatTime(row.lastAccessAt) }}</span>
          <span v-else class="micro muted">从未访问</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.revokedAt" size="small" type="danger" effect="plain">已撤销</el-tag>
          <el-tag v-else-if="row.enabled" size="small" type="success" effect="plain">启用</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <template v-if="row.revokedAt">
            <span class="micro muted">终态，不可恢复</span>
          </template>
          <template v-else>
            <el-button
              link size="small"
              @click="toggleEnabled(row)"
            >{{ row.enabled ? '停用' : '启用' }}</el-button>
            <el-button link size="small" type="danger" @click="revoke(row)">撤销</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="生成访问凭证" width="420px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="有效期">
          <el-radio-group v-model="expiresMode">
            <el-radio-button value="never">长期有效</el-radio-button>
            <el-radio-button value="date">指定日期</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-if="expiresMode === 'date'"
            v-model="expiresAt"
            type="date"
            placeholder="选择到期日"
            style="width: 100%; margin-top: var(--space-xs)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="creating" @click="create">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { assistantsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { AccessCredential } from '@/api/assistants'

const props = defineProps<{ appId: string }>()

const list = ref<AccessCredential[]>([])
const loading = ref(false)
const createVisible = ref(false)
const creating = ref(false)
const expiresMode = ref<'never' | 'date'>('never')
const expiresAt = ref<Date | null>(null)

const formatTime = (iso: string) => dayjs(iso).format('YYYY-MM-DD HH:mm')

/**
 * 免登录公开链接的**真实路由形状**是 hash 路径 `/#/public/:token`
 * （`pages/conversation/route.ts`），不是查询串。
 * 拼成 `/#/public?token=xxx` 打开即 404 —— 那个形状在路由表里不存在，也没有任何页面读 `?token`。
 */
const shareUrl = (token: string) =>
  `${window.location.origin}/#/public/${token}`

const copy = async (text: string) => {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

const fetch = async () => {
  loading.value = true
  try {
    list.value = await assistantsApi.listCredentials(props.appId)
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  expiresMode.value = 'never'
  expiresAt.value = null
  createVisible.value = true
}

const create = async () => {
  creating.value = true
  try {
    await assistantsApi.createCredential(props.appId, {
      expiresAt:
        expiresMode.value === 'date' && expiresAt.value
          ? dayjs(expiresAt.value).endOf('day').toISOString()
          : null
    })
    ElMessage.success('凭证已生成')
    createVisible.value = false
    await fetch()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '生成失败')
  } finally {
    creating.value = false
  }
}

const toggleEnabled = async (row: AccessCredential) => {
  try {
    await assistantsApi.setCredentialEnabled(props.appId, row.id, !row.enabled)
    await fetch()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

const revoke = async (row: AccessCredential) => {
  // 取消是正常路径：then 的拒绝回调把它收成 false，不落成 unhandled rejection
  const confirmed = await ElMessageBox.confirm(
    '撤销后该链接立即失效，且无法重新启用。只能重新生成一个新凭证。确认撤销？',
    '确认撤销凭证',
    { type: 'warning', confirmButtonText: '撤销' }
  ).then(
    () => true,
    () => false
  )
  if (!confirmed) return
  try {
    await assistantsApi.revokeCredential(props.appId, row.id)
    ElMessage.success('已撤销，链接立即失效')
    await fetch()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '撤销失败')
  }
}

defineExpose({ fetch })
</script>

<style scoped>
.cred {
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
