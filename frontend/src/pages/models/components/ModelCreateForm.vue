<template>
  <div class="body">
    <!-- 左：表单 -->
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px" class="form">
      <el-form-item label="模型类型" prop="kind">
        <el-radio-group v-model="form.kind" :disabled="isEdit" @change="onKindChange">
          <el-radio-button value="chat">对话模型</el-radio-button>
          <el-radio-button value="embedding">向量模型</el-radio-button>
        </el-radio-group>
        <div v-if="isEdit" class="hint micro">创建后不可更改类型</div>
      </el-form-item>

      <el-form-item label="基础模型" prop="baseModel">
        <div v-if="form.baseModel" class="picked">
          <div class="picked-main">
            <div class="picked-name">{{ form.baseModel }}</div>
            <div class="micro muted">
              {{ labelOf(SUPPLIER_LABELS, form.supplier) }} ·
              {{ form.kind === 'EMBEDDING' ? `${pickedBase?.dimension} 维` : `${pickedBase?.contextWindow?.toLocaleString()} tokens` }}
            </div>
          </div>
          <el-button v-if="!isEdit" link size="small" @click="clearBase">移除</el-button>
        </div>
        <div v-else class="picked empty micro muted">从右侧目录中选择一个基础模型</div>
        <div v-if="isEdit" class="hint micro">基础模型不可修改；如需更换请新建模型</div>
      </el-form-item>

      <el-form-item label="模型名称" prop="name">
        <el-input
          v-model="form.name" maxlength="30" show-word-limit
          placeholder="给这个模型起个名字，例如 合同审查专用"
        />
      </el-form-item>

      <template v-if="form.kind === 'CHAT'">
        <el-form-item label="能力声明">
          <div class="cap-head">
            <el-tag size="small" effect="plain" :type="capSource === 'PROBED' ? 'success' : 'warning'">
              来源：{{ capSource }}
            </el-tag>
            <el-button size="small" text :icon="MagicStick" :loading="probing" @click="probe">
              自动探测
            </el-button>
          </div>
          <div class="hint micro">
            探测失败不阻断 —— 可以手工声明。界面必须标明来源，否则没人分得清是测出来的还是填的。
          </div>
        </el-form-item>

        <el-form-item label="思考模式" prop="thinking">
          <el-select v-model="form.thinking" style="width: 260px">
            <el-option
              v-for="opt in THINKING_OPTIONS" :key="opt.value"
              :label="opt.label" :value="opt.value"
            >
              <span>{{ opt.label }}</span>
              <span class="opt-hint">{{ opt.hint }}</span>
            </el-option>
          </el-select>
          <div class="hint micro">{{ thinkingHint }}</div>
        </el-form-item>

        <el-form-item label="工具调用" prop="tools">
          <el-select v-model="form.tools" style="width: 260px">
            <el-option v-for="opt in TOOLS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <div class="hint micro">MVP 只声明不接口，V1.0 起才成为工具场景的准入依据</div>
        </el-form-item>
      </template>

      <el-form-item v-else label="向量维度">
        <div class="dim">
          <b class="num">{{ form.dimension ?? pickedBase?.dimension ?? '—' }}</b> 维
          <span class="hint micro">维度与向量表绑定，创建后不可更改</span>
        </div>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" resize="none" maxlength="100" show-word-limit />
      </el-form-item>
    </el-form>

    <!-- 右：基础模型目录 -->
    <div class="catalog">
      <div class="catalog-hd">
        <span>基础模型目录</span>
        <el-input
          v-model="baseKeyword" size="small" placeholder="搜索"
          style="width: 130px" clearable :prefix-icon="Search"
        />
      </div>

      <div v-loading="loadingBase" class="catalog-list">
        <button
          v-for="item in filteredBase" :key="item.baseModel"
          class="catalog-item" :class="{ on: form.baseModel === item.baseModel }"
          :disabled="isEdit"
          @click="pickBase(item)"
        >
          <div class="ci-main">
            <div class="ci-name">{{ item.baseModel }}</div>
            <div class="ci-meta micro muted">
              {{ labelOf(SUPPLIER_LABELS, item.supplier) }} ·
              {{ item.kind === 'EMBEDDING' ? `${item.dimension} 维` : `${item.contextWindow?.toLocaleString()} tokens` }}
            </div>
          </div>
          <el-tag size="small" effect="plain" :type="item.kind === 'EMBEDDING' ? 'warning' : 'info'">
            {{ item.kind === 'EMBEDDING' ? '向量' : '对话' }}
          </el-tag>
        </button>

        <div v-if="!loadingBase && !filteredBase.length" class="catalog-empty micro muted">
          没有匹配的基础模型
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { MagicStick, Search } from '@element-plus/icons-vue'
import { modelsApi } from '@/api'
import { ApiError } from '@/api/types'
import type {
  BaseModelOption,
  ModelInfo,
  ModelKind,
  Supplier,
  ThinkingMode,
  ToolsMode,
  UpsertModelBody
} from '@/api/models'
import { SUPPLIER_LABELS, labelOf } from '@/constants/labels'

