<template>
  <div class="settings-panel">
    <div class="panel form-panel">
      <h3 class="sec-title">基本信息</h3>
      <div v-if="categoryHint" class="notice warn">{{ categoryHint }}</div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" class="form">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="30" show-word-limit :disabled="!canManage" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" style="width: 220px" :disabled="!canManage">
            <el-option v-for="c in categoryOptions" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="图标">
          <div class="icon-picks">
            <button
              v-for="ic in ICONS"
              :key="ic"
              type="button"
              class="icon-pick"
              :class="{ on: form.icon === ic }"
              :disabled="!canManage"
              @click="form.icon = ic"
            >{{ ic }}</button>
          </div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" show-word-limit :disabled="!canManage" />
        </el-form-item>
        <el-form-item label="导入类型">
          <el-tag size="small" effect="plain">{{ kb.importType === 'LOCAL' ? '本地文档' : '在线抓取' }}</el-tag>
          <span class="hint micro">创建后不可更改（BR-03-01）</span>
        </el-form-item>
        <el-form-item label="向量模型">
          <div class="embedding">
            <span>{{ kb.embeddingModelName }}</span>
            <el-tag v-if="kb.embeddingLocked" size="small" type="warning" effect="plain">已锁定</el-tag>
          </div>
          <div class="hint micro">
            <template v-if="kb.embeddingLocked">
              已有向量化内容，向量模型不可更换（KB-I4）。如需更换请新建知识库。
            </template>
            <template v-else>
              尚无向量化内容，可在下方更换。维度与向量表绑定。
            </template>
          </div>
          <el-select
            v-if="!kb.embeddingLocked && canManage"
            v-model="form.embeddingModelId"
            style="width: 280px; margin-top: 6px"
            placeholder="更换向量模型"
          >
            <el-option v-for="m in embeddingModels" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canManage">
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="panel form-panel">
      <h3 class="sec-title">检索参数</h3>
      <el-form label-width="96px" class="form">
        <el-form-item label="检索模式">
          <el-radio-group v-model="retrieval.mode" :disabled="!canManage">
            <!-- ★ value 必须是**机器码**（契约 RetrievalMode）：它是"选中判定 + 写回表单"双重依据，
                 写中文标签的后果是"打开时不高亮 + 保存必 400"（#28）。展示文案留在按钮文字里。 -->
            <el-radio-button value="VECTOR">向量</el-radio-button>
            <el-radio-button value="FULLTEXT">全文</el-radio-button>
            <el-radio-button value="HYBRID">混合</el-radio-button>
          </el-radio-group>
          <div class="hint micro">混合模式用 RRF 融合向量与全文结果，召回更稳</div>
        </el-form-item>
        <el-form-item label="相似度阈值">
          <el-slider v-model="retrieval.threshold" :min="0" :max="1" :step="0.05" show-input :disabled="!canManage" style="width: 280px" />
          <div class="hint micro">先过滤后取 TopN —— 顺序反了会把噪声带进上下文</div>
        </el-form-item>
        <el-form-item label="TopN">
          <el-input-number v-model="retrieval.topN" :min="1" :max="20" :disabled="!canManage" />
        </el-form-item>
        <el-form-item label="无引用策略">
          <el-radio-group v-model="retrieval.noRefStrategy" :disabled="!canManage">
            <!-- ★ 同上：机器码（契约 NoRefStrategy），不是中文标签 -->
            <el-radio-button value="REFUSE">拒答</el-radio-button>
            <el-radio-button value="FREE_ANSWER">自由回答</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="canManage">
          <el-button type="primary" :loading="savingRetrieval" @click="saveRetrieval">保存检索参数</el-button>
          <el-button @click="loadRetrieval">还原</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="panel form-panel danger-zone">
      <h3 class="sec-title">启停与删除</h3>
      <div class="row gap-12">
        <el-button v-if="canManage" size="small" @click="toggleEnabled">
          {{ kb.enabled ? '停用知识库' : '启用知识库' }}
        </el-button>
        <el-button v-if="canManage" size="small" type="danger" @click="remove">删除知识库</el-button>
        <span class="hint micro">删除会连同文档与分段一并移除，不可恢复</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { knowledgeApi, modelsApi, retrievalApi } from '@/api'
import { ApiError } from '@/api/types'
import type { KnowledgeBase } from '@/api/knowledge'
import type { ModelInfo } from '@/api/models'
import type { RetrievalParams } from '@/api/retrieval'
import { useDictionary } from '@/composables/useDictionary'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'

const props = defineProps<{ kb: KnowledgeBase }>()
const emit = defineEmits<{ changed: []; deleted: []; dirtyChange: [dirty: boolean] }>()

const session = useSessionStore()
const canManage = computed(() => session.can(PERM.knowledgeManage))

const ICONS = ['📘', '📗', '📙', '📕', '📒', '📂', '💼', '🏛']

const formRef = ref<FormInstance>()
const saving = ref(false)
const savingRetrieval = ref(false)
const embeddingModels = ref<ModelInfo[]>([])

