<template>
  <el-dialog
    v-model="visible"
    title="创建知识库"
    width="860px"
    :close-on-click-modal="false"
    :show-close="step === 0 || step === 3"
    @closed="reset"
  >
    <el-steps :active="step" align-center class="steps" finish-status="success">
      <el-step title="基本信息" />
      <el-step title="上传文档" />
      <el-step title="分段设置" />
      <el-step title="导入结果" />
    </el-steps>

    <!-- ── 第一步：基本信息 ── -->
    <div v-show="step === 0" class="step-body">
      <el-form ref="baseFormRef" :model="baseForm" :rules="baseRules" label-width="96px" class="base-form">
        <el-form-item label="名称" prop="name">
          <el-input v-model="baseForm.name" maxlength="30" show-word-limit placeholder="例如：合同管理制度" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="baseForm.category" style="width: 220px" placeholder="选择分类">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="图标">
          <div class="icon-picks">
            <button
              v-for="ic in ICONS"
              :key="ic"
              type="button"
              class="icon-pick"
              :class="{ on: baseForm.icon === ic }"
              @click="baseForm.icon = ic"
            >{{ ic }}</button>
          </div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="baseForm.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="导入类型" prop="importType">
          <el-radio-group v-model="baseForm.importType">
            <el-radio-button value="local">本地文档</el-radio-button>
            <el-radio-button value="online">在线抓取</el-radio-button>
          </el-radio-group>
          <div class="hint micro">创建后不可更改（BR-03-01）</div>
        </el-form-item>
        <el-form-item label="向量模型" prop="embeddingModelId">
          <el-select v-model="baseForm.embeddingModelId" style="width: 280px" placeholder="选择向量模型">
            <el-option v-for="m in embeddingModels" :key="m.id" :label="m.name" :value="m.id">
              <span>{{ m.name }}</span>
              <span class="opt-hint">{{ m.dimension }} 维</span>
            </el-option>
          </el-select>
          <div class="hint micro">决定分段长度上限与向量维度，越早选定越好</div>
        </el-form-item>
      </el-form>
    </div>

    <!-- ── 第二步：上传文档 ── -->
    <div v-show="step === 1" class="step-body">
      <el-tabs v-model="uploadTab">
        <el-tab-pane label="本地文档" name="local">
          <el-upload
            drag
            multiple
            :auto-upload="false"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            :file-list="fileList"
            accept=".pdf,.docx,.doc,.md,.txt"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip micro muted">
                支持 PDF / Word / Markdown / 纯文本。选好文件后点「下一步」进入分段设置。
              </div>
            </template>
          </el-upload>
        </el-tab-pane>
        <!-- 在线抓取属 V1.2，整项隐藏而不是留一个点了没反应的入口 -->
        <el-tab-pane v-if="false" label="在线抓取" name="online">
          <div class="notice warn">在线抓取计划在 V1.2 提供</div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- ── 第三步：分段设置（核心） ── -->
    <div v-show="step === 2" class="step-body split-step">
      <div class="split-left">
        <h4 class="sec-title">分段规则</h4>
        <el-radio-group v-model="segForm.strategy" class="strategy-group">
          <el-radio-button value="auto">智能分段</el-radio-button>
          <el-radio-button value="custom">自定义</el-radio-button>
        </el-radio-group>
        <div class="hint micro">
          {{ segForm.strategy === 'auto' ? '自动按段落与语义边界切分，适合大多数文档' : '按固定长度切分，适合结构化文档' }}
        </div>

        <div class="slider-row">
          <span class="slider-label">分段长度</span>
          <el-slider v-model="segForm.segmentLength" :min="100" :max="2000" :step="50" show-input :show-input-controls="false" />
        </div>
        <div class="slider-row">
          <span class="slider-label">重叠长度</span>
          <el-slider v-model="segForm.overlap" :min="0" :max="500" :step="10" show-input :show-input-controls="false" />
        </div>

        <!-- 本方案新增：左侧实时提示向量模型上限 -->
        <div class="notice warn limit-notice">
          ⚠ 当前向量模型上限约 <b class="num">{{ modelLimit }}</b> 字<br />
          超出部分将被截断，影响召回
        </div>

        <el-button type="primary" class="preview-btn" :loading="previewing" @click="generatePreview">
          生成预览
        </el-button>
      </div>

      <div class="split-right">
        <div class="preview-head">
          <span class="sec-title">
            切分预览
            <template v-if="preview">· 共 {{ preview.segmentCount }} 段</template>
          </span>
          <span class="micro muted">可编辑</span>
        </div>

        <div v-if="!preview" class="preview-empty micro muted">
          点左侧「生成预览」查看切分结果。超长分段会标红并给出建议。
        </div>

        <div v-else class="preview-list scroll-y">
          <div
            v-for="seg in preview.segments"
            :key="seg.ordinal"
            class="preview-item"
            :class="{ oversized: seg.oversized }"
          >
            <div class="pi-head">
              <span class="micro muted">#{{ seg.ordinal }} · {{ seg.charCount }} 字</span>
              <span v-if="seg.oversized" class="pi-warn">⚠ 超长</span>
            </div>
            <!-- 可编辑 -->
            <el-input
              v-if="editingOrdinal === seg.ordinal"
              v-model="editingContent"
              type="textarea"
              :rows="4"
              @blur="savePreviewEdit(seg)"
              @keyup.enter="savePreviewEdit(seg)"
            />
            <p v-else class="pi-content" @click="startPreviewEdit(seg)">{{ seg.content }}</p>
            <div v-if="seg.oversized" class="pi-advice">
              该段将被向量模型截断。建议：按段落切分为 2 段，或调小分段长度
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 第四步：导入结果 ── -->
    <div v-show="step === 3" class="step-body">
      <div class="notice info">
        知识库「{{ createdKb?.name }}」已创建，文档正在解析与切分。可以离开本向导，进度在文档列表继续可见。
      </div>
      <div class="result-list">
        <div v-for="doc in resultDocs" :key="doc.id" class="result-item">
          <span class="ellipsis">{{ doc.name }}</span>
          <span class="grow" />
          <span class="status" :class="STATUS_CLASS[doc.status as DocumentStatus]">
            <el-icon v-if="isRunning(doc.status as DocumentStatus)" class="is-loading"><Loading /></el-icon>
            <i v-else class="dot" :class="STATUS_DOT[doc.status as DocumentStatus]" />
            {{ doc.status }}
            <template v-if="doc.status === '向量化中'"> {{ doc.processedSegments }}/{{ doc.segmentCount }}</template>
          </span>
        </div>
      </div>
    </div>

    <template #footer>
      <template v-if="step < 3">
        <el-button size="small" @click="visible = false">取消</el-button>
        <el-button v-if="step > 0" size="small" @click="step -= 1">上一步</el-button>
        <el-button
          v-if="step < 2"
          size="small"
          type="primary"
          :loading="submitting"
          @click="next"
        >下一步</el-button>
        <el-button
          v-else
          size="small"
          type="primary"
          :loading="submitting"
          @click="startImport"
        >开始导入</el-button>
      </template>
      <template v-else>
        <el-button size="small" type="primary" @click="finish">完成</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import {
  ElMessage,
  type FormInstance,
  type FormRules,
  type UploadFile,
  type UploadFiles
} from 'element-plus'
import { Loading, UploadFilled } from '@element-plus/icons-vue'
import { ingestionApi, knowledgeApi, modelsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { ImportType, KnowledgeBase, KnowledgeCategory } from '@/api/knowledge'
import type { ModelInfo } from '@/api/models'
import type { DocumentItem, DocumentStatus, PreviewSegmentationResult } from '@/api/ingestion'

const emit = defineEmits<{ created: [kb: KnowledgeBase] }>()

const ICONS = ['📘', '📗', '📙', '📕', '📒', '📂', '💼', '🏛']

const STATUS_CLASS: Record<DocumentStatus, string> = {
  待解析: 'st-idle',
  分段中: 'st-loading',
  待向量化: 'st-warn',
  向量化中: 'st-loading',
  成功: 'st-ok',
  分段失败: 'st-err',
  向量化失败: 'st-err'
}
const STATUS_DOT: Record<DocumentStatus, string> = {
  待解析: '',
  分段中: 'warn',
  待向量化: 'warn',
  向量化中: 'warn',
  成功: 'ok',
  分段失败: 'err',
  向量化失败: 'err'
}
const isRunning = (s: DocumentStatus) => s === '待解析' || s === '分段中' || s === '向量化中'

const visible = ref(false)
const step = ref(0)
const submitting = ref(false)
const previewing = ref(false)
const uploadTab = ref('local')

const baseFormRef = ref<FormInstance>()
const categories = ref<KnowledgeCategory[]>([])
const embeddingModels = ref<ModelInfo[]>([])

const baseForm = reactive({
  name: '',
  category: '',
  icon: '📘',
  description: '',
  importType: 'local' as ImportType,
  embeddingModelId: ''
})

const baseRules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
  importType: [{ required: true, message: '请选择导入类型', trigger: 'change' }],
  embeddingModelId: [{ required: true, message: '请选择向量模型', trigger: 'change' }]
}