const props = defineProps<{
  /** 编辑时传入现有模型；null / 省略 = 新建 */
  model?: ModelInfo | null
  /** 只看某供应商的基础模型（编排画布按「选择供应商」预筛） */
  supplier?: Supplier
}>()
const emit = defineEmits<{ saved: [] }>()

const THINKING_OPTIONS: Array<{ value: ThinkingMode; label: string; hint: string }> = [
  { value: 'NONE', label: '无思考模式', hint: '整项不渲染' },
  { value: 'TOGGLEABLE', label: '可开关', hint: '默认关闭，附说明' },
  { value: 'ALWAYS_ON', label: '强制开启', hint: '置开且禁用，需标注原因' }
]

const TOOLS_OPTIONS: Array<{ value: ToolsMode; label: string }> = [
  { value: 'UNSUPPORTED', label: '不支持工具' },
  { value: 'SUPPORTED', label: '支持工具调用' },
  { value: 'PARALLEL', label: '支持并行工具调用' }
]

const saving = ref(false)
const probing = ref(false)
const formRef = ref<FormInstance>()
const baseKeyword = ref('')
const loadingBase = ref(false)
const baseList = ref<BaseModelOption[]>([])
const capSource = ref<'PROBED' | 'MANUAL'>('MANUAL')

const isEdit = computed(() => !!props.model)

const form = reactive({
  kind: 'chat' as ModelKind,
  supplier: '本地推理' as Supplier,
  baseModel: '',
  name: '',
  thinking: 'NONE' as ThinkingMode,
  tools: 'UNSUPPORTED' as ToolsMode,
  dimension: null as number | null,
  remark: ''
})

const rules: FormRules = {
  kind: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
  baseModel: [{ required: true, message: '请选择基础模型', trigger: 'change' }],
  name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  thinking: [{ required: true, message: '请选择思考模式', trigger: 'change' }],
  tools: [{ required: true, message: '请选择工具调用能力', trigger: 'change' }]
}

const pickedBase = computed(() => baseList.value.find((b) => b.baseModel === form.baseModel))

const filteredBase = computed(() =>
  baseList.value
    .filter((b) => b.kind === form.kind)
    .filter((b) => !props.supplier || b.supplier === props.supplier)
    .filter((b) => !baseKeyword.value || b.baseModel.toLowerCase().includes(baseKeyword.value.toLowerCase()))
)

const thinkingHint = computed(
  () => THINKING_OPTIONS.find((o) => o.value === form.thinking)?.hint ?? ''
)

const loadBaseModels = async () => {
  loadingBase.value = true
  try {
    baseList.value = await modelsApi.listBaseModels({
      kind: form.kind,
      ...(props.supplier ? { supplier: props.supplier } : {})
    })
  } finally {
    loadingBase.value = false
  }
}

const pickBase = (item: BaseModelOption) => {
  form.baseModel = item.baseModel
  form.supplier = item.supplier
  form.dimension = item.kind === 'EMBEDDING' ? item.dimension : null
  // 换基础模型等于换了模型，能力声明必须重来，避免把上一个模型的能力带过去
  form.thinking = 'NONE'
  form.tools = 'UNSUPPORTED'
  capSource.value = 'MANUAL'
  if (!form.name) form.name = item.baseModel
}

const clearBase = () => {
  form.baseModel = ''
  form.dimension = null
}

const onKindChange = () => {
  clearBase()
  form.thinking = 'NONE'
  form.tools = 'UNSUPPORTED'
  loadBaseModels()
}

