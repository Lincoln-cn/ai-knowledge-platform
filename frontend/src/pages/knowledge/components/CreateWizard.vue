<template>
  <el-dialog
    v-model="visible"
    title="创建知识库"
    width="860px"
    :close-on-click-modal="false"
    :show-close="step === 0 || step === 3"
    @closed="onClosed"
  >
    <el-steps :active="step" align-center class="steps" finish-status="success">
      <el-step title="基本信息" />
      <el-step title="上传文档" />
      <el-step title="分段设置" />
      <el-step title="导入结果" />
    </el-steps>

    <!-- ── 第一步：基本信息 ── -->
    <div v-show="step === 0" class="step-body">
      <!-- 分类 / 密级上限取自基础数据字典：空下拉的原因（失败 / 没配）必须写出来 -->
      <div v-if="catalogHint" class="notice warn">{{ catalogHint }}</div>
      <el-form ref="baseFormRef" :model="baseForm" :rules="baseRules" label-width="96px" class="base-form">
        <el-form-item label="名称" prop="name">
          <el-input v-model="baseForm.name" maxlength="30" show-word-limit placeholder="例如：合同管理制度" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <!-- ★ value 用 code、显示用 label（08 §十）：管理员改展示名不影响已建库的取值 -->
          <el-select v-model="baseForm.category" style="width: 220px" placeholder="选择分类">
            <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.code" />
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
            <el-radio-button value="LOCAL">本地文档</el-radio-button>
            <el-radio-button value="ONLINE">在线抓取</el-radio-button>
          </el-radio-group>
          <div class="hint micro">创建后不可更改（BR-03-01）</div>
        </el-form-item>
        <el-form-item label="密级上限" prop="maxClassification">
          <!-- ★ 与建档的「密级」同一个字典：两边各写一份文案，迟早出现"上限叫机密、文档密级叫绝密" -->
          <el-select v-model="baseForm.maxClassification" style="width: 220px" placeholder="选择密级上限">
            <el-option v-for="c in classifications" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
          <div class="hint micro">这个库允许装入的最高密级（13 §6）—— 文档密级超过它会被拒绝入库</div>
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
            :accept="acceptAttr"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip micro muted">
                <template v-if="uploadPolicy">
                  支持 {{ uploadPolicy.acceptedExtensions.join(' / ') }} · 单个 ≤ {{ uploadPolicy.maxFileSizeMB }}MB ·
                  一次最多 {{ uploadPolicy.maxFileCount }} 个。选好文件后点「下一步」进入分段设置。
                </template>
                <template v-else>
                  支持 PDF / Word / Markdown / 纯文本。选好文件后点「下一步」进入分段设置。
                </template>
              </div>
            </template>
          </el-upload>
          <div v-if="fileRejects.length" class="notice warn reject-list">
            <div><b>以下文件未通过校验：</b></div>
            <div v-for="r in fileRejects" :key="r.name" class="micro">{{ r.name }} —— {{ r.reason }}</div>
          </div>
          <div v-if="stageError" class="notice err" style="margin-top: var(--space-sm)">
            <div><b>暂存上传失败：</b>{{ stageError }}</div>
            <div class="micro">可以直接点「下一步」重试 —— 知识库是幂等创建的，不会重复建库。</div>
          </div>
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
          {{ segForm.strategy === 'AUTO' ? '自动按段落与语义边界切分，适合大多数文档' : '按固定长度切分，适合结构化文档' }}
        </div>

        <div class="slider-row">
          <span class="slider-label">分段长度</span>
          <el-slider
            v-model="segForm.segmentLength"
            :min="100"
            :max="segmentLengthMax"
            :step="50"
            show-input
            :show-input-controls="false"
          />
        </div>
        <div class="slider-row">
          <span class="slider-label">重叠长度</span>
          <el-slider v-model="segForm.overlap" :min="0" :max="500" :step="10" show-input :show-input-controls="false" />
        </div>

        <!-- 本方案新增：左侧实时提示向量模型上限 -->
        <div class="notice warn limit-notice">
          ⚠ 当前向量模型上限约 <b class="num">{{ modelLimit }}</b> 字<br />
          超过上限的分段会被<b>拒绝</b>（不会静默截断），请调小分段长度或换长上下文模型
        </div>

        <!-- ★ 建档必填三项（12 §5.1）：文种 / 责任部门 / 密级 —— 批级，一次导入同一套标签
             ★ 文种与密级取自**基础数据字典**（08 §十）：值必须收敛（它们会进召回前缀、要参与过滤），
               所以这两个下拉只能选，不能现场新增 —— 要新值由管理员在「基础数据」里加。
             ⚠️ 部门的数据源应当是「组织机构树」（07 §十），不是字典项 —— 那一条还没做，仍用常量。 -->
        <div v-if="docMetaHint" class="notice warn">{{ docMetaHint }}</div>
        <el-form ref="docMetaFormRef" :model="docMeta" :rules="docMetaRules" label-width="92px" class="doc-meta-form">
          <el-form-item label="文种" prop="docType">
            <el-select
              v-model="docMeta.docType"
              filterable
              default-first-option
              style="width: 100%"
              placeholder="选择文种"
            >
              <el-option v-for="d in docTypes" :key="d.code" :label="d.label" :value="d.code" />
            </el-select>
          </el-form-item>
          <!-- ★ 责任部门改成**组织树选择**（T300 / #37）：存 `org_id`，不再是自由字符串。
               为什么不能自由输入：展示名可变、可重名，而它要进召回前缀（`07 §十一`）——
               重名会让"按名字比较"的地方把两个部门混成一个（一个部门看到另一个部门的文档，且不报错）。 -->
          <el-form-item label="责任部门" prop="orgId">
            <el-tree-select
              v-model="docMeta.orgId"
              :data="orgTree"
              node-key="id"
              :props="{ label: 'name', children: 'children' }"
              check-strictly
              :render-after-expand="false"
              default-expand-all
              style="width: 100%"
              placeholder="从组织树选责任部门"
            />
          </el-form-item>
          <el-form-item label="密级" prop="classification">
            <el-select v-model="docMeta.classification" style="width: 100%" placeholder="选择密级">
              <el-option v-for="c in classifications" :key="c.code" :label="c.label" :value="c.code" />
            </el-select>
          </el-form-item>
        </el-form>

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
          <span class="micro muted">可编辑 · 导入时按此计划切分</span>
        </div>
        <!-- ★ 多文件时预览只展示**第一个**文档：不说清的话，用户会以为这一份预览涵盖了全部文件 -->
        <div v-if="preview && files.length > 1" class="micro muted">
          预览展示第一个文档（{{ files[0]?.name }}）；其余 {{ files.length - 1 }} 个按相同参数切分
        </div>

        <!--
          ★ 噪声剥离的行数（T249.2，12 §2.4）：页眉/页脚/页码/目录会被剔掉再分块。
            必须显示 —— 不显示的话「为什么这份文档少了一页内容」没人能解释（删掉的东西要看得见）。
            只在 >0 时出现：文本型文档通常是 0，不该挂一条恒为 0 的噪声。
        -->
        <div v-if="preview && (preview.removedNoiseLines ?? 0) > 0" class="micro muted noise-note">
          已剔除 <b class="num">{{ preview.removedNoiseLines }}</b> 行页眉 / 页脚 / 页码 / 目录
          —— 它们在每个分段里重复出现，会稀释召回
        </div>

        <div v-if="previewError" class="notice err preview-error">
          <div><b>生成预览失败：</b>{{ previewError }}</div>
          <el-button size="small" style="margin-top: var(--space-xs)" @click="generatePreview">重试</el-button>
        </div>

        <!-- 等解析落地时的进行中说明（T249.3）：扫描件的 OCR 要跑十几秒到一分多钟，
             ★ 说清"在做什么 + 走到哪"，而不是让用户对着不动的界面猜 -->
        <div v-else-if="previewWait" class="micro muted preview-wait">{{ previewWait }}</div>

        <div v-else-if="!preview" class="preview-empty micro muted">
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
              <span class="micro muted">#{{ seg.ordinal + 1 }} · {{ seg.charCount }} 字</span>
              <span v-if="seg.oversized" class="pi-warn">⚠ 超长</span>
              <span v-if="editedOrdinals.has(seg.ordinal)" class="pi-edited">已编辑</span>
            </div>
            <!-- 可编辑：改动写入 segmentationPlan，导入时被真正采纳 -->
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
              该段超过向量模型上限，<b>入库会被拒绝</b>（不会静默截断）。建议：调小分段长度，或按段落拆分
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 第四步：导入结果 ── -->
    <div v-show="step === 3" class="step-body">
      <div v-if="importError" class="notice err">
        <div><b>导入失败：</b>{{ importError }}</div>
        <div class="micro">知识库已创建（幂等），可以点「重试导入」继续，不会重复建库。</div>
      </div>
      <div v-else class="notice info">
        知识库「{{ createdKb?.name }}」已创建，文档正在解析与切分。可以离开本向导，进度在文档列表继续可见。
      </div>
      <div class="result-list">
        <div v-for="doc in resultDocs" :key="doc.id" class="result-item">
          <span class="ellipsis">{{ doc.name }}</span>
          <span class="grow" />
          <span class="status" :class="STATUS_CLASS[doc.status as DocumentStatus]">
            <el-icon v-if="isRunning(doc.status as DocumentStatus)" class="is-loading"><Loading /></el-icon>
            <i v-else class="dot" :class="STATUS_DOT[doc.status as DocumentStatus]" />
            {{ labelOf(DOCUMENT_STATUS_LABELS, doc.status) }}
            <template v-if="doc.status === 'EMBEDDING'"> {{ doc.processedSegments }}/{{ doc.segmentCount }}</template>
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
        <el-button v-if="importError" size="small" type="primary" :loading="submitting" @click="startImport">
          重试导入
        </el-button>
        <el-button size="small" type="primary" @click="finish">完成</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  ElMessage,
  type FormInstance,
  type FormRules,
  type UploadFile,
  type UploadFiles
} from 'element-plus'
import { Loading, UploadFilled } from '@element-plus/icons-vue'
import { ingestionApi, knowledgeApi, modelsApi, orgsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { ClassificationLevel } from '@/api/types'
import type { ImportType, KnowledgeBase } from '@/api/knowledge'
import type { OrgNodeView } from '@/api/orgs'
import type { ModelInfo } from '@/api/models'
import type {
  DocumentItem,
  DocumentStatus,
  PreviewSegmentationResult,
  UploadPolicy
} from '@/api/ingestion'
import { DOCUMENT_STATUS_LABELS, labelOf } from '@/constants/labels'
import { useDictionary } from '@/composables/useDictionary'
import { isUsableEmbeddingModel } from '@/lib/modelReadiness'

const emit = defineEmits<{ created: [kb: KnowledgeBase] }>()

const ICONS = ['📘', '📗', '📙', '📕', '📒', '📂', '💼', '🏛']

const STATUS_CLASS: Record<DocumentStatus, string> = {
  PENDING_PARSE: 'st-idle',
  SEGMENTING: 'st-loading',
  PENDING_EMBEDDING: 'st-warn',
  EMBEDDING: 'st-loading',
  SUCCEEDED: 'st-ok',
  SEGMENT_FAILED: 'st-err',
  EMBEDDING_FAILED: 'st-err'
}
const STATUS_DOT: Record<DocumentStatus, string> = {
  PENDING_PARSE: '',
  SEGMENTING: 'warn',
  PENDING_EMBEDDING: 'warn',
  EMBEDDING: 'warn',
  SUCCEEDED: 'ok',
  SEGMENT_FAILED: 'err',
  EMBEDDING_FAILED: 'err'
}
const isRunning = (s: DocumentStatus) => s === 'PENDING_PARSE' || s === 'SEGMENTING' || s === 'EMBEDDING'

const visible = ref(false)
const step = ref(0)
const submitting = ref(false)
const previewing = ref(false)
const uploadTab = ref('local')

const baseFormRef = ref<FormInstance>()
const embeddingModels = ref<ModelInfo[]>([])

/**
 * 库分类 / 密级 / 文种都取自基础数据字典（08 §十）。
 *
 * ★ 为什么不是 `knowledgeApi.listKnowledgeCategories()`：那个接口回的是"**已有库**的分类 + 计数"
 *   （现有数据的投影，知识库列表页的筛选要的就是它），拿它填新建下拉 ⇒ 永远建不出第一个新分类的库。
 */
const { items: categories, hint: categoryHint, load: loadCategories } = useDictionary('KB_CATEGORY', '库分类')
const { items: classifications, hint: classificationHint, load: loadClassifications } =
  useDictionary('CLASSIFICATION', '密级')
const { items: docTypes, hint: docTypeHint, load: loadDocTypes } = useDictionary('DOC_TYPE', '文种')

/** 提示要出现在它的字段那一步：分类 / 密级上限在第一步，文种 / 密级在第三步 */
const catalogHint = computed(() => [categoryHint.value, classificationHint.value].filter(Boolean).join('；'))
const docMetaHint = computed(() => [docTypeHint.value, classificationHint.value].filter(Boolean).join('；'))

const baseForm = reactive({
  name: '',
  category: '',
  icon: '📘',
  description: '',
  // ★ 取值必须是 SCREAMING_SNAKE_CASE（14 §2.1）：与后端 ImportType 枚举同名，否则真栈建库 400
  importType: 'LOCAL' as ImportType,
  embeddingModelId: '',
  /** 库的密级上限（ceiling，13 §6）—— 必填，缺了不让提交 */
  maxClassification: '' as ClassificationLevel | ''
})

const baseRules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
  importType: [{ required: true, message: '请选择导入类型', trigger: 'change' }],
  embeddingModelId: [{ required: true, message: '请选择向量模型', trigger: 'change' }],
  maxClassification: [{ required: true, message: '请选择密级上限', trigger: 'change' }]
}