const files = ref<File[]>([])
const fileList = ref<UploadFiles>([])

const segForm = reactive({
  strategy: 'auto' as 'auto' | 'custom',
  segmentLength: 500,
  overlap: 60
})

const preview = ref<PreviewSegmentationResult | null>(null)
const modelLimit = ref(1200)
const editingOrdinal = ref<number | null>(null)
const editingContent = ref('')

const createdKb = ref<KnowledgeBase | null>(null)
const resultDocs = ref<DocumentItem[]>([])

const open = async () => {
  visible.value = true
  step.value = 0
  categories.value = await knowledgeApi.listKnowledgeCategories()
  const models = await modelsApi.listModels({ kind: 'embedding', size: 50 })
  embeddingModels.value = models.list.filter((m) => m.status === '已加载' || m.status === '未加载')
}

const reset = () => {
  step.value = 0
  baseForm.name = ''
  baseForm.category = ''
  baseForm.icon = '📘'
  baseForm.description = ''
  baseForm.importType = 'local'
  baseForm.embeddingModelId = ''
  files.value = []
  fileList.value = []
  segForm.strategy = 'auto'
  segForm.segmentLength = 500
  segForm.overlap = 60
  preview.value = null
  editingOrdinal.value = null
  createdKb.value = null
  resultDocs.value = []
  baseFormRef.value?.clearValidate()
}

