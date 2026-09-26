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
      :accept="acceptAttr"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
      <template #tip>
        <div class="el-upload__tip micro muted">
          <template v-if="policy">
            支持 {{ policy.acceptedExtensions.join(' / ') }} · 单个 ≤ {{ policy.maxFileSizeMB }}MB · 一次最多
            {{ policy.maxFileCount }} 个。扫描件 PDF 无文本层，会在分段阶段失败并给出原因。
          </template>
          <template v-else>
            支持 PDF / Word / Markdown / 纯文本。扫描件 PDF 无文本层，会在分段阶段失败并给出原因。
          </template>
        </div>
      </template>
    </el-upload>

    <div v-if="rejected.length" class="notice warn reject-list">
      <div><b>以下文件未通过校验：</b></div>
      <div v-for="r in rejected" :key="r.name" class="micro">{{ r.name }} —— {{ r.reason }}</div>
    </div>

    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="uploading" :disabled="!files.length" @click="submit">
        上传 {{ files.length ? `(${files.length})` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type UploadFile, type UploadFiles } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { ingestionApi } from '@/api'
import { ApiError } from '@/api/types'
import type { UploadPolicy } from '@/api/ingestion'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ uploaded: [] }>()

const visible = ref(false)
const uploading = ref(false)
const files = ref<File[]>([])
const fileList = ref<UploadFiles>([])
const policy = ref<UploadPolicy | null>(null)
const rejected = ref<Array<{ name: string; reason: string }>>([])

const acceptAttr = computed(() =>
  policy.value?.acceptedExtensions.length ? policy.value.acceptedExtensions.join(',') : '.pdf,.docx,.doc,.md,.txt'
)

const open = async () => {
  visible.value = true
  rejected.value = []
  try {
    policy.value = await ingestionApi.getUploadPolicy(props.kbId)
  } catch {
    policy.value = null
  }
}

const collectFiles = (list: UploadFiles) => {
  const result: File[] = []
  for (const item of list) {
    if (item.raw) result.push(item.raw)
  }
  return result
}

/** P1 上传大小/数量校验：在提交前就拒掉，不等后端 */
const validate = (list: File[]): Array<{ name: string; reason: string }> => {
  const bad: Array<{ name: string; reason: string }> = []
  const p = policy.value
  if (!p) return bad
  if (list.length > p.maxFileCount) {
    bad.push({ name: `共 ${list.length} 个文件`, reason: `一次最多 ${p.maxFileCount} 个，请分批上传` })
  }
  for (const f of list) {
    const ext = f.name.includes('.') ? f.name.slice(f.name.lastIndexOf('.')).toLowerCase() : ''
    if (p.acceptedExtensions.length && ext && !p.acceptedExtensions.includes(ext)) {
      bad.push({ name: f.name, reason: `不支持的类型「${ext}」` })
      continue
    }
    if (f.size > p.maxFileSizeMB * 1024 * 1024) {
      bad.push({ name: f.name, reason: `超过 ${p.maxFileSizeMB}MB 上限` })
    }
  }
  return bad
}

const onChange = (_file: UploadFile, list: UploadFiles) => {
  fileList.value = list
  files.value = collectFiles(list)
  rejected.value = validate(files.value)
}

const onRemove = (_file: UploadFile, list: UploadFiles) => {
  fileList.value = list
  files.value = collectFiles(list)
  rejected.value = validate(files.value)
}

const reset = () => {
  files.value = []
  fileList.value = []
  rejected.value = []
  policy.value = null
}

const submit = async () => {
  if (!files.value.length) return
  const bad = validate(files.value)
  if (bad.length) {
    rejected.value = bad
    return
  }
  uploading.value = true
  try {
    // 走暂存 + 导入，拿到部分失败回执
    const staged = await ingestionApi.stageUploads(props.kbId, files.value)
    const receipt = await ingestionApi.importDocuments(props.kbId, { uploadIds: staged.uploadIds })
    if (receipt.rejected.length) {
      rejected.value = receipt.rejected
      ElMessage.warning(`成功 ${receipt.accepted.length} 个，失败 ${receipt.rejected.length} 个`)
    } else {
      ElMessage.success(`已上传 ${receipt.accepted.length} 个文档，开始解析`)
      visible.value = false
      emit('uploaded')
    }
    if (receipt.accepted.length) emit('uploaded')
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

.reject-list {
  margin-top: var(--space-sm);
}
</style>