const form = reactive({
  name: '',
  category: '',
  icon: '📘',
  description: '',
  embeddingModelId: ''
})

/** 库分类取自基础数据字典（08 §十）—— 不再是"已有库的分类投影"（那是列表页筛选要的） */
const { items: categories, hint: categoryHint, load: loadCategories } = useDictionary('KB_CATEGORY', '库分类')

/**
 * 下拉选项 = 字典里的启用项 + **这个库当前的分类**（若字典里查不到）。
 *
 * ★ 后一半不能省：分类可能已被停用（读接口不带停用项），而它是这个库**已经在用**的值 ——
 *   少了它，设置页会变成一个没选中项的空下拉，看起来像"这个库没有分类"（既有数据必须照旧可读）。
 */
const categoryOptions = computed<Array<{ code: string; label: string }>>(() => {
  if (!form.category || categories.value.some((c) => c.code === form.category)) return categories.value
  return [...categories.value, { code: form.category, label: form.category }]
})

const retrieval = reactive<RetrievalParams>({
  mode: 'HYBRID',
  threshold: 0.6,
  topN: 5,
  noRefStrategy: 'REFUSE'
})

/** P1 设置面板改动静默丢失：脏标记上报给页面壳，切 Tab 前确认 */
const snapshot = ref('')
const dirty = ref(false)

const takeSnapshot = () => {
  snapshot.value = JSON.stringify({
    form: { ...form },
    retrieval: { ...retrieval }
  })
  if (dirty.value) {
    dirty.value = false
    emit('dirtyChange', false)
  }
}

const checkDirty = () => {
  const now = JSON.stringify({ form: { ...form }, retrieval: { ...retrieval } })
  const next = now !== snapshot.value
  if (next !== dirty.value) {
    dirty.value = next
    emit('dirtyChange', next)
  }
}

const rules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }]
}

const loadForm = () => {
  form.name = props.kb.name
  form.category = props.kb.category
  form.icon = props.kb.icon
  form.description = props.kb.description
  form.embeddingModelId = props.kb.embeddingModelId
  takeSnapshot()
}

const save = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await knowledgeApi.updateKnowledgeBase(props.kb.id, {
      name: form.name,
      category: form.category,
      icon: form.icon,
      description: form.description,
      embeddingModelId: form.embeddingModelId
    })
    ElMessage.success('已保存')
    takeSnapshot()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const loadRetrieval = async () => {
  const params = await retrievalApi.getRetrievalParams(props.kb.id)
  retrieval.mode = params.mode
  retrieval.threshold = params.threshold
  retrieval.topN = params.topN
  retrieval.noRefStrategy = params.noRefStrategy
  takeSnapshot()
}

const saveRetrieval = async () => {
  savingRetrieval.value = true
  try {
    await retrievalApi.updateRetrievalParams(props.kb.id, {
      mode: retrieval.mode,
      threshold: retrieval.threshold,
      topN: retrieval.topN,
      noRefStrategy: retrieval.noRefStrategy
    })
    ElMessage.success('检索参数已保存')
    takeSnapshot()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    savingRetrieval.value = false
  }
}

const toggleEnabled = async () => {
  const next = !props.kb.enabled
  if (!next) {
    try {
      await ElMessageBox.confirm(
        '停用后该知识库不再参与检索。确认停用？',
        '确认停用知识库',
        { type: 'warning', confirmButtonText: '停用' }
      )
    } catch {
      return
    }
  }
  try {
    await knowledgeApi.setKnowledgeBaseEnabled(props.kb.id, next)
    ElMessage.success(next ? '已启用' : '已停用')
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

const remove = async () => {
  try {
    await ElMessageBox.confirm(
      `删除「${props.kb.name}」将一并移除其全部文档与分段，且不可恢复。确认删除？`,
      '确认删除知识库',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch {
    return
  }
  try {
    await knowledgeApi.deleteKnowledgeBase(props.kb.id)
    ElMessage.success('已删除')
    emit('deleted')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

// P1 脏标记：任何表单改动都算 dirty
watch([form, retrieval], checkDirty, { deep: true })

watch(() => props.kb.id, loadForm)

onMounted(async () => {
  loadForm()
  await loadCategories()
  const models = await modelsApi.listModels({ kind: 'EMBEDDING', size: 50 })
  embeddingModels.value = models.list
  await loadRetrieval()
})
</script>

<style scoped>
.settings-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.form-panel {
  padding: var(--space-md) var(--space-lg);
}

.sec-title {
  margin: 0 0 var(--space-md);
  font-size: var(--text-md);
  font-weight: 500;
  line-height: var(--leading-tight);
  color: var(--color-ink);
}

.form {
  max-width: 560px;
}

.hint {
  color: var(--color-muted);
  line-height: var(--leading-body);
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

.icon-pick:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.embedding {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--text-sm);
  line-height: var(--leading-tight);
}

.danger-zone {
  border-color: color-mix(in oklch, var(--color-danger) 35%, var(--color-rule));
}

.danger-zone .sec-title {
  color: var(--color-danger);
}
</style>