const collectFiles = (list: UploadFiles) => {
  const result: File[] = []
  for (const item of list) {
    if (item.raw) result.push(item.raw)
  }
  return result
}

const onFileChange = (_f: UploadFile, list: UploadFiles) => {
  fileList.value = list
  files.value = collectFiles(list)
}

const onFileRemove = (_f: UploadFile, list: UploadFiles) => {
  fileList.value = list
  files.value = collectFiles(list)
}

const next = async () => {
  if (step.value === 0) {
    const valid = await baseFormRef.value?.validate().catch(() => false)
    if (!valid) return
    step.value = 1
    return
  }
  if (step.value === 1) {
    if (!files.value.length) {
      ElMessage.warning('请至少选择一个文档')
      return
    }
    step.value = 2
  }
}

const generatePreview = async () => {
  previewing.value = true
  try {
    // 上传前预览：documentId 传 null。契约里没有 content 字段，mock 用内置语料演示切分
    preview.value = await ingestionApi.previewSegmentation({
      documentId: null,
      segmentLength: segForm.segmentLength,
      overlap: segForm.overlap,
      strategy: segForm.strategy
    })
    modelLimit.value = preview.value.modelLimit
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '生成预览失败')
  } finally {
    previewing.value = false
  }
}

const startPreviewEdit = (seg: { ordinal: number; content: string }) => {
  editingOrdinal.value = seg.ordinal
  editingContent.value = seg.content
}

const savePreviewEdit = (seg: { ordinal: number; content: string; charCount: number; oversized: boolean }) => {
  if (editingOrdinal.value !== seg.ordinal) return
  seg.content = editingContent.value
  seg.charCount = editingContent.value.length
  seg.oversized = seg.charCount > modelLimit.value
  editingOrdinal.value = null
}

const startImport = async () => {
  if (!preview.value) {
    ElMessage.warning('请先生成预览，确认切分结果后再导入')
    return
  }
  submitting.value = true
  try {
    // 1) 建库
    createdKb.value = await knowledgeApi.createKnowledgeBase({
      name: baseForm.name,
      category: baseForm.category,
      description: baseForm.description,
      icon: baseForm.icon,
      importType: baseForm.importType,
      embeddingModelId: baseForm.embeddingModelId
    })

    // 2) 上传文档（触发解析切分）
    resultDocs.value = await ingestionApi.uploadDocuments(createdKb.value.id, files.value)

    step.value = 3
    emit('created', createdKb.value)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '导入失败')
  } finally {
    submitting.value = false
  }
}