const files = ref<File[]>([])
const fileList = ref<UploadFiles>([])
const uploadPolicy = ref<UploadPolicy | null>(null)
const fileRejects = ref<Array<{ name: string; reason: string }>>([])

/**
 * ★ 责任部门的选择题 = **组织树**（`T300` / #37）：存 org 的 id，不是展示名。
 *
 * <p>读失败时**让异常照常抛出**（不吞）：静默给一棵空树等于"建档时选不到任何部门"，
 * 而界面看起来只是"还没加载" —— 那是本项目头号病（看起来在做、实际没生效）的形状。
 */
const orgTree = ref<OrgNodeView[]>([])

const loadOrgTree = async () => {
  orgTree.value = await orgsApi.orgTree()
}

const segForm = reactive({
  strategy: 'AUTO' as 'AUTO' | 'CUSTOM',
  segmentLength: 500,
  overlap: 60
})

/** 建档必填三项（12 §5.1）—— 批级，一次导入同一套标签 */
const docMeta = reactive({
  docType: '',
  orgId: '',
  classification: '' as ClassificationLevel | ''
})
const docMetaFormRef = ref<FormInstance>()
const docMetaRules: FormRules = {
  docType: [{ required: true, message: '请选择文种', trigger: 'blur' }],
  orgId: [{ required: true, message: '请从组织树选责任部门', trigger: 'change' }],
  classification: [{ required: true, message: '请选择密级', trigger: 'change' }]
}

