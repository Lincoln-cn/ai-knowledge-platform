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

    <!-- ★ 建档必填三项（12 §5.1）：文种 / 责任部门 / 密级 —— 决定召回前缀与安全过滤，不能有默认值 -->
    <el-form ref="metaFormRef" :model="meta" :rules="metaRules" label-width="96px" class="meta-form">
      <el-form-item label="文种" prop="docType">
        <el-input v-model="meta.docType" maxlength="32" placeholder="例如：合同 / 制度 / 报销规定" />
      </el-form-item>
      <el-form-item label="责任部门" prop="department">
        <el-input v-model="meta.department" maxlength="32" placeholder="例如：法务部 / 采购部" />
      </el-form-item>
      <el-form-item label="密级" prop="classification">
        <el-select v-model="meta.classification" style="width: 220px" placeholder="选择密级">
          <el-option
            v-for="c in CLASSIFICATION_LEVEL_VALUES"
            :key="c"
            :label="labelOf(CLASSIFICATION_LEVEL_LABELS, c)"
            :value="c"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <div v-if="rejected.length" class="notice warn reject-list">
      <div><b>以下文件未通过校验：</b></div>
      <div v-for="r in rejected" :key="r.name" class="micro">{{ r.name }} —— {{ r.reason }}</div>
    </div>

    <!-- 后端可读原因原文（如 ceiling 超限）—— 不代写、不吞掉 hint -->
    <div v-if="submitError" class="notice err submit-error">
      <div><b>提交失败：</b>{{ submitError }}</div>
      <div v-if="submitHint" class="micro">{{ submitHint }}</div>
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
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadFile, type UploadFiles } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { ingestionApi } from '@/api'
import { ApiError } from '@/api/types'
import type { UploadPolicy, UploadReceipt, DocumentItem } from '@/api/ingestion'
import type { ClassificationLevel } from '@/api/types'
import {
  CLASSIFICATION_LEVEL_LABELS,
  CLASSIFICATION_LEVEL_VALUES,
  labelOf
} from '@/constants/labels'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ uploaded: [] }>()

const visible = ref(false)
const uploading = ref(false)
const files = ref<File[]>([])
const fileList = ref<UploadFiles>([])
const policy = ref<UploadPolicy | null>(null)
const rejected = ref<Array<{ name: string; reason: string }>>([])
const submitError = ref('')
const submitHint = ref('')

/** 建档必填三项（12 §5.1） */
const meta = reactive({
  docType: '',
  department: '',
  classification: '' as ClassificationLevel | ''
})
const metaFormRef = ref<FormInstance>()
const metaRules: FormRules = {
  docType: [{ required: true, message: '请选择文种', trigger: 'blur' }],
  department: [{ required: true, message: '请填写责任部门', trigger: 'blur' }],
  classification: [{ required: true, message: '请选择密级', trigger: 'change' }]
}

const acceptAttr = computed(() =>
  policy.value?.acceptedExtensions.length ? policy.value.acceptedExtensions.join(',') : '.pdf,.docx,.doc,.md,.txt'
)

const open = async () => {
  visible.value = true
  rejected.value = []
  submitError.value = ''
  submitHint.value = ''
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
  meta.docType = ''
  meta.department = ''
  meta.classification = ''
  submitError.value = ''
  submitHint.value = ''
  metaFormRef.value?.clearValidate()
}

const submit = async () => {
  if (!files.value.length) return
  // ★ 建档三项必填：缺任一不让提交（前端拦一道；后端也拦）
  const metaValid = await metaFormRef.value?.validate().catch(() => false)
  if (!metaValid) return

  const bad = validate(files.value)
  if (bad.length) {
    rejected.value = bad
    return
  }
  uploading.value = true
  submitError.value = ''
  submitHint.value = ''
  try {
    // ★ 走 multipart 上传（契约 uploadDocuments）：带上文件字节 ⇒ 后端**建档即解析**，
    //   解析出的 Markdown 进 parsedContent、失败态带可读原因。
    //   （旧的 stageUploads / importDocuments 形态只登记元数据、不带字节，解析无从发生。）
    const raw = (await ingestionApi.uploadDocuments(props.kbId, files.value, {
      docType: meta.docType.trim(),
      department: meta.department.trim(),
      // ★ metaValid 已保证三项必填（表单 rules），此处分类必非空串 —— 类型上仍是 '' | Level
      classification: meta.classification as ClassificationLevel
    })) as unknown as UploadReceipt | DocumentItem[]
    // 真栈回 DocumentItem[]；mock 若回 UploadReceipt 也收 —— 两种都归一
    const receipt: UploadReceipt = Array.isArray(raw) ? { accepted: raw, rejected: [] } : raw
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
    // ★ 后端可读原因原文展示（如 ceiling 超限：「文档密级（机密）高于知识库的密级上限（秘密）…」）
    const message = error instanceof ApiError ? error.message : '上传失败，请稍后重试'
    submitError.value = message
    submitHint.value = error instanceof ApiError ? error.hint || '' : ''
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

.meta-form {
  margin-top: var(--space-md);
}

.reject-list {
  margin-top: var(--space-sm);
}

.submit-error {
  margin-top: var(--space-sm);
}
</style>