const finish = () => {
  visible.value = false
}

defineExpose({ open })
</script>

<style scoped>
.steps {
  margin-bottom: var(--space-lg);
}

.step-body {
  min-height: 320px;
}

.base-form {
  max-width: 520px;
  margin: 0 auto;
}

.hint {
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.opt-hint {
  float: right;
  color: var(--color-muted);
  font-size: var(--text-2xs);
}

.icon-picks {
  display: flex;
  gap: var(--space-2xs);
  flex-wrap: wrap;
}

.icon-pick {
  width: var(--space-xl);
  height: var(--space-xl);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
  font-family: inherit;
  font-size: var(--text-md);
  line-height: 1;
  cursor: pointer;
  transition:
    border-color var(--dur-micro) var(--ease-out),
    background var(--dur-micro) var(--ease-out);
}

.icon-pick:hover {
  border-color: var(--color-rule-strong);
  background: var(--color-hover);
}

.icon-pick.on {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
}

.split-step {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: var(--space-md);
  align-items: start;
}

.split-left {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
}

.sec-title {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 500;
  line-height: var(--leading-tight);
  color: var(--color-ink);
}

.strategy-group {
  width: 100%;
}

.strategy-group :deep(.el-radio-button) {
  flex: 1;
}

.strategy-group :deep(.el-radio-button__inner) {
  width: 100%;
}

.slider-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.slider-label {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
  color: var(--color-muted);
}

.limit-notice {
  margin: 0;
}

.preview-btn {
  width: 100%;
}

.split-right {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  display: flex;
  flex-direction: column;
  min-height: 360px;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  border-bottom: 1px solid var(--color-rule-2);
}

.preview-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-xl) var(--space-md);
  text-align: center;
}

.preview-list {
  flex: 1;
  max-height: 320px;
  padding: var(--space-xs);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.preview-item {
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-sunken);
  border-radius: var(--radius);
  border: 1px solid transparent;
  transition: border-color var(--dur-micro) var(--ease-out);
}

.preview-item.oversized {
  background: var(--color-warn-soft);
  border-color: color-mix(in oklch, var(--color-warn) 40%, var(--color-rule));
}

.pi-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-2xs);
}

.pi-head .num {
  font-size: var(--text-2xs);
  font-variant-numeric: tabular-nums;
}

.pi-warn {
  font-size: var(--text-2xs);
  color: var(--color-warn);
  font-weight: 500;
  line-height: var(--leading-tight);
}

.pi-content {
  margin: 0;
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
  line-height: var(--leading-body);
  cursor: pointer;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.pi-advice {
  margin-top: var(--space-2xs);
  font-size: var(--text-2xs);
  color: var(--color-warn);
  line-height: var(--leading-body);
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
}

.result-item {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-md);
  background: var(--color-sunken);
  border-radius: var(--radius);
  font-size: var(--text-sm);
  line-height: var(--leading-tight);
}

.status {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
}

.st-idle { color: var(--color-muted); }
.st-loading { color: var(--color-accent); }
.st-warn { color: var(--color-warn); }
.st-ok { color: var(--color-ok); }
.st-err { color: var(--color-danger); }

/* 上传区：发丝线 + 文案，不用拖拽虚线框 */
.step-body :deep(.el-upload),
.step-body :deep(.el-upload-dragger) {
  width: 100%;
}

.step-body :deep(.el-upload-dragger) {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  background: var(--color-sunken);
  padding: var(--space-xl) var(--space-lg);
  transition:
    border-color var(--dur-micro) var(--ease-out),
    background var(--dur-micro) var(--ease-out);
}

.step-body :deep(.el-upload-dragger:hover) {
  border-color: var(--color-accent-line);
  background: var(--color-hover);
}

.step-body :deep(.el-icon--upload) {
  color: var(--color-faint);
  font-size: var(--text-xl);
  margin-bottom: var(--space-xs);
}

.step-body :deep(.el-upload__text) {
  color: var(--color-ink-2);
  font-size: var(--text-sm);
  line-height: var(--leading-body);
}

.step-body :deep(.el-upload__text em) {
  color: var(--color-accent);
  font-style: normal;
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
