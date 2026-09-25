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
        <el-button size="small" type="danger" :disabled="!selectedIds.length" @click="batchRemove">批量删除</el-button>
        <span class="micro muted">已选 {{ selectedIds.length }} 项</span>
        <el-button size="small" @click="exitBatch">取消选择</el-button>
      </template>
      <el-button v-else size="small" @click="batchMode = true">批量选择</el-button>
    </div>

    <div v-if="oversizedCount > 0" class="notice warn">
      ⚠ 当前有 <b>{{ oversizedCount }}</b> 个超长分段（超过向量模型上限约 {{ modelLimit }} 字）。
      超出部分将被截断，影响召回。建议：按段落切分，或调小分段长度后重新切分。
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
          <el-tag size="small" effect="plain" :type="seg.embedding === '已向量化' ? 'success' : 'info'">
            {{ seg.embedding }}
          </el-tag>
        </div>

        <p class="sg-content">{{ seg.content }}</p>

        <div class="sg-fail" v-if="seg.charCount > modelLimit">
          该段 {{ seg.charCount }} 字，超过上限约 {{ modelLimit }} 字，将被向量模型截断。建议：按段落切分为多段，或调小分段长度。
        </div>

        <div class="sg-foot">
          <span class="micro muted">#{{ seg.ordinal }} · <b class="num">{{ seg.charCount }}</b> 字</span>
          <span class="grow" />
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

      <div v-if="!loading && !list.length" class="empty panel">
        <div class="empty-title">
          {{ query.onlyOversized ? '没有超长分段' : '该筛选下没有分段' }}
        </div>
        <p class="micro muted">调整筛选条件，或从文档页导入新内容</p>
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

    <SegmentEditDialog ref="editRef" :model-limit="modelLimit" @saved="fetchList" />

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

const props = defineProps<{ kbId: string; initialDocumentId?: string }>()

const session = useSessionStore()
const canEdit = computed(() => session.can(PERM.segmentEdit))

const list = ref<Segment[]>([])
const total = ref(0)
const loading = ref(false)
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

const fetchList = async () => {
  loading.value = true
  try {
    const result = await ingestionApi.listSegments({
      documentId: query.documentId || undefined,
      onlyOversized: query.onlyOversized || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total

    // 超长告警：用 onlyOversized 查一次拿总数（契约里 Segment 没有 oversized 字段）
    const over = await ingestionApi.listSegments({
      documentId: query.documentId || undefined,
      onlyOversized: true,
      page: 1,
      size: 1
    })
    oversizedCount.value = over.total
  } finally {
    loading.value = false
  }
}

const fetchDocs = async () => {
  const result = await ingestionApi.listDocuments(props.kbId, { page: 1, size: 100 })
  docOptions.value = result.list
}

/** 从切分预览拿 modelLimit（契约里 Segment 本身不带该字段） */
const fetchModelLimit = async () => {
  try {
    const preview = await ingestionApi.previewSegmentation({
      documentId: query.documentId || null,
      segmentLength: 500,
      overlap: 60,
      strategy: 'auto'
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

const toggleEnabled = async (seg: Segment) => {
  const next = !seg.enabled
  await ingestionApi.setSegmentEnabled(seg.id, next)
  ElMessage.success(next ? '已启用，参与检索' : '已停用，不再参与检索')
  await fetchList()
}

const remove = async (seg: Segment) => {
  await ElMessageBox.confirm(
    '删除后不可恢复。确认删除该分段？',
    '确认删除分段',
    { type: 'warning', confirmButtonText: '删除' }
  )
  await ingestionApi.deleteSegment(seg.id)
  ElMessage.success('已删除')
  await fetchList()
}

const batchRemove = async () => {
  await ElMessageBox.confirm(
    `将删除选中的 ${selectedIds.value.length} 个分段，且不可恢复。确认？`,
    '批量删除分段',
    { type: 'warning', confirmButtonText: '删除' }
  )
  for (const id of selectedIds.value) {
    await ingestionApi.deleteSegment(id)
  }
  ElMessage.success(`已删除 ${selectedIds.value.length} 个分段`)
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
    ElMessage.error(error instanceof ApiError ? error.message : '迁移失败')
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

onMounted(() => {
  fetchDocs()
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
  font-size: var(--text-2xs);
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

.pager {
  display: flex;
  justify-content: flex-end;
}

.move-hint {
  margin-top: var(--space-xs);
  line-height: var(--leading-body);
}
</style>
