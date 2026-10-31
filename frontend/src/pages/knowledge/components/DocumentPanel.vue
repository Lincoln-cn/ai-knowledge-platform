<template>
  <div class="doc-panel">
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索文档名"
        style="width: 200px"
        clearable
        :prefix-icon="Search"
        @keyup.enter="fetchList"
        @clear="fetchList"
      />
      <el-select v-model="query.status" size="small" placeholder="全部状态" style="width: 130px" @change="onFilterChange">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STATUSES" :key="s" :label="labelOf(DOCUMENT_STATUS_LABELS, s)" :value="s" />
      </el-select>
      <!--
        ★ `#56`：待补归属这一档。加它以前，回填时匹配不上责任部门的文档只能靠"那一列空着"被发现，
        看起来像"还没填"，而不像"系统需要你处理"（`department_legacy` 是不可更新的存档列）。
      -->
      <el-select
        v-model="query.orgAssignment"
        size="small"
        placeholder="全部归属"
        style="width: 130px"
        @change="onFilterChange"
      >
        <el-option label="全部归属" value="" />
        <el-option label="待补归属" value="UNASSIGNED" />
        <el-option label="已有归属" value="ASSIGNED" />
      </el-select>
      <el-button size="small" :icon="Refresh" :loading="loading" @click="manualRefresh">刷新</el-button>
      <!-- ★ 回收站（`#75` 判据 ④）：彻底删除的入口。没有它，那个端点只能由运维用 curl 调 ——
           "不可恢复的操作只有一个看不见的入口"本身就是风险。 -->
      <el-button v-if="canManage" size="small" @click="openRecycle">回收站</el-button>
      <span class="grow" />
      <el-button v-if="canUpload" size="small" type="primary" @click="openUpload">+ 上传文档</el-button>
    </div>

    <GlobalProgress :progress="progress" :load-error="progressError" @retry="manualRefresh" />

    <!--
      ★★ `#56` 的可读提示：只把文档列出来还不够 —— 这一行必须说明"为什么它们在这里、你要做什么"。
      没有它，用户看到的是"责任部门那一列空着"，读起来像"还没填"，而不像"系统需要你处理"
      （那正是这条账被埋了两个月的原因）。

      ★★ `#150` 落地之后这一段改成了**真正的操作指引**（改之前是刻意保守的措辞 ——
      当时建档之后没有修改责任部门的入口，写"请指定"就是一句做不到的指引，会让用户找半天）。
    -->
    <div v-if="query.orgAssignment === 'UNASSIGNED'" class="notice info">
      <div>
        <b>待补归属：{{ total }} 篇。</b>
        这些文档在导入时<strong>无法按责任部门名称自动匹配</strong>（原值已存档），因此归属为空。
        请在「责任部门」那一列点<strong>指定</strong>逐一补上 —— 补完它们会从这一档消失。
      </div>
    </div>

    <div v-if="loadError" class="notice err">
      <div><b>加载失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" style="margin-top: var(--space-xs)" @click="refreshAll">重试</el-button>
    </div>

    <div v-loading="loading" class="table-wrap">
      <el-table :data="list" row-key="id" empty-text="暂无文档，点右上角「上传文档」开始导入">
        <el-table-column label="文档名" min-width="200">
          <template #default="{ row }">
            <div class="doc-name">
              <span class="ellipsis">{{ row.name }}</span>
              <el-button
                v-if="canManage"
                link size="small" :icon="EditPen"
                class="rename-btn"
                @click="openRename(row)"
              />
            </div>
          </template>
        </el-table-column>

        <!--
          ★★ `#150`：责任部门列。它此前**根本不显示** —— 于是「待补归属」那一档的文档
          只能靠"别的列看起来正常"被发现，而归属为空在数据权限上是**谁也不可见**
          （`07 §十一` 组织三消费位之一），所以这不是"少一列"，是"少一个能看出问题的面"。
        -->
        <el-table-column label="责任部门" width="180">
          <template #default="{ row }">
            <span v-if="row.orgId" class="ellipsis">{{ orgNames.get(row.orgId) || row.orgId }}</span>
            <template v-else>
              <span class="muted">未指定</span>
              <el-button
                v-if="canUpload"
                link size="small" type="primary"
                @click="openAssign(row)"
              >指定</el-button>
            </template>
          </template>
        </el-table-column>

        <el-table-column label="分段数" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.segmentCount" class="num">{{ row.segmentCount }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="190">
          <template #default="{ row }">
            <span class="status" :class="statusClass(row.status as DocumentStatus)">
              <el-icon v-if="isRunning(row.status as DocumentStatus)" class="is-loading"><Loading /></el-icon>
              <i v-else class="dot" :class="statusDot(row.status as DocumentStatus)" />
              {{ labelOf(DOCUMENT_STATUS_LABELS, row.status) }}
            </span>
            <!--
              ★ 「已识别 N/M 页」（T249.3，12 §11.8）：扫描件的 OCR 实测每页约 3.6 秒，
                没有这个数用户面对的就是几分钟空白。进度是**库里的事实**（后端逐页回写），
                所以刷新页面不重置 —— 与向量化进度同一条纪律。
              ★ 只在"真的涉及 OCR 且在解析中"时显示：M=0（非 PDF / 纯文本 PDF）时不显示，
                否则每条文本型文档都会挂一条恒为 0/0 的噪声。
            -->
            <span
              v-if="row.status === 'SEGMENTING' && (row.ocrTotalPages ?? 0) > 0"
              class="ocr-progress micro num"
            >
              已识别 {{ row.ocrDonePages ?? 0 }}/{{ row.ocrTotalPages }} 页
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="400" fixed="right">
          <template #default="{ row }">
            <!-- 行内进度：向量化中时展示 processed/total，不是百分比 -->
            <span v-if="row.status === 'EMBEDDING'" class="row-progress">
              <span class="rp-bar">
                <span class="rp-fill" :style="{ width: embedPercent(row) + '%' }" />
              </span>
              <span class="micro num">{{ row.processedSegments }}/{{ row.segmentCount }}</span>
            </span>

            <el-button
              v-if="row.status === 'PENDING_EMBEDDING' && canUpload"
              link size="small" type="primary"
              @click="startEmbed(row)"
            >开始向量化</el-button>

            <!-- #10 文档级重新向量化入口 -->
            <el-button
              v-if="row.status === 'SUCCEEDED' && canUpload"
              link size="small"
              @click="reembed(row)"
            >重新向量化</el-button>

            <template v-if="isFailed(row.status)">
              <el-button link size="small" @click="toggleFail(row.id)">
                {{ expandedFails.has(row.id) ? '收起原因' : '查看原因' }}
              </el-button>
              <el-button v-if="canUpload" link size="small" type="primary" @click="retry(row)">重试</el-button>
            </template>

            <el-button
              v-if="row.status === 'SUCCEEDED'"
              link size="small"
              @click="emit('gotoSegments', row.id)"
            >查看分段</el-button>

            <!-- 解析结果：所有文档都能看 ——
                 待解析时是空态（「尚未解析」），失败态展示 failReason + 部分正文 -->
            <el-button
              link size="small"
              @click="openParseResult(row)"
            >解析结果</el-button>

            <el-button v-if="canManage" link size="small" @click="toggleEnabled(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canManage" link size="small" @click="openMeta(row)">密级与标签</el-button>
            <el-button v-if="canManage" link size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 失败原因展开在表格下方独立区块，避免 expand 列不好用 -->
    <div v-for="row in failedRows" :key="'f' + row.id">
      <div v-if="expandedFails.has(row.id)" class="notice err fail-panel">
        <div class="row-between">
          <div class="fail-title">{{ row.name }} · 失败原因</div>
          <el-button link size="small" @click="toggleFail(row.id)">收起</el-button>
        </div>
        <p class="fail-reason">{{ row.failReason || '未提供失败原因' }}</p>
        <p v-if="row.failHint" class="fail-hint">{{ row.failHint }}</p>
      </div>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="fetchList"
      />
    </div>

    <el-dialog v-model="recycleOpen" title="回收站（已删除的文档）" width="760px">
      <p class="micro muted">
        这些文档已从列表移除，<b>它们的分段与向量也已经删掉</b> —— 回收站只保留一条记录，不保留内容。
        <b>要恢复只能重新上传</b>；下面的「彻底删除」会连记录一起移除（备份里仍有一段时间的副本）。
      </p>
      <el-table v-loading="recycleLoading" :data="recycleRows" size="small">
        <el-table-column prop="name" label="文档名" min-width="220" />
        <el-table-column label="删除时间" width="170">
          <template #default="{ row }">{{ row.deletedAt ? new Date(row.deletedAt).toLocaleString('zh-CN') : '—' }}</template>
        </el-table-column>
        <el-table-column label="" width="120">
          <template #default="{ row }">
            <el-button link size="small" type="danger" @click="purge(row)">彻底删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="empty-title">回收站是空的</div>
            <div class="micro muted">被删除的文档会先来这里，确认不再需要之后再彻底删除。</div>
          </div>
        </template>
      </el-table>
    </el-dialog>

    <UploadDialog ref="uploadRef" :kb-id="kbId" @uploaded="onUploaded" />
    <ParseResultDialog ref="parseResultRef" />

    <!--
      ★ `#150` 指定责任部门。用组织树选择器（与建档时同一个控件、同一份数据）——
      ★ 不用自由文本：部门名可变、可重名，而它要进召回前缀（`07 §十一`），所以存的必须是 id。
    -->
    <el-dialog
      :model-value="assigningFor !== null"
      title="指定责任部门"
      width="420px"
      @update:model-value="(v: boolean) => { if (!v) assigningFor = null }"
    >
      <div class="assign-target">文档：{{ assigningFor?.name }}</div>
      <el-tree-select
        v-model="assignOrgId"
        :data="orgTree"
        node-key="id"
        :props="{ label: 'name', children: 'children' }"
        check-strictly
        :render-after-expand="false"
        default-expand-all
        style="width: 100%"
        placeholder="从组织树选责任部门"
      />
      <template #footer>
        <el-button size="small" @click="assigningFor = null">取消</el-button>
        <el-button
          size="small" type="primary"
          :loading="assigning"
          :disabled="!assignOrgId"
          @click="submitAssign"
        >确定</el-button>
      </template>
    </el-dialog>

    <!-- ★ `#79` 第三格：这两个端点在契约与后端都在，但页面没有入口 —— 有入口才算"接上了"。
         密级的下拉走基础数据字典（`08 §十`），不在前端写死文案：那是管理员能改的数据。 -->
    <el-dialog v-model="metaOpen" title="密级与标签" width="520px">
      <div class="meta-body">
        <div class="meta-row">
          <span class="meta-label">文档</span>
          <span>{{ metaForm.name }}</span>
        </div>
        <div class="meta-row">
          <span class="meta-label">密级</span>
          <el-select v-model="metaForm.classification" style="width: 200px" placeholder="选择密级">
            <el-option v-for="c in classifications" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
        </div>
        <p v-if="classificationHint" class="meta-hint">{{ classificationHint }}</p>
        <div class="meta-row">
          <span class="meta-label">标签</span>
          <el-input
            v-model="metaForm.labelsText"
            style="width: 300px"
            placeholder="用逗号分隔，例如：财务资料,合同"
            clearable
          />
        </div>
        <p class="meta-hint">
          不填 = 不受标签限制（绝大多数文档的默认状态）；填了之后，只有带相交标签的角色才能检索到它。
          改密级会同步改写该文档的全部分段，并把引用过它的会话密级水位抬到新值（降密不动水位）。
        </p>
      </div>
      <template #footer>
        <el-button @click="metaOpen = false">取消</el-button>
        <el-button type="primary" :loading="metaSaving" @click="submitMeta">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Loading, Refresh, Search } from '@element-plus/icons-vue'