const probe = async () => {
  if (!form.baseModel) {
    ElMessage.warning('请先选择基础模型')
    return
  }
  probing.value = true
  try {
    const capability = await modelsApi.probeBaseModelCapability({
      baseModel: form.baseModel,
      supplier: form.supplier,
      kind: form.kind
    })
    form.thinking = capability.thinking
    form.tools = capability.tools
    capSource.value = capability.source
    ElMessage.success('已探测到能力，可在下方手工覆盖')
  } catch (error) {
    // 探测失败不阻断流程，但要说清为什么、下一步怎么办
    const message = error instanceof ApiError ? error.message : '探测失败'
    const hint = error instanceof ApiError && error.hint ? error.hint : '请手工声明能力'
    ElMessage.warning({ message: `${message}。${hint}`, duration: 5000 })
  } finally {
    probing.value = false
  }
}

const reset = () => {
  formRef.value?.clearValidate()
  form.kind = 'CHAT'
  form.supplier = props.supplier ?? 'LOCAL_INFERENCE'
  form.baseModel = ''
  form.name = ''
  form.thinking = 'NONE'
  form.tools = 'UNSUPPORTED'
  form.dimension = null
  form.remark = ''
  baseKeyword.value = ''
  capSource.value = 'MANUAL'
}

const load = async () => {
  reset()
  await loadBaseModels()
  if (props.model) {
    const m = props.model
    form.kind = m.kind
    form.supplier = m.supplier
    form.baseModel = m.baseModel
    form.name = m.name
    form.thinking = m.thinking
    form.tools = m.tools
    form.dimension = m.dimension
    form.remark = m.remark ?? ''
    capSource.value = 'MANUAL'
  }
}

const submit = async (): Promise<boolean> => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return false

  saving.value = true
  try {
    if (isEdit.value && props.model) {
      await modelsApi.updateModel(props.model.id, {
        name: form.name,
        thinking: form.thinking,
        tools: form.tools,
        remark: form.remark
      })
      ElMessage.success('已保存')
    } else {
      const body: UpsertModelBody = {
        name: form.name,
        kind: form.kind,
        supplier: form.supplier,
        baseModel: form.baseModel,
        dimension: form.dimension,
        thinking: form.thinking,
        tools: form.tools,
        remark: form.remark
      }
      await modelsApi.createModel(body)
      ElMessage.success('已保存。在列表中点「加载」即可启用该模型')
    }
    emit('saved')
    return true
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '保存失败，请稍后重试'
    ElMessage.error(message)
    return false
  } finally {
    saving.value = false
  }
}

defineExpose({ load, submit, reset })
</script>

<style scoped>
.body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--space-md);
  align-items: start;
}

.form {
  min-width: 0;
}

.hint {
  color: var(--color-muted);
  line-height: var(--leading-body);
  margin-left: 0;
}

.opt-hint {
  float: right;
  color: var(--color-muted);
  font-size: var(--text-2xs);
  margin-left: var(--space-sm);
}

.picked {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
  width: 100%;
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-sunken);
  border-radius: var(--radius);
}

.picked.empty {
  color: var(--color-muted);
  justify-content: flex-start;
}

.picked-name {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.cap-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.dim {
  display: flex;
  align-items: baseline;
  gap: var(--space-2xs);
  font-size: var(--text-xs);
}

.catalog {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  overflow: hidden;
}

.catalog-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-sm);
  border-bottom: 1px solid var(--color-rule-2);
  font-size: var(--text-xs);
  font-weight: 600;
}

.catalog-list {
  height: 380px;
  overflow-y: auto;
  padding: var(--space-xs);
  min-height: 120px;
}

.catalog-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
  width: 100%;
  padding: var(--space-xs) var(--space-xs);
  margin-bottom: var(--space-2xs);
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: border-color var(--dur-micro) var(--ease-out), background var(--dur-micro) var(--ease-out);
}

.catalog-item:hover:not(:disabled) {
  border-color: var(--color-accent-line);
  background: var(--color-hover);
}

.catalog-item.on {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
  box-shadow: inset 2px 0 0 var(--color-accent);
}

.catalog-item:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.ci-main {
  min-width: 0;
}

.ci-name {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
}

.ci-meta {
  margin-top: var(--space-3xs);
}

.catalog-empty {
  padding: var(--space-lg) 0;
  text-align: center;
}
</style>