const preview = ref<PreviewSegmentationResult | null>(null)
const previewError = ref('')
/** 等解析时的进行中说明（T249.3）——"正在解析 已识别 1/3 页"这类。空 = 不在等。 */
const previewWait = ref('')
/**
 * 等解析落地的上限。★ 给足：扫描件每页约 3.6 秒，一份 20 页的扫描件就要一分多钟。
 * 超时不是"失败"，而是给出可操作的下一步（去文档列表看进度后重试）。
 */
const PREVIEW_PARSE_WAIT_MS = 180_000
const stageError = ref('')
const importError = ref('')
const modelLimit = ref(1200)
/**
 * 分段长度滑块上限：向量模型上限更紧时用它。
 * ★ 否则用户能把滑块拖到 2000、而模型上限只有 1200 —— 提交必被后端拒（mock 与真栈同口径 400），
 * 界面却允许他选，属于"让用户撞墙再告诉他不行"。
 */
const segmentLengthMax = computed(() => Math.max(100, Math.min(2000, modelLimit.value || 2000)))
const editingOrdinal = ref<number | null>(null)
const editingContent = ref('')
const editedOrdinals = ref(new Set<number>())

const createdKb = ref<KnowledgeBase | null>(null)
const resultDocs = ref<DocumentItem[]>([])
/** #12 幂等键：一次向导会话内不变。重试「开始导入」不会二次建库 */
const clientRequestId = ref('')
/**
 * 已经建档（并已解析、已分块）的文档 —— 一次向导会话内只上传一次。
 *
 * ★ 走「建档即解析 + 入库即分块」这条真实链路（0.2.1/0.2.2）：上传即建档，预览按 documentId 做
 *   （预览与入库用同一个分块器，第 7 周判据 ④）。
 * ⚠️ 早先这里走的是 mock 时代的「暂存上传 uploadIds → 按暂存文件预览」，而 `/uploads` 至今未实现
 *   ⇒ 真栈下预览必然 400，等于「创建知识库 + 生成预览」整条不可用。
 */