import { ingestionApi, orgsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { DocumentItem, DocumentStatus, IngestionProgress, OrgAssignment } from '@/api/ingestion'
import type { ClassificationLevel } from '@/api/types'
import type { OrgNodeView } from '@/api/orgs'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import GlobalProgress from './GlobalProgress.vue'
import UploadDialog from './UploadDialog.vue'
import ParseResultDialog from './ParseResultDialog.vue'
import { DOCUMENT_STATUS_LABELS, labelOf } from '@/constants/labels'
import { useDictionary } from '@/composables/useDictionary'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ gotoSegments: [documentId: string] }>()

const session = useSessionStore()
const canUpload = computed(() => session.can(PERM.documentUpload))
const canManage = computed(() => session.can(PERM.knowledgeManage))

/** 密级下拉的取值来自基础数据字典（`08 §十`）—— 不在前端写死，管理员能改 */
const { items: classifications, hint: classificationHint, load: loadClassifications } =
  useDictionary('CLASSIFICATION', '密级')

const STATUSES: DocumentStatus[] = [
  'PENDING_PARSE',
  'SEGMENTING',
  'PENDING_EMBEDDING',
  'EMBEDDING',
  'SUCCEEDED',
  'SEGMENT_FAILED',
  'EMBEDDING_FAILED'
]

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
const isFailed = (s: DocumentStatus) => s === 'SEGMENT_FAILED' || s === 'EMBEDDING_FAILED'
const statusClass = (s: DocumentStatus) => STATUS_CLASS[s] ?? 'st-idle'
const statusDot = (s: DocumentStatus) => STATUS_DOT[s] ?? ''

