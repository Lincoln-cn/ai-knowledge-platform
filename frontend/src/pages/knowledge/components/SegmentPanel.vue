<template>
  <div class="seg-panel">
    <div class="toolbar">
      <el-select v-model="query.documentId" size="small" placeholder="全部文档" style="width: 200px" @change="onFilterChange">
        <el-option label="全部文档" value="" />
        <el-option v-for="d in docOptions" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-checkbox v-model="query.onlyOversized" @change="onFilterChange">只看超长</el-checkbox>
      <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      <span class="grow" />
      <template v-if="batchMode">
        <el-button size="small" type="primary" :disabled="!selectedIds.length" @click="openMove">批量迁移</el-button>
        <el-button size="small" :disabled="!selectedIds.length" @click="batchReembed">批量重新向量化</el-button>
        <el-button size="small" type="danger" :disabled="!selectedIds.length" @click="batchRemove">批量删除</el-button>
        <span class="micro muted">已选 {{ selectedIds.length }} 项</span>
        <el-button size="small" @click="exitBatch">取消选择</el-button>
      </template>
      <el-button v-else size="small" @click="batchMode = true">批量选择</el-button>
      <el-button v-if="canEdit" size="small" @click="reembedAll">全部重新向量化</el-button>
    </div>

    <div v-if="loadError" class="notice err">
      <div><b>加载失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" style="margin-top: var(--space-xs)" @click="fetchList">重试</el-button>
    </div>

    <div v-if="oversizedCount > 0" class="notice warn">
      ⚠ 当前有 <b>{{ oversizedCount }}</b> 个超长分段（超过向量模型上限约 {{ modelLimit }} 字）。
      超过上限的分段会被<b>拒绝</b>入库（不会静默截断）。建议：按段落切分，或调小分段长度后重新切分。
    </div>

    <div v-loading="loading" class="grid">
      <div
        v-for="seg in list"
        :key="seg.id"
        class="seg-card panel"
        :class="{
          oversized: seg.charCount > modelLimit,
          disabled: !seg.enabled,
          selected: selectedIds.includes(seg.id)
        }"
        @click="onCardClick(seg)"
      >
        <div class="sg-head">
          <el-checkbox
            v-if="batchMode"
            :model-value="selectedIds.includes(seg.id)"
            @click.stop
            @change="toggleSelect(seg.id)"
          />
          <span class="sg-title ellipsis">{{ seg.title || '（无标题）' }}</span>
          <span class="grow" />
          <el-tag v-if="seg.charCount > modelLimit" size="small" type="warning" effect="plain">超长</el-tag>
          <el-tag size="small" effect="plain" :type="embeddingTagType(seg.embedding)">
            {{ labelOf(SEGMENT_EMBEDDING_LABELS, seg.embedding) }}
          </el-tag>
        </div>

        <p class="sg-content">{{ seg.content }}</p>

        <div class="sg-fail" v-if="seg.charCount > modelLimit">
          该段 {{ seg.charCount }} 字，超过上限约 {{ modelLimit }} 字，将被向量模型截断。建议：按段落切分为多段，或调小分段长度。
        </div>

        <div class="sg-foot">
          <span class="micro muted">#{{ seg.ordinal }} · <b class="num">{{ seg.charCount }}</b> 字</span>
          <span class="grow" />
          <el-button
            v-if="!batchMode && canEdit && needsReembed(seg)"
            link size="small" type="primary"
            @click.stop="reembedOne(seg)"
          >重新向量化</el-button>
          <el-switch
            v-if="!batchMode && canEdit"
            :model-value="seg.enabled"
            size="small"
            @click.stop
            @change="toggleEnabled(seg)"
          />
          <el-button v-if="!batchMode && canEdit" link size="small" @click.stop="openEdit(seg)">编辑</el-button>
          <el-button v-if="!batchMode && canEdit" link size="small" type="danger" @click.stop="remove(seg)">删除</el-button>
        </div>
      </div>

      <!-- 空态与错误态严格区分：只有"请求成功且 0 条"才走这里 -->
      <div v-if="!loading && !loadError && !list.length" class="empty panel empty-split">
        <EmptyArt kind="pipeline" />
        <div class="empty-copy">
          <div class="empty-title">
            {{ query.onlyOversized ? '没有超长分段' : '该筛选下没有分段' }}
          </div>
          <p class="micro muted">调整筛选条件，或从文档页导入新内容</p>
        </div>
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

    <SegmentEditDialog
      ref="editRef"
      :model-limit="modelLimit"
      @saved="fetchList"
      @reembed="onEditReembed"
    />

    <el-dialog v-model="moveVisible" title="批量迁移分段" width="420px">
      <el-select v-model="moveTargetId" placeholder="选择目标文档" style="width: 100%">
        <el-option v-for="d in docOptions" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <p class="micro muted move-hint">迁移是搬移语义：源文档中删除、目标文档中写入。分段需重新向量化。</p>
      <template #footer>
        <el-button size="small" @click="moveVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="moving" :disabled="!moveTargetId" @click="confirmMove">
          迁移 {{ selectedIds.length }} 项
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { ingestionApi } from '@/api'
import { ApiError } from '@/api/types'
import type { DocumentItem, Segment } from '@/api/ingestion'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import SegmentEditDialog from './SegmentEditDialog.vue'
import EmptyArt from '@/components/EmptyArt.vue'
import { SEGMENT_EMBEDDING_LABELS, labelOf } from '@/constants/labels'