const ingestedDocs = ref<DocumentItem[]>([])
/** 是否已经把文档导进去过（用来决定关闭时要不要清孤儿库） */
const imported = ref(false)

const acceptAttr = computed(() =>
  uploadPolicy.value?.acceptedExtensions.length
    ? uploadPolicy.value.acceptedExtensions.join(',')
    : '.pdf,.docx,.doc,.md,.txt'
)

/**
 * 打开向导。
 *
 * @param presetCategory 列表页当前筛的那个分类 —— ★ 用户已经用筛选表达过"我要建这一类"，
 *   再让他选一次是白问一遍；空列表里的「创建第一个知识库」尤其明显（那个按钮
 *   本来就是从"当前筛选下没有库"的空态点出来的）。
 */
const open = async (presetCategory?: string) => {
  // ★ 当场把值写进表单（Issue #46）。**不要**只存进一个"待用变量"等 `reset()` 落值 ——
  //   `reset()` 只挂在 `@closed`（关窗之后），于是：第一次打开必为空（reset 从没跑过），
  //   第二次打开回填的是**上一次**传进来的分类（晚一拍）。用户会因此建错分类，而界面看起来"已填好"。
  baseForm.category = presetCategory ?? ''
  visible.value = true
  step.value = 0
  // ★ 每次打开都重读字典：管理员刚在「基础数据」里加过 / 停用过一项，建档的人要马上看到（判据 1、2）
  await Promise.all([loadCategories(), loadClassifications(), loadDocTypes(), loadOrgTree()])
  // ★ 拉满一页（后端上限 100）。原来取 50：向量模型一旦超过 50 条，
  //   排在后面的可用模型会被截掉，界面显示"没有可用的向量模型"——纯属分页假象。
  const models = await modelsApi.listModels({ kind: 'EMBEDDING', size: 100 })
  // 判据只在 `lib/modelReadiness.ts` 一处（工作台引导 + 本处共用）：
  // 向量模型「登记了没预热」也算能用（本地 ONNX 首次调用懒加载）。这条长得像 bug，
  // 所以不许各处各写一份 —— `#40` 的教训正是同一状态四处各写一份、漏改一处就是假绿。
  embeddingModels.value = models.list.filter(isUsableEmbeddingModel)
  try {
    // 上传策略挂在已有库上；新库还没建，先用一个可见的库拿策略，拿不到就用默认提示
    const anyKb = await knowledgeApi.listKnowledgeBases({ page: 1, size: 1 })
    if (anyKb.list[0]) {
      uploadPolicy.value = await ingestionApi.getUploadPolicy(anyKb.list[0].id)
    }
  } catch {
    uploadPolicy.value = null
  }
}