const list = ref<DocumentItem[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref<{ reason: string; hint?: string } | null>(null)
const progressError = ref<string | null>(null)
const pageSize = 20
const expandedFails = ref(new Set<string>())

const query = reactive({
  keyword: '',
  status: '' as DocumentStatus | '',
  /**
   * ★ 责任部门归属筛选（`#56`）：`V27` 把责任部门从自由字符串换成 `org_id` 时按名字回填，
   * **匹配不上的原值留在 `department_legacy`**，而它们此前没有任何界面入口能列出来。
   * 选「待补归属」即列出这批文档 —— 指定归属后它们会从这一档消失。
   */
  orgAssignment: '' as OrgAssignment | '',
  page: 1
})

const progress = ref<IngestionProgress>({
  kbId: props.kbId,
  total: 0,
  done: 0,
  failed: 0,
  stages: [],
  running: false
})

const failedRows = computed(() => list.value.filter((d) => isFailed(d.status)))

/**
 * ★★ 责任部门（`#150`）：列里显示的是**部门名**，而库里存的是 `orgId`。
 *
 * <p>`DocumentView` 的注释把这条口径写死了：「要给人看部门名就由前端拿 `orgId` 去组织树查」。
 * 所以这里取一次组织树，建 id → name 的映射（★ 不是每个行各查一次 —— 那是 N+1，与
 * `OrgDirectoryPort#findByIds` 存在的理由同一条）。
 */
const orgTree = ref<OrgNodeView[]>([])
const orgNames = computed(() => {
  const map = new Map<string, string>()
  const walk = (nodes: OrgNodeView[]) => {
    nodes.forEach((node) => {
      map.set(node.id, node.name)
      if (node.children?.length) walk(node.children)
    })
  }
  walk(orgTree.value)
  return map
})

const loadOrgTree = async () => {
  try {
    orgTree.value = await orgsApi.orgTree()
  } catch {
    // 组织树只是"把 id 显示成人能读的名字"—— 取不到就不显示名字（列里落回 —），
    // ★ 不许因此让整个文档列表报错：列表本身是能用的。
    orgTree.value = []
  }
}

/** 指定归属的目标：非空即代表"正在改这一篇"。 */
const assigningFor = ref<DocumentItem | null>(null)
const assignOrgId = ref('')
const assigning = ref(false)

const openAssign = (row: DocumentItem) => {
  assigningFor.value = row
  assignOrgId.value = ''
}

const submitAssign = async () => {
  const row = assigningFor.value
  if (!row || !assignOrgId.value) return
  assigning.value = true
  try {
    await ingestionApi.assignDocumentOrg(props.kbId, row.id, assignOrgId.value)
    ElMessage.success('责任部门已指定')
    assigningFor.value = null
    assignOrgId.value = ''
    // ★ 刷新：若当前正停在「待补归属」那一档，这一篇会当场从列表里消失（那就是这一档的判据）
    await fetchList()
  } catch (error) {
    const { reason, hint } = errText(error, '指定责任部门失败')
    ElMessage.error(hint ? `${reason}（${hint}）` : reason)
  } finally {
    assigning.value = false
  }
}

const embedPercent = (row: DocumentItem) => {
  if (!row.segmentCount) return 0
  return Math.min(100, Math.round((row.processedSegments / row.segmentCount) * 100))
}

const errText = (error: unknown, fallback: string): { reason: string; hint?: string } => {
  if (error instanceof ApiError) return { reason: error.message, hint: error.hint }
  if (error instanceof Error) return { reason: error.message || fallback }
  return { reason: fallback }
}

const fetchList = async () => {
  loading.value = true
  loadError.value = null
  try {
    const result = await ingestionApi.listDocuments(props.kbId, {
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      orgAssignment: query.orgAssignment || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    loadError.value = errText(error, '加载文档列表失败')
  } finally {
    loading.value = false
  }
}

const fetchProgress = async () => {
  progressError.value = null
  try {
    progress.value = await ingestionApi.getIngestionProgress(props.kbId)
  } catch (error) {
    // 进度失败必须与"暂无文档"区分：GlobalProgress 靠这个字段换标题
    progressError.value = errText(error, '加载进度失败').reason
    throw error
  }
}

const refreshAll = async () => {
  await Promise.all([fetchList(), fetchProgress().catch(() => undefined)])
}

/* ── 轮询：只在有进行中任务时轮询；完成即停；页面不可见暂停；
      上限 + 退避 —— 后端卡在"运行中"时不会无限每 1.5s 打一枪 ── */
const POLL_BASE_MS = 1500
const POLL_MAX_MS = 12_000
const POLL_MAX_ATTEMPTS = 48

let timer: ReturnType<typeof setTimeout> | null = null
let pollAttempts = 0

const clearTimer = () => {
  if (timer) clearTimeout(timer)
  timer = null
}

const nextDelay = () =>
  Math.min(POLL_MAX_MS, Math.round(POLL_BASE_MS * Math.pow(1.25, Math.max(0, pollAttempts - 1))))

const schedule = () => {
  clearTimer()
  if (!progress.value.running || document.hidden) {
    pollAttempts = 0
    return
  }
  if (pollAttempts >= POLL_MAX_ATTEMPTS) {
    progressError.value = `轮询已达上限（${POLL_MAX_ATTEMPTS} 次），已停止自动刷新。可手动点「刷新」继续。`
    return
  }
  pollAttempts += 1
  timer = setTimeout(async () => {
    try {
      await refreshAll()
    } catch {
      clearTimer()
      pollAttempts = 0
      return
    }
    schedule()
  }, nextDelay())
}

const onVisibility = () => {
  if (document.hidden) {
    clearTimer()
    return
  }
  // 回到前台立即补一次，不等下一个周期
  refreshAll().then(schedule, clearTimer)
}

const manualRefresh = async () => {
  pollAttempts = 0
  await refreshAll()
  schedule()
}

const onFilterChange = () => {
  query.page = 1
  fetchList().then(schedule)
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility)
  refreshAll().then(schedule)
  // ★ `#150`：组织树只取一次（把 `orgId` 显示成人能读的部门名）。取不到不影响列表。
  void loadOrgTree()
  void loadClassifications()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  clearTimer()
})

/* ── 操作 ── */
const uploadRef = ref<InstanceType<typeof UploadDialog>>()
const parseResultRef = ref<InstanceType<typeof ParseResultDialog>>()

const openUpload = () => uploadRef.value?.open()

/** 打开「解析结果」弹窗：渲染 parsedContent，失败态一并展示 failReason / failHint 原文 */
const openParseResult = (row: DocumentItem) => parseResultRef.value?.open(row)

const onUploaded = async () => {
  await refreshAll()
  schedule()
}

const startEmbed = async (row: DocumentItem) => {
  try {
    await ingestionApi.startEmbedding(row.id)
    ElMessage.success(`已开始向量化「${row.name}」`)
    pollAttempts = 0
    await refreshAll()
    schedule()
  } catch (error) {
    ElMessage.error(errText(error, '启动向量化失败').reason)
  }
}

/** #10 文档级重新向量化 */
const reembed = async (row: DocumentItem) => {
  try {
    await ingestionApi.reembedDocument(row.id)
    ElMessage.success(`已开始重新向量化「${row.name}」`)
    pollAttempts = 0
    await refreshAll()
    schedule()
  } catch (error) {
    ElMessage.error(errText(error, '重新向量化失败').reason)
  }
}

const retry = async (row: DocumentItem) => {
  try {
    await ingestionApi.retryDocument(row.id)
    ElMessage.success(`已重新提交「${row.name}」，从失败阶段继续`)
    expandedFails.value.delete(row.id)
    pollAttempts = 0
    await refreshAll()
    schedule()
  } catch (error) {
    ElMessage.error(errText(error, '重试失败').reason)
  }
}

const toggleFail = (id: string) => {
  const next = new Set(expandedFails.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedFails.value = next
}

const openRename = async (row: DocumentItem) => {
  let value: string
  try {
    const res = await ElMessageBox.prompt('输入新的文档名', '重命名文档', {
      inputValue: row.name,
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    value = res.value
  } catch {
    // 用户取消，不是错误
    return
  }
  if (!value?.trim()) return
  try {
    await ingestionApi.renameDocument(row.id, value.trim())
    ElMessage.success('已重命名')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '重命名失败').reason)
  }
}

const toggleEnabled = async (row: DocumentItem) => {
  const next = !row.enabled
  if (!next) {
    try {
      await ElMessageBox.confirm(
        `停用后「${row.name}」的分段将不再参与检索。确认停用？`,
        '确认停用文档',
        { type: 'warning', confirmButtonText: '停用' }
      )
    } catch {
      return
    }
  }
  try {
    await ingestionApi.setDocumentEnabled(row.id, next)
    ElMessage.success(next ? '已启用' : '已停用')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '操作失败').reason)
  }
}