const props = defineProps<{ kbId: string; initialDocumentId?: string }>()

const session = useSessionStore()
const canEdit = computed(() => session.can(PERM.segmentEdit))

const list = ref<Segment[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref<{ reason: string; hint?: string } | null>(null)
const pageSize = 20
const docOptions = ref<DocumentItem[]>([])
const modelLimit = ref(1200)
const oversizedCount = ref(0)

const query = reactive({
  documentId: props.initialDocumentId ?? '',
  onlyOversized: false,
  page: 1
})

const batchMode = ref(false)
const selectedIds = ref<string[]>([])
const moveVisible = ref(false)
const moveTargetId = ref('')
const moving = ref(false)

const editRef = ref<InstanceType<typeof SegmentEditDialog>>()

const errText = (error: unknown, fallback: string): { reason: string; hint?: string } => {
  if (error instanceof ApiError) return { reason: error.message, hint: error.hint }
  if (error instanceof Error) return { reason: error.message || fallback }
  return { reason: fallback }
}

const embeddingTagType = (e: Segment['embedding']) => {
  if (e === 'EMBEDDED') return 'success'
  if (e === 'EMBEDDING') return 'warning'
  if (e === 'FAILED') return 'danger'
  return 'info'
}

const needsReembed = (seg: Segment) =>
  seg.embedding === 'PENDING_EMBEDDING' || seg.embedding === 'FAILED'

const fetchList = async () => {
  loading.value = true
  loadError.value = null
  try {
    // P1 跨库批量：始终带 kbId，否则列表会扫到别的库的分段，批量删除会跨库
    const result = await ingestionApi.listSegments({
      kbId: props.kbId,
      documentId: query.documentId || undefined,
      onlyOversized: query.onlyOversized || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total

    // 超长告警：用 onlyOversized 查一次拿总数（契约里 Segment 没有 oversized 字段）
    const over = await ingestionApi.listSegments({
      kbId: props.kbId,
      documentId: query.documentId || undefined,
      onlyOversized: true,
      page: 1,
      size: 1
    })
    oversizedCount.value = over.total
  } catch (error) {
    loadError.value = errText(error, '加载分段列表失败')
  } finally {
    loading.value = false
  }
}

const fetchDocs = async () => {
  try {
    const result = await ingestionApi.listDocuments(props.kbId, { page: 1, size: 100 })
    docOptions.value = result.list
  } catch {
    // 文档下拉是辅助信息，拿不到不阻断分段列表；下拉保持空即可
  }
}

/** 从切分预览拿 modelLimit（契约里 Segment 本身不带该字段） */
const fetchModelLimit = async () => {
  // ★ 契约要求预览必须给 documentId 或 segmentationPlan（后端会明确 400）。
  //   而「全部文档」时 query.documentId 是空串 ⇒ 原样发出去是一个**注定 400** 的请求，
  //   被下面的 catch 吞掉，于是 modelLimit 永远停在写死的默认值 —— 「看起来在读模型上限，其实没读到」。
  //   模型上限是**库级**属性（同一个库绑同一个向量模型）⇒ 随便借本库的一篇文档去问，问出来的就是同一个数。
  const probe = query.documentId || docOptions.value[0]?.id
  if (!probe) {
    // 库里还没有文档 ⇒ 也没有分段要判「超长」，不必发那个请求
    return
  }
  try {
    const preview = await ingestionApi.previewSegmentation({
      documentId: probe,
      segmentLength: 500,
      overlap: 60,
      strategy: 'AUTO'
    })
    modelLimit.value = preview.modelLimit
  } catch {
    // 拿不到就用默认值，不阻断列表
  }
}

const onFilterChange = () => {
  query.page = 1
  fetchList()
}

const onCardClick = (seg: Segment) => {
  if (batchMode.value) {
    toggleSelect(seg.id)
    return
  }
  if (canEdit.value) openEdit(seg)
}

const toggleSelect = (id: string) => {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) selectedIds.value.splice(idx, 1)
  else selectedIds.value.push(id)
}

const exitBatch = () => {
  batchMode.value = false
  selectedIds.value = []
}

const openEdit = (seg: Segment) => editRef.value?.open(seg)

/** 编辑对话框保存后勾了「立即重新向量化」 */
const onEditReembed = async (id: string) => {
  try {
    await ingestionApi.reembedSegment(id)
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '重新向量化失败').reason)
    await fetchList()
  }
}

const reembedOne = async (seg: Segment) => {
  try {
    await ingestionApi.reembedSegment(seg.id)
    ElMessage.success('已开始重新向量化')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '重新向量化失败').reason)
  }
}

