<template>
  <el-dialog v-model="visible" title="分享" width="440px" append-to-body>
    <p class="micro muted">
      生成公开链接后，外部访客无需登录即可提问。凭证可随时撤销，撤销后链接立即失效。
    </p>

    <div v-if="publicUrl" class="url-row">
      <el-input v-model="publicUrl" readonly />
      <el-button type="primary" @click="copy">复制</el-button>
    </div>

    <div v-else class="actions">
      <el-button type="primary" :loading="creating" @click="create">生成公开链接</el-button>
      <span class="micro muted">当前为 mock 环境，将使用演示凭证</span>
    </div>

    <div v-if="messagePreview" class="preview">
      <div class="micro muted">同时复制这段内容？</div>
      <div class="preview-text micro">{{ messagePreview }}</div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps<{
  /** 免登录链接中的 token；有值则直接拼 URL */
  token?: string
  /** 随分享附带的消息正文（消息级分享） */
  messageText?: string
}>()

const visible = ref(false)
const creating = ref(false)
const publicUrl = ref('')
const messagePreview = computed(() => props.messageText || '')

const open = () => {
  publicUrl.value = props.token ? buildUrl(props.token) : ''
  visible.value = true
}

function buildUrl(token: string) {
  const { origin, pathname } = window.location
  // hash 路由：/#/public/:token
  return `${origin}${pathname}#/public/${token}`
}

const create = async () => {
  creating.value = true
  try {
    // 演示凭证。真实环境应走 assistantsApi.createCredential
    publicUrl.value = buildUrl('pub_ok_demo')
    ElMessage.success('已生成演示公开链接')
  } finally {
    creating.value = false
  }
}

const copy = async () => {
  const payload = [publicUrl.value, messagePreview.value].filter(Boolean).join('\n\n')
  try {
    await navigator.clipboard.writeText(payload)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

defineExpose({ open })
</script>

<style scoped>
.url-row {
  display: flex;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
}

.actions {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.preview {
  margin-top: var(--space-md);
  padding: var(--space-sm);
  background: var(--color-sunken);
  border-radius: var(--radius-sm);
}

.preview-text {
  margin-top: var(--space-xs);
  line-height: var(--leading-body);
  max-height: 100px;
  overflow: auto;
  white-space: pre-wrap;
}
</style>