/* ★ 回收站（`#75` 判据 ④）：让「彻底删除」有地方可点。
   ★ 它消费两个端点：`listDeletedDocuments`（这个列表）与 `purgeDocument`（那一列的按钮）。
   ★ 在设计上刻意**不做**"撤销删除"：软删之后能恢复需要把分段与向量一起重建（重新解析），
     那是另一个功能的形状，做成一个按钮会让它听起来像"点一下就能回来"。 */
const recycleOpen = ref(false)
const recycleLoading = ref(false)
const recycleRows = ref<DocumentItem[]>([])

async function openRecycle() {
  recycleOpen.value = true
  recycleRows.value = []
  recycleLoading.value = true
  try {
    const page = await ingestionApi.listDeletedDocuments(props.kbId, { page: 1, size: 100 })
    recycleRows.value = page.list
  } catch (error) {
    ElMessage.error(errText(error, '回收站加载失败').reason)
  } finally {
    recycleLoading.value = false
  }
}

async function purge(row: DocumentItem) {
  try {
    await ElMessageBox.confirm(
      `彻底删除「${row.name}」会把文档、分段与向量一起移除，**不可恢复**（备份里还有一段时间内的副本）。确认？`,
      '确认彻底删除',
      { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await ingestionApi.purgeDocument(row.id)
    ElMessage.success('已彻底删除')
    recycleRows.value = recycleRows.value.filter((item) => item.id !== row.id)
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '彻底删除失败').reason)
  }
}

