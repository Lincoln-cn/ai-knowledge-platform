<template>
  <el-dialog
    v-model="visible"
    title="上传文档"
    width="560px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <el-upload
      drag
      multiple
      :auto-upload="false"
      :on-change="onChange"
      :on-remove="onRemove"
      :file-list="fileList"
      accept=".pdf,.docx,.doc,.md,.txt"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
      <template #tip>
        <div class="el-upload__tip micro muted">
          支持 PDF / Word / Markdown / 纯文本。扫描件 PDF 无文本层，会在分段阶段失败并给出原因。
        </div>
      </template>
    </el-upload>

    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="uploading" :disabled="!files.length" @click="submit">
        上传 {{ files.length ? `(${files.length})` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, type UploadFile, type UploadFiles } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { ingestionApi } from '@/api'
import { ApiError } from '@/api/types'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ uploaded: [] }>()

const visible = ref(false)
const uploading = ref(false)
const files = ref<File[]>([])
const fileList = ref<UploadFiles>([])

const open = () => {
  visible.value = true
}

const collectFiles = (list: UploadFiles) => {
  const result: File[] = []
  for (const item of list) {
    if (item.raw) result.push(item.raw)
  }
  return result
}

const onChange = (_file: UploadFile, list: UploadFiles) => {
  fileList.value = list
  files.value = collectFiles(list)
}

const onRemove = (_file: UploadFile, list: UploadFiles) => {
  fileList.value = list
  files.value = collectFiles(list)
}

const reset = () => {
  files.value = []
  fileList.value = []
}

const submit = async () => {
  if (!files.value.length) return
  uploading.value = true
  try {
    await ingestionApi.uploadDocuments(props.kbId, files.value)
    ElMessage.success(`已上传 ${files.value.length} 个文档，开始解析`)
    visible.value = false
    emit('uploaded')
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '上传失败，请稍后重试'
    ElMessage.error(message)
  } finally {
    uploading.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
/* 上传区：发丝线 + 文案，不用拖拽虚线框 */
:deep(.el-upload),
:deep(.el-upload-dragger) {
  width: 100%;
}

:deep(.el-upload-dragger) {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  background: var(--color-sunken);
  padding: var(--space-xl) var(--space-lg);
  transition:
    border-color var(--dur-micro) var(--ease-out),
    background var(--dur-micro) var(--ease-out);
}

:deep(.el-upload-dragger:hover) {
  border-color: var(--color-accent-line);
  background: var(--color-hover);
}

:deep(.el-icon--upload) {
  color: var(--color-faint);
  font-size: var(--text-xl);
  margin-bottom: var(--space-xs);
}

:deep(.el-upload__text) {
  color: var(--color-ink-2);
  font-size: var(--text-sm);
  line-height: var(--leading-body);
}

:deep(.el-upload__text em) {
  color: var(--color-accent);
  font-style: normal;
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
