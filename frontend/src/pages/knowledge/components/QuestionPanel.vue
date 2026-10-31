<template>
  <div class="qa-panel">
    <!-- ★★ 这一条必须显眼：本期检索侧一行不接（#78 的裁定）。
         不写在界面上，用户建完会以为"检索已经用上了" —— 那是本仓最忌讳的静默期待。 -->
    <div class="notice info">
      <b>问答对是「问题 → 答案」的人工标定，用来补纯语义检索的召回不足。</b>
      <span class="micro">
        ★ 当前版本<strong>检索还不会消费它</strong>（数据与配置先落地）。建好的问答对不会改变问答结果 ——
        这一点在检索接入前会在界面上一直标着。
      </span>
    </div>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        size="small"
        placeholder="筛选问题 / 答案"
        clearable
        style="width: 240px"
      />
      <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      <span class="grow" />
      <el-button v-if="canEdit" size="small" @click="openBatch">批量新建</el-button>
      <el-button v-if="canEdit" size="small" type="primary" @click="openCreate">新建问答对</el-button>
    </div>

    <div v-if="loadError" class="notice err">
      <div><b>加载失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" style="margin-top: var(--space-xs)" @click="fetchList">重试</el-button>
    </div>

    <el-table v-loading="loading" :data="filtered" size="small" class="qa-table">
      <!-- 空态给出口：没有问答对时只说「还没有」不告诉用户能做什么 -->
      <template #empty>
        <div class="empty">
          <div class="empty-title">还没有问答对</div>
          <div>问答对让检索优先命中人工确认过的答案</div>
          <el-button v-if="canEdit" size="small" type="primary" @click="openCreate">新建问答对</el-button>
        </div>
      </template>
      <el-table-column label="问题" min-width="220">
        <template #default="{ row }">
          <div class="q-text">{{ row.question }}</div>
        </template>
      </el-table-column>
      <el-table-column label="答案" min-width="280">
        <template #default="{ row }">
          <div class="a-text">{{ row.answer }}</div>
        </template>
      </el-table-column>
      <el-table-column label="出处" width="120">
        <template #default="{ row }">
          <!-- ★ 出处为空是**合法**的：人工导入的问答对可以没有出处（V31 的注释） -->
          <span v-if="row.segmentId" class="mono micro">{{ shortId(row.segmentId) }}</span>
          <span v-else class="micro muted">无出处</span>
        </template>
      </el-table-column>
      <el-table-column label="密级" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ CLASSIFICATION_LABELS[row.classification] || row.classification }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="110">
        <template #default="{ row }">
          <!-- ★ 来源决定"这条凭什么可信、被质疑时怎么追溯"，所以要显示出来 -->
          <span class="micro">{{ ORIGIN_LABELS[row.origin] || row.origin }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="canEdit" label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" @click="openRename(row)">改名</el-button>
          <el-button link size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        small
        @current-change="fetchList"
      />
    </div>

    <!-- 新建单条 -->
    <el-dialog v-model="createVisible" title="新建问答对" width="560px">
      <el-form label-width="72px">
        <el-form-item label="问题" required>
          <el-input v-model="form.question" type="textarea" :rows="2" placeholder="用户真正会问出来的那句话" />
        </el-form-item>
        <el-form-item label="答案" required>
          <el-input v-model="form.answer" type="textarea" :rows="4" placeholder="人工标定的那个回答" />
        </el-form-item>
        <el-form-item label="密级" required>
          <el-select v-model="form.classification" style="width: 200px">
            <el-option v-for="c in CLASSIFICATIONS" :key="c" :label="CLASSIFICATION_LABELS[c]" :value="c" />
          </el-select>
          <div class="micro muted">不得高于所属知识库的密级上限</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量新建：一行一个问题 -->
    <el-dialog v-model="batchVisible" title="批量新建（一行一条）" width="620px">
      <p class="micro">
        每行一条，格式 <code>问题 | 答案</code>（用竖线分隔）。★ 一批要么全进、要么一条都不进 ——
        半批成功会让"重试会不会重复"变得说不清。
      </p>
      <el-input
        v-model="batchText"
        type="textarea"
        :rows="10"
        placeholder="出差住宿费上限是多少 | 一线城市每晚上限 600 元&#10;发票丢了还能报销吗 | 需提供情况说明并由部门负责人签字"
      />
      <el-form label-width="72px" style="margin-top: var(--space-sm)">
        <el-form-item label="密级" required>
          <el-select v-model="batchClassification" style="width: 200px">
            <el-option v-for="c in CLASSIFICATIONS" :key="c" :label="CLASSIFICATION_LABELS[c]" :value="c" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="batchVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitBatch">导入</el-button>
      </template>
    </el-dialog>

    <!-- 改名 -->
    <el-dialog v-model="renameVisible" title="改问题" width="520px">
      <el-input v-model="renameText" type="textarea" :rows="2" />
      <p class="micro muted">★ 只改问题：答案 / 出处 / 密级各有各的入口，不会被这次改名影响。</p>
      <template #footer>
        <el-button size="small" @click="renameVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  batchCreateQaPairs,
  createQaPair,
  deleteQaPair,
  listQaPairs,
  renameQaPair,
  type QaPair,
  type QaPairClassification
} from '@/api/qaPairs'
import { ApiError } from '@/api/types'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'