/* ★ `#79` 第三格：密级与标签的入口。
   ★ 两个端点都在、契约也在，但**页面没有入口** —— 那正是"后端做完了、功能没做完"的藏身处。 */
const metaOpen = ref(false)
const metaSaving = ref(false)
const metaOrigin = reactive<{ classification: string; labels: string[] }>({
  classification: '',
  labels: []
})
const metaForm = reactive<{
  id: string
  name: string
  classification: string
  labelsText: string
}>({ id: '', name: '', classification: '', labelsText: '' })

const parseLabels = (text: string): string[] =>
  text
    .split(/[,，、;；\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)

const openMeta = async (row: DocumentItem) => {
  metaForm.id = row.id
  metaForm.name = row.name
  metaForm.classification = row.classification ?? ''
  metaForm.labelsText = (row.labels ?? []).join('，')
  metaOrigin.classification = row.classification ?? ''
  metaOrigin.labels = [...(row.labels ?? [])]
  metaOpen.value = true
  if (!classifications.value.length) await loadClassifications()
}

const submitMeta = async () => {
  const labels = parseLabels(metaForm.labelsText)
  const classificationChanged =
    !!metaForm.classification && metaForm.classification !== metaOrigin.classification
  const labelsChanged = labels.join('|') !== metaOrigin.labels.join('|')
  if (!classificationChanged && !labelsChanged) {
    ElMessage.info('没有改动')
    metaOpen.value = false
    return
  }
  metaSaving.value = true
  try {
    if (classificationChanged) {
      await ingestionApi.changeDocumentClassification(
        metaForm.id,
        metaForm.classification as ClassificationLevel
      )
    }
    if (labelsChanged) await ingestionApi.changeDocumentLabels(metaForm.id, labels)
    ElMessage.success('已保存')
    metaOpen.value = false
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '保存失败').reason)
  } finally {
    metaSaving.value = false
  }
}