const batchReembed = async () => {
  let ok = 0
  for (const id of selectedIds.value) {
    try {
      await ingestionApi.reembedSegment(id)
      ok += 1
    } catch {
      // 单段失败不阻断其余；回执在最后统一报
    }
  }
  ElMessage.success(ok ? `已提交 ${ok} 个分段重新向量化` : '没有分段被提交')
  exitBatch()
  await fetchList()
}

const reembedAll = async () => {
  await ElMessageBox.confirm(
    '将对该知识库下全部文档重新向量化，可能需要一些时间。确认？',
    '全部重新向量化',
    { type: 'warning', confirmButtonText: '开始' }
  )
  try {
    const result = await ingestionApi.reembedKnowledgeBase(props.kbId)
    ElMessage.success(`已排队 ${result.queued} 个分段`)
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '重新向量化失败').reason)
  }
}

const toggleEnabled = async (seg: Segment) => {
  const next = !seg.enabled
  try {
    await ingestionApi.setSegmentEnabled(seg.id, next)
    ElMessage.success(next ? '已启用，参与检索' : '已停用，不再参与检索')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '操作失败').reason)
  }
}

const remove = async (seg: Segment) => {
  await ElMessageBox.confirm(
    '删除后不可恢复。确认删除该分段？',
    '确认删除分段',
    { type: 'warning', confirmButtonText: '删除' }
  )
  try {
    await ingestionApi.deleteSegment(seg.id)
    ElMessage.success('已删除')
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '删除失败').reason)
    await fetchList()
  }
}

/** P1 批量删除走单次 batch 接口，拿部分失败回执 */
const batchRemove = async () => {
  await ElMessageBox.confirm(
    `将删除选中的 ${selectedIds.value.length} 个分段，且不可恢复。确认？`,
    '批量删除分段',
    { type: 'warning', confirmButtonText: '删除' }
  )
  try {
    const result = await ingestionApi.batchDeleteSegments(selectedIds.value)
    if (result.failed.length) {
      ElMessage.warning(
        `已删除 ${result.deleted} 个，${result.failed.length} 个失败：${result.failed[0].reason}`
      )
    } else {
      ElMessage.success(`已删除 ${result.deleted} 个分段`)
    }
  } catch (error) {
    ElMessage.error(errText(error, '批量删除失败').reason)
  }
  exitBatch()
  await fetchList()
}

const openMove = () => {
  moveTargetId.value = ''
  moveVisible.value = true
}

const confirmMove = async () => {
  moving.value = true
  try {
    const result = await ingestionApi.batchMoveSegments({
      segmentIds: selectedIds.value,
      targetDocumentId: moveTargetId.value
    })
    ElMessage.success(`已迁移 ${result.moved} 个分段，需重新向量化`)
    moveVisible.value = false
    exitBatch()
    await fetchList()
  } catch (error) {
    ElMessage.error(errText(error, '迁移失败').reason)
  } finally {
    moving.value = false
  }
}

watch(
  () => props.initialDocumentId,
  (id) => {
    query.documentId = id ?? ''
    fetchList()
  }
)

onMounted(async () => {
  // ★ 顺序有意义：模型上限要借一篇文档去问，所以文档清单必须先到（原来是并发发的，谁先到看运气）
  await fetchDocs()
  fetchModelLimit()
  fetchList()
})
</script>

<style scoped>
.seg-panel {
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

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--space-sm);
  min-height: 120px;
}

.seg-card {
  padding: var(--space-sm) var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  cursor: pointer;
  transition:
    border-color var(--dur-micro) var(--ease-out),
    background var(--dur-micro) var(--ease-out),
    opacity var(--dur-micro) var(--ease-out);
}

.seg-card:hover {
  border-color: var(--color-accent-line);
}

.seg-card.selected {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
  box-shadow: inset 2px 0 0 var(--color-accent);
}

.seg-card.disabled {
  opacity: 0.55;
}

.seg-card.oversized {
  border-color: color-mix(in oklch, var(--color-warn) 40%, var(--color-rule));
}

.sg-head {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

.sg-title {
  font-size: var(--text-sm);
  font-weight: 500;
  line-height: var(--leading-tight);
  color: var(--color-ink);
  min-width: 0;
}

.sg-content {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-ink-2);
  line-height: var(--leading-body);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  flex: 1;
}

.sg-fail {
  font-size: var(--text-2xs);
  color: var(--color-warn);
  line-height: var(--leading-body);
  padding: var(--space-2xs) var(--space-xs);
  background: var(--color-warn-soft);
  border-radius: var(--radius);
}

.sg-foot {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding-top: var(--space-xs);
  border-top: 1px solid var(--color-rule-2);
}

.sg-foot .num {
  font-size: var(--text-2xs);
  font-variant-numeric: tabular-nums;
  color: var(--color-ink-2);
}

.empty {
  grid-column: 1 / -1;
}

/* 空态两栏：把全局 .empty 的居中排布换成左起的两栏（图左字右）。 */
.empty-split {
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: var(--space-xl);
  text-align: left;
}

.empty-copy {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.pager {
  display: flex;
  justify-content: flex-end;
}

.move-hint {
  margin-top: var(--space-xs);
  line-height: var(--leading-body);
}
</style>