const reset = () => {
  step.value = 0
  baseForm.name = ''
  // ★ 分类**不在这里设**：它由 `open(presetCategory)` 当场写入。
  //   挂在这里就是那个"晚一拍"的 bug（`reset()` 只在 `@closed` 跑）—— 见 Issue #46。
  baseForm.icon = '📘'
  baseForm.description = ''
  baseForm.importType = 'LOCAL'
  baseForm.embeddingModelId = ''
  baseForm.maxClassification = ''
  files.value = []
  fileList.value = []
  fileRejects.value = []
  segForm.strategy = 'AUTO'
  segForm.segmentLength = 500
  segForm.overlap = 60
  docMeta.docType = ''
  docMeta.orgId = ''
  docMeta.classification = ''
  preview.value = null
  previewError.value = ''
  stageError.value = ''
  importError.value = ''
  editingOrdinal.value = null
  editedOrdinals.value = new Set()
  createdKb.value = null
  resultDocs.value = []
  ingestedDocs.value = []
  imported.value = false
  baseFormRef.value?.clearValidate()
}

/** 关闭时清理孤儿库：建了库但一个文档都没导入，就删掉 */
const onClosed = async () => {
  const kb = createdKb.value
  const shouldCleanup = kb && !imported.value && !resultDocs.value.length
  reset()
  if (shouldCleanup && kb) {
    try {
      await knowledgeApi.deleteKnowledgeBase(kb.id)
    } catch {
      // 清理失败不阻断关闭；空库留在列表里，用户可手动删
    }
  }
}