const remove = async (row: DocumentItem) => {
  try {
    await ElMessageBox.confirm(
      `删除「${row.name}」将一并移除其全部分段，且不可恢复。确认删除？`,
      '确认删除文档',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch {
    return
  }
  try {
    await ingestionApi.deleteDocument(row.id)
    ElMessage.success('已删除')
    await refreshAll()
  } catch (error) {
    ElMessage.error(errText(error, '删除失败').reason)
    await refreshAll()
  }
}
</script>

<style scoped>
.doc-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.table-wrap {
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.doc-name {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  min-width: 0;
}

.rename-btn {
  opacity: 0;
  transition: opacity var(--dur-micro) var(--ease-out);
}

tr:hover .rename-btn {
  opacity: 1;
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

.row-progress {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  margin-right: var(--space-xs);
  vertical-align: middle;
}

/* 「已识别 N/M 页」：跟在状态后面另起一行；等宽数字（进度在跳，避免宽度抖动） */
.ocr-progress {
  display: block;
  margin-top: 2px;
  color: var(--color-text-3);
}

.rp-bar {
  display: inline-block;
  width: 64px;
  height: 4px;
  border-radius: var(--radius-pill);
  background: var(--color-sunken);
  overflow: hidden;
}

.rp-fill {
  display: block;
  height: 100%;
  background: var(--color-accent);
  border-radius: var(--radius-pill);
  transition: width var(--dur-short) var(--ease-out);
}

.fail-panel {
  margin-bottom: var(--space-xs);
}

.fail-title {
  font-size: var(--text-2xs);
  font-weight: 500;
  line-height: var(--leading-tight);
  margin-bottom: var(--space-2xs);
  color: var(--color-ink);
}

.fail-reason {
  margin: 0;
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.fail-hint {
  margin: var(--space-2xs) 0 0;
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
  opacity: 0.85;
}

.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