const props = defineProps<{ kbId: string }>()

// ★ 与 SegmentPanel 同一口径：权限点走 session.can，不靠父组件传布尔值
const session = useSessionStore()
const canEdit = computed(() => session.can(PERM.qaEdit))

const CLASSIFICATIONS: QaPairClassification[] = ['PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL']
const CLASSIFICATION_LABELS: Record<string, string> = {
  PUBLIC: '公开',
  INTERNAL: '内部',
  SECRET: '秘密',
  CONFIDENTIAL: '机密'
}
const ORIGIN_LABELS: Record<string, string> = {
  MANUAL: '人工导入',
  CONVERSATION: '会话沉淀',
  FEEDBACK: '赞踩沉淀',
  DOCUMENT: '文档加工'
}

const loading = ref(false)
const saving = ref(false)
const list = ref<QaPair[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loadError = ref<{ reason: string; hint?: string } | null>(null)

const createVisible = ref(false)
const batchVisible = ref(false)
const renameVisible = ref(false)
const renameText = ref('')
const renameId = ref('')
const batchText = ref('')
const batchClassification = ref<QaPairClassification>('INTERNAL')
const form = reactive<{
  question: string
  answer: string
  classification: QaPairClassification
}>({ question: '', answer: '', classification: 'INTERNAL' })

/**
 * ★ 前端筛选只作用于**当前页**。全量搜索要后端支持 keyword —— 那是另一批的事，
 * 这里不假装支持（否则用户会以为搜的是全部）。
 */
const filtered = computed(() => {
  const k = keyword.value.trim()
  if (!k) return list.value
  return list.value.filter((row) => row.question.includes(k) || row.answer.includes(k))
})

const shortId = (id: string) => id.slice(0, 8)

async function fetchList() {
  loading.value = true
  loadError.value = null
  try {
    const res = await listQaPairs(props.kbId, { page: page.value, size: size.value })
    list.value = res.list
    total.value = res.total
  } catch (e) {
    const err = e as ApiError
    loadError.value = { reason: err.message || '未知错误', hint: err.hint }
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.question = ''
  form.answer = ''
  form.classification = 'INTERNAL'
  createVisible.value = true
}

async function submitCreate() {
  if (!form.question.trim() || !form.answer.trim()) {
    ElMessage.warning('问题和答案都必填')
    return
  }
  saving.value = true
  try {
    await createQaPair(props.kbId, {
      question: form.question,
      answer: form.answer,
      classification: form.classification
    })
    ElMessage.success('已创建')
    createVisible.value = false
    await fetchList()
  } catch (e) {
    const err = e as ApiError
    // ★ 把 hint 也显示出来：只说"失败了"等于让用户猜
    ElMessage.error(err.hint ? `${err.message}（${err.hint}）` : err.message)
  } finally {
    saving.value = false
  }
}

function openBatch() {
  batchText.value = ''
  batchClassification.value = 'INTERNAL'
  batchVisible.value = true
}

async function submitBatch() {
  const rows = batchText.value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
  const items = rows.map((line) => {
    const at = line.indexOf('|')
    return {
      // ★ 没有分隔符 ⇒ 整行当问题、答案为空 ⇒ 后端会以"第 N 条缺少答案"拒绝整批。
      //   这里不替用户猜"答案是什么"。
      question: at < 0 ? line : line.slice(0, at).trim(),
      answer: at < 0 ? '' : line.slice(at + 1).trim(),
      classification: batchClassification.value
    }
  })
  if (!items.length) {
    ElMessage.warning('没有要导入的行')
    return
  }
  saving.value = true
  try {
    const created = await batchCreateQaPairs(props.kbId, items)
    ElMessage.success(`已导入 ${created.length} 条`)
    batchVisible.value = false
    await fetchList()
  } catch (e) {
    const err = e as ApiError
    ElMessage.error(err.hint ? `${err.message}（${err.hint}）` : err.message)
  } finally {
    saving.value = false
  }
}

function openRename(row: QaPair) {
  renameId.value = row.id
  renameText.value = row.question
  renameVisible.value = true
}

async function submitRename() {
  if (!renameText.value.trim()) {
    ElMessage.warning('问题不能为空')
    return
  }
  saving.value = true
  try {
    await renameQaPair(renameId.value, renameText.value)
    ElMessage.success('已改名')
    renameVisible.value = false
    await fetchList()
  } catch (e) {
    const err = e as ApiError
    ElMessage.error(err.hint ? `${err.message}（${err.hint}）` : err.message)
  } finally {
    saving.value = false
  }
}

async function remove(row: QaPair) {
  try {
    await ElMessageBox.confirm(`删除问答对「${row.question}」？`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteQaPair(row.id)
    ElMessage.success('已删除')
    await fetchList()
  } catch (e) {
    const err = e as ApiError
    ElMessage.error(err.hint ? `${err.message}（${err.hint}）` : err.message)
  }
}

onMounted(fetchList)
</script>

<style scoped>
.qa-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.grow {
  flex: 1;
}
.qa-table {
  width: 100%;
}
.q-text {
  font-weight: 600;
}
.a-text {
  white-space: pre-wrap;
  word-break: break-word;
}
.pager {
  display: flex;
  justify-content: flex-end;
}
.mono {
  font-family: var(--font-mono, monospace);
}
</style>