const collectFiles = (list: UploadFiles) => {
  const result: File[] = []
  for (const item of list) {
    if (item.raw) result.push(item.raw)
  }
  return result
}

const validateFiles = (list: File[]): Array<{ name: string; reason: string }> => {
  const bad: Array<{ name: string; reason: string }> = []
  const p = uploadPolicy.value
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

const onFileChange = (_f: UploadFile, list: UploadFiles) => {
  fileList.value = list
  files.value = collectFiles(list)
  fileRejects.value = validateFiles(files.value)
}

const onFileRemove = (_f: UploadFile, list: UploadFiles) => {
  fileList.value = list
  files.value = collectFiles(list)
  fileRejects.value = validateFiles(files.value)
}

/**
 * 确保知识库已创建（#12 幂等）。
 * 同一 clientRequestId 重试返回既有库 —— 上传失败后不会再撞「名称已存在」。
 */
const ensureKb = async (): Promise<KnowledgeBase> => {
  if (createdKb.value) {
    // 回上一步改过名称/分类的话，补一次更新
    try {
      createdKb.value = await knowledgeApi.updateKnowledgeBase(createdKb.value.id, {
        name: baseForm.name,
        category: baseForm.category,
        description: baseForm.description,
        icon: baseForm.icon,
        embeddingModelId: baseForm.embeddingModelId
      })
    } catch {
      // 更新失败不阻断导入流程，以已建库为准
    }
    return createdKb.value
  }
  if (!clientRequestId.value) {
    clientRequestId.value = `kbreq_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
  }
  createdKb.value = await knowledgeApi.createKnowledgeBase({
    name: baseForm.name,
    category: baseForm.category,
    description: baseForm.description,
    icon: baseForm.icon,
    importType: baseForm.importType,
    embeddingModelId: baseForm.embeddingModelId,
    maxClassification: baseForm.maxClassification,
    clientRequestId: clientRequestId.value
  })
  return createdKb.value
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
    const bad = validateFiles(files.value)
    if (bad.length) {
      fileRejects.value = bad
      return
    }
    // ★ 这一步不再调服务：上传要带上建档三项（文种/责任部门/密级），而它们在下一步。
    //   早先在这里调 stageUploads（真栈未实现、失败还被 catch 吞掉）⇒ 预览拿到空 uploadIds ⇒ 必然 400。
    step.value = 2
  }
}

/**
 * 建档：把选中的文件真正上传（**建档即解析 + 入库即分块**）。
 *
 * <p>同步实现语义：一次会话内只上传一次；重复点「生成预览」不会重复建档（也就不产生重复文档）。
 */
const ingestFiles = async (): Promise<DocumentItem[]> => {
  if (ingestedDocs.value.length) {
    return ingestedDocs.value
  }
  const kb = await ensureKb()
  const accepted = await ingestionApi.uploadDocuments(kb.id, files.value, {
    docType: docMeta.docType.trim(),
    orgId: docMeta.orgId,
    classification: docMeta.classification as ClassificationLevel
  })
  ingestedDocs.value = accepted
  imported.value = accepted.length > 0
  return accepted
}

/**
 * 把预览里编辑过的分段逐条提交。
 *
 * ★ 真栈只有**逐条编辑**端点（`PUT /segments/:id`），没有"整份计划覆盖"；
 *   所以这里按 ordinal 把预览结果映射回库里的分段再逐条改 —— 而不是"收下编辑但不用"
 *   （那会让用户以为改生效了）。
 */
const applyEditedSegments = async (documentId: string): Promise<number> => {
  if (!editedOrdinals.value.size || !preview.value) {
    return 0
  }
  const page = await ingestionApi.listSegments({ documentId, page: 1, size: 200 })
  const byOrdinal = new Map(page.list.map((s) => [s.ordinal, s]))
  let applied = 0
  for (const seg of preview.value.segments) {
    if (!editedOrdinals.value.has(seg.ordinal)) continue
    const target = byOrdinal.get(seg.ordinal)
    if (!target) continue
    await ingestionApi.updateSegment(target.id, { content: seg.content })
    applied += 1
  }
  return applied
}

const generatePreview = async () => {
  // ★ 建档三项要先齐：上传时它们随文件一起走（后端缺任一即 400）
  const metaValid = await docMetaFormRef.value?.validate().catch(() => false)
  if (!metaValid) {
    previewError.value = '请先填写文种 / 责任部门 / 密级 —— 它们在建档时随文件一起提交'
    return
  }
  previewing.value = true
  previewError.value = ''
  previewWait.value = ''
  try {
    // ★ 真实链路：先把文件建了档（解析 + 分块），再按 **documentId** 预览。
    //   预览与入库走同一个分块器 ⇒ 预览里看到的就是入库后会得到的（第 7 周判据 ④）。
    const docs = await ingestFiles()
    if (!docs.length) {
      previewError.value = '没有建档成功的文档，无法生成预览'
      return
    }
    // ★★ 解析是**异步**的（T249.3）：扫描件的 OCR 实测每页约 3.6 秒 ⇒ 建档刚返回时文档还在「分段中」，
    //   此刻取预览会被后端**如实拒绝**（"文档解析尚未完成，无法预览切分"）。
    //   ⇒ 界面要自己**等解析落地**再把预览取出来。
    //   不等的表现：用户点一次、被拒一次、再点一次 —— 而正确答案是"它正在解析，请稍候"，
    //   并且解析完**自动**出预览。扫描件那一次要等十几秒，让用户手点是不合理的。
    const doc = await waitParsed(docs[0].id, docs[0].kbId)
    if (!doc) {
      previewError.value = '文档解析超时（仍在解析中）—— 可在文档列表查看进度后重试'
      return
    }
    if (doc.status === 'SEGMENT_FAILED') {
      previewError.value = doc.failReason || '文档解析失败，无法生成预览'
      return
    }
    previewWait.value = ''
    preview.value = await ingestionApi.previewSegmentation({
      documentId: docs[0].id,
      segmentLength: segForm.segmentLength,
      overlap: segForm.overlap,
      strategy: segForm.strategy
    })
    modelLimit.value = preview.value.modelLimit
    editedOrdinals.value = new Set<number>()
  } catch (error) {
    // ★ 非 ApiError（前端异常）必须留下可定位的痕迹：泛化文案会把真因藏起来
    console.error('[创建向导] 生成预览失败', error)
    previewError.value = error instanceof ApiError ? error.message : '生成预览失败'
  } finally {
    previewing.value = false
    previewWait.value = ''
  }
}

/**
 * 等文档**解析落地**再取预览（T249.3）。
 *
 * <p>★ 为什么界面要自己等：解析改成异步之后，"建档返回"与"能预览"之间有一个可能十几秒的空档
 * （扫描件每页约 3.6 秒）。返回 null 表示超时 —— 那时给出可操作的下一步，而不是装作没事。
 */
const waitParsed = async (documentId: string, kbId: string): Promise<DocumentItem | null> => {
  const deadline = Date.now() + PREVIEW_PARSE_WAIT_MS
  while (Date.now() < deadline) {
    try {
      const page = await ingestionApi.listDocuments(kbId, { page: 1, size: 50 })
      const found = page.list.find((d) => d.id === documentId) ?? null
      if (found && (found.status === 'PENDING_EMBEDDING' || found.status === 'SEGMENT_FAILED')) {
        return found
      }
      const total = found?.ocrTotalPages ?? 0
      const done = found?.ocrDonePages ?? 0
      previewWait.value =
        total > 0
          ? `正在解析文档：已识别 ${done}/${total} 页（扫描件较慢，请稍候）`
          : '正在解析文档，请稍候…'
    } catch {
      // 轮询失败不致命：下一轮再试（网络抖动不该让用户看到"生成预览失败"）
    }
    await new Promise((resolve) => setTimeout(resolve, 700))
  }
  return null
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
  editedOrdinals.value = new Set(editedOrdinals.value).add(seg.ordinal)
  editingOrdinal.value = null
}

const startImport = async () => {
  // ★ 建档三项必填先校验（12 §5.1）：缺任一就在表单上给出可读原因
  const metaValid = await docMetaFormRef.value?.validate().catch(() => false)
  if (!metaValid) return
  if (!files.value.length && !ingestedDocs.value.length) {
    stageError.value = '文件已丢失，请回到上一步重新选择文件'
    ElMessage.error(stageError.value)
    return
  }
  submitting.value = true
  importError.value = ''
  try {
    // ★ 提交必须**确保文件真的建过档**：没走预览就提交时在这里补上传 ——
    //   否则会"提交成功但库里什么都没有"（早先的 names-only 轻量建档就是这个后果：
    //   建出来的是不带字节的空文档，解析与分段全空）。
    const docs = await ingestFiles()
    if (!docs.length) {
      importError.value = '没有建档成功的文档'
      return
    }
    // 预览里编辑过的分段逐条提交（真栈只有逐条编辑端点）
    const applied = await applyEditedSegments(docs[0].id)
    resultDocs.value = docs
    imported.value = true
    if (applied > 0) {
      ElMessage.success(`已应用 ${applied} 处分段编辑`)
    }
    step.value = 3
    if (createdKb.value) {
      emit('created', createdKb.value)
    }
  } catch (error) {
    // ★ 后端可读原因原文展示（如 ceiling 超限）—— 不代写
    importError.value = error instanceof ApiError ? error.message : '导入失败，请重试'
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
  letter-spacing: var(--tracking-body);
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

.doc-meta-form {
  margin-top: var(--space-xs);
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

/* 「已剔除 N 行页眉/页脚/页码/目录」—— 与预览头分开一点，避免看成标题的一部分 */
.noise-note {
  margin-top: var(--space-2xs);
  padding-left: var(--space-xs);
  border-left: 2px solid var(--color-rule);
}

/* 等解析落地时的说明：同上，给个左侧竖线让它看起来像"过程说明"而不是正文 */
.preview-wait {
  margin-top: var(--space-2xs);
  padding-left: var(--space-xs);
  border-left: 2px solid var(--color-rule);
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

.preview-error {
  margin: var(--space-md);
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

.pi-edited {
  font-size: var(--text-2xs);
  color: var(--color-accent);
  font-weight: 500;
  line-height: var(--leading-tight);
}

.pi-content {
  margin: 0;
  font-size: var(--text-xs);
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

.reject-list {
  margin-top: var(--space-sm);
}

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
