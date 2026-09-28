<template>
  <div class="body">
    <!-- 左：表单 -->
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px" class="form">
      <!-- ★ 先定连哪个服务，再谈选哪个模型 —— 顺序反了就变成「先选基础模型才谈供应商」 -->
      <el-form-item label="供应商" prop="supplier">
        <el-radio-group v-model="form.supplier" :disabled="supplierLocked" @change="onUpstreamChange">
          <el-radio-button
            v-for="s in SUPPLIER_VALUES" :key="s"
            :value="s"
          >{{ labelOf(SUPPLIER_LABELS, s) }}</el-radio-button>
        </el-radio-group>
        <div class="hint micro">
          {{ form.supplier === 'EXTERNAL_API'
            ? '外部 API：必须给出服务地址，目录从该服务拉取'
            : '本地推理：走本机默认推理服务' }}
        </div>
      </el-form-item>

      <el-form-item label="服务地址" prop="apiBase">
        <el-input
          v-model="form.apiBase" clearable
          :placeholder="form.supplier === 'EXTERNAL_API' ? '必填，例如 https://api.openai.com/v1' : '选填'"
          @change="onUpstreamChange"
        />
        <div class="hint micro">
          {{ form.supplier === 'EXTERNAL_API'
            ? '外部 API 的推理服务地址是必填项'
            : '留空 = 使用默认服务' }}
        </div>
      </el-form-item>

      <el-form-item label="密钥" prop="apiKey">
        <el-input
          v-model="form.apiKey" type="password" show-password clearable
          placeholder="留空则不修改"
          @change="onUpstreamChange"
        />
        <div class="hint micro">只写不回显：保存后不再展示，留空表示不改动已有密钥</div>
      </el-form-item>

      <el-form-item label="模型类型" prop="kind">
        <el-radio-group v-model="form.kind" :disabled="isEdit" @change="onKindChange">
          <el-radio-button
            v-for="k in MODEL_KIND_VALUES" :key="k"
            :value="k"
          >{{ labelOf(MODEL_KIND_LABELS, k) }}</el-radio-button>
        </el-radio-group>
        <div v-if="isEdit" class="hint micro">创建后不可更改类型</div>
      </el-form-item>

      <el-form-item label="基础模型" prop="baseModel">
        <!-- 手填模式：目录里没有时的逃生口 —— 后端创建时不校验真伪，加载时才验 -->
        <div v-if="baseManualMode && !isEdit" class="manual-base">
          <div class="manual-base-row">
            <el-input
              v-model="manualBaseName"
              clearable
              placeholder="输入基础模型名，例如 my-custom-model"
              @update:model-value="onManualBaseInput"
            />
            <el-tag size="small" effect="plain" type="warning" class="source-tag">
              来源：{{ labelOf(VALUE_SOURCE_LABELS, baseSource) }}
            </el-tag>
          </div>
          <div class="hint micro">
            手填的基础模型在加载时才会验证 —— 上游没有它会加载失败并给出原因，不阻断录入
          </div>
        </div>
        <div v-else-if="form.baseModel" class="picked">
          <div class="picked-main">
            <div class="picked-name-row">
              <div class="picked-name">{{ form.baseModel }}</div>
              <el-tag size="small" effect="plain" :type="baseSource === 'MANUAL' ? 'warning' : 'info'" class="source-tag">
                来源：{{ labelOf(VALUE_SOURCE_LABELS, baseSource) }}
              </el-tag>
            </div>
            <div class="micro muted">
              {{ labelOf(SUPPLIER_LABELS, form.supplier) }} ·
              <template v-if="form.kind === 'EMBEDDING'">
                {{ pickedBase?.dimension ?? form.dimension ?? '—' }} 维
              </template>
              <template v-else>
                {{ pickedBase?.contextWindow?.toLocaleString() ?? '—' }} tokens
              </template>
            </div>
          </div>
          <el-button v-if="!isEdit" link size="small" @click="clearBase">移除</el-button>
        </div>
        <div v-else class="picked empty micro muted">从右侧目录中选择，或在目录区手动填写</div>
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
              来源：{{ labelOf(MODEL_CAPABILITY_SOURCE_LABELS, capSource) }}
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

      <el-form-item v-else label="向量维度" prop="dimension">
        <div class="dim">
          <el-input
            v-model="dimText"
            class="dim-input"
            placeholder="正整数，例如 768"
            :disabled="isEdit"
            @update:model-value="onDimInput"
          />
          <el-tag
            size="small" effect="plain"
            :type="dimSource === 'MANUAL' ? 'warning' : dimSource === 'PROBED' ? 'success' : 'info'"
            class="source-tag"
          >
            来源：{{ labelOf(VALUE_SOURCE_LABELS, dimSource) }}
          </el-tag>
        </div>
        <div class="hint micro">维度与向量表绑定，创建后不可更改；不确定先用探测</div>
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

        <!-- 空态 ≠ 只说「没有匹配」：上游不可达时要给能照着排查的那句话 -->
        <div v-if="!loadingBase && !filteredBase.length" class="catalog-empty">
          <div class="micro">{{ catalogEmptyTitle }}</div>
          <div v-if="catalogHint" class="micro muted catalog-hint">{{ catalogHint }}</div>
        </div>
      </div>

      <!-- 目录里没有？手动填写 —— 后端 baseModel 就是字符串，创建不校验，加载时才验 -->
      <div class="catalog-foot">
        <template v-if="!baseManualMode">
          <el-button
            v-if="!isEdit" link type="primary" size="small"
            class="manual-escape"
            @click="enableManualBase"
          >
            目录里没有？手动填写
          </el-button>
        </template>
        <template v-else>
          <div class="micro manual-mode-hint">正在手动填写基础模型</div>
          <el-button v-if="!isEdit" link size="small" @click="disableManualBase">返回目录选择</el-button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
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
import {
  MODEL_CAPABILITY_SOURCE_LABELS,
  MODEL_KIND_LABELS,
  MODEL_KIND_VALUES,
  SUPPLIER_LABELS,
  SUPPLIER_VALUES,
  VALUE_SOURCE_LABELS,
  labelOf
} from '@/constants/labels'

/** 基础模型 / 维度的取值来源：目录带出 / 探测 / 手填 —— 界面必须标出来 */
type ValueSource = 'CATALOG' | 'PROBED' | 'MANUAL'

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
/** 包络 hint：上游不可达/为空时后端给的可读排查提示 */
const catalogHint = ref('')
const capSource = ref<'PROBED' | 'MANUAL'>('MANUAL')
/** 目录里没有？手动填写 —— 逃生口开关 */
const baseManualMode = ref(false)
const manualBaseName = ref('')
const baseSource = ref<ValueSource>('CATALOG')
const dimSource = ref<ValueSource>('CATALOG')
/** 维度输入框的原文（正整数校验吃字符串，0/负数/非数字都要能被拦） */
const dimText = ref('')

const isEdit = computed(() => !!props.model)
/** 编排画布已预选供应商时不许改，否则预筛会被用户改穿 */
const supplierLocked = computed(() => !!props.supplier)

const form = reactive({
  kind: 'CHAT' as ModelKind,
  supplier: 'LOCAL_INFERENCE' as Supplier,
  /** 外部 API 必填；本地推理可留空 = 默认服务 */
  apiBase: '',
  /** 只写不回显：编辑时永远不回填 */
  apiKey: '',
  baseModel: '',
  name: '',
  thinking: 'NONE' as ThinkingMode,
  tools: 'UNSUPPORTED' as ToolsMode,
  dimension: null as number | null,
  remark: ''
})

const rules: FormRules = {
  kind: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
  baseModel: [{ required: true, message: '请选择或填写基础模型', trigger: 'change' }],
  name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  thinking: [{ required: true, message: '请选择思考模式', trigger: 'change' }],
  tools: [{ required: true, message: '请选择工具调用能力', trigger: 'change' }],
  dimension: [
    {
      validator: (_rule, _value, callback) => {
        if (form.kind !== 'EMBEDDING') {
          callback()
          return
        }
        const raw = dimText.value.trim()
        if (!raw) {
          callback(new Error('请填写向量维度'))
          return
        }
        const n = Number(raw)
        if (!Number.isInteger(n) || n <= 0) {
          callback(new Error('向量维度必须是正整数'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  apiBase: [
    {
      validator: (_rule, value, callback) => {
        if (form.supplier === 'EXTERNAL_API' && !String(value ?? '').trim()) {
          callback(new Error('外部API 必须填写服务地址'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const pickedBase = computed(() => baseList.value.find((b) => b.baseModel === form.baseModel))

const filteredBase = computed(() =>
  baseList.value
    .filter((b) => b.kind === form.kind)
    .filter((b) => !props.supplier || b.supplier === props.supplier)
    .filter((b) => !baseKeyword.value || b.baseModel.toLowerCase().includes(baseKeyword.value.toLowerCase()))
)

const catalogEmptyTitle = computed(() => {
  if (baseKeyword.value && baseList.value.length) return '没有匹配的基础模型'
  return catalogHint.value ? '未从上游拉到模型' : '没有匹配的基础模型'
})

const thinkingHint = computed(
  () => THINKING_OPTIONS.find((o) => o.value === form.thinking)?.hint ?? ''
)

const loadBaseModels = async () => {
  loadingBase.value = true
  catalogHint.value = ''
  try {
    baseList.value = await modelsApi.listBaseModels(
      {
        kind: form.kind,
        supplier: form.supplier,
        // ★ 把当前表单的上游地址/密钥传进去 —— 目录跟着所连服务走
        ...(form.apiBase.trim() ? { apiBase: form.apiBase.trim() } : {}),
        ...(form.apiKey ? { apiKey: form.apiKey } : {}),
        ...(props.supplier ? { supplier: props.supplier } : {})
      },
      (hint) => {
        catalogHint.value = hint ?? ''
      }
    )
  } catch (error) {
    baseList.value = []
    catalogHint.value =
      error instanceof ApiError && error.hint
        ? error.hint
        : error instanceof ApiError
          ? error.message
          : '未从上游拉到模型：请检查服务地址是否正确、服务是否已启动'
  } finally {
    loadingBase.value = false
  }
}

/** 供应商 / 服务地址 / 密钥 任一变更 ⇒ 重新拉目录 */
const onUpstreamChange = () => {
  // 换了上游，原来选中的基础模型不一定还在新目录里
  if (!isEdit.value) clearBase()
  loadBaseModels()
}

// 文本框连续输入走防抖，避免每敲一个字符拉一次
let upstreamTimer: ReturnType<typeof setTimeout> | undefined
watch(
  () => [form.apiBase, form.apiKey] as const,
  () => {
    if (isEdit.value && form.baseModel) {
      // 编辑态基础模型锁定，但目录仍要跟着上游变（方便核对）
    } else if (!isEdit.value) {
      clearBase()
    }
    clearTimeout(upstreamTimer)
    upstreamTimer = setTimeout(() => loadBaseModels(), 400)
  }
)

const pickBase = (item: BaseModelOption) => {
  // 从目录点选 ⇒ 来源是目录；先退出手填模式，避免两套输入打架
  baseManualMode.value = false
  manualBaseName.value = ''
  form.baseModel = item.baseModel
  baseSource.value = 'CATALOG'
  // ★ supplier 由用户在顶部先选定，不再从目录项反推
  form.dimension = item.kind === 'EMBEDDING' ? item.dimension : null
  dimText.value = item.kind === 'EMBEDDING' && item.dimension != null ? String(item.dimension) : ''
  dimSource.value = item.kind === 'EMBEDDING' && item.dimension != null ? 'CATALOG' : 'MANUAL'
  // 换基础模型等于换了模型，能力声明必须重来，避免把上一个模型的能力带过去
  form.thinking = 'NONE'
  form.tools = 'UNSUPPORTED'
  capSource.value = 'MANUAL'
  if (!form.name) form.name = item.baseModel
}

const clearBase = () => {
  form.baseModel = ''
  form.dimension = null
  dimText.value = ''
  baseSource.value = 'CATALOG'
  dimSource.value = 'MANUAL'
  manualBaseName.value = ''
  // 保留 baseManualMode：清掉已填内容后仍可继续手填
}

const enableManualBase = () => {
  baseManualMode.value = true
  // 手填时 contextWindow / dimension 无从带出 —— 用户自行决定填不填
  form.baseModel = manualBaseName.value.trim()
  baseSource.value = 'MANUAL'
  form.dimension = null
  dimText.value = ''
  dimSource.value = 'MANUAL'
  form.thinking = 'NONE'
  form.tools = 'UNSUPPORTED'
  capSource.value = 'MANUAL'
}

const disableManualBase = () => {
  baseManualMode.value = false
  manualBaseName.value = ''
  form.baseModel = ''
  form.dimension = null
  dimText.value = ''
  baseSource.value = 'CATALOG'
  dimSource.value = 'MANUAL'
}

const onManualBaseInput = (val: string) => {
  manualBaseName.value = val
  form.baseModel = val.trim()
  baseSource.value = 'MANUAL'
  // 手填换名 = 换模型，能力重来
  form.thinking = 'NONE'
  form.tools = 'UNSUPPORTED'
  capSource.value = 'MANUAL'
}

const onDimInput = (val: string) => {
  dimText.value = val
  dimSource.value = 'MANUAL'
  const raw = val.trim()
  if (!raw) {
    form.dimension = null
    return
  }
  const n = Number(raw)
  form.dimension = Number.isFinite(n) ? n : null
}

const onKindChange = () => {
  clearBase()
  form.thinking = 'NONE'
  form.tools = 'UNSUPPORTED'
  dimText.value = ''
  dimSource.value = 'MANUAL'
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
    // ★ 维度也从探测带出，并标「探测」—— 与手填/目录分得开
    if (form.kind === 'EMBEDDING' && capability.dimension != null) {
      form.dimension = capability.dimension
      dimText.value = String(capability.dimension)
      dimSource.value = 'PROBED'
    }
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
  form.apiBase = ''
  // 密钥永不回显 —— 连编辑态也不回填
  form.apiKey = ''
  form.baseModel = ''
  form.name = ''
  form.thinking = 'NONE'
  form.tools = 'UNSUPPORTED'
  form.dimension = null
  form.remark = ''
  baseKeyword.value = ''
  catalogHint.value = ''
  capSource.value = 'MANUAL'
  baseManualMode.value = false
  manualBaseName.value = ''
  baseSource.value = 'CATALOG'
  dimSource.value = 'MANUAL'
  dimText.value = ''
}

const load = async () => {
  reset()
  if (props.model) {
    const m = props.model
    // 先落定 kind/供应商再拉目录 —— 否则目录按新建默认的 CHAT 拉，编辑向量模型时右侧全空
    form.kind = m.kind
    form.supplier = m.supplier
    form.baseModel = m.baseModel
    form.name = m.name
    form.thinking = m.thinking
    form.tools = m.tools
    form.dimension = m.dimension
    form.remark = m.remark ?? ''
    dimText.value = m.dimension != null ? String(m.dimension) : ''
    // 编辑已有模型：值来自库，不是本次手填；仍标「目录」以外的来源会让用户误解，
    // 这里统一按「目录/带出」展示（编辑态不可改基础模型与维度）。
    baseSource.value = 'CATALOG'
    dimSource.value = 'CATALOG'
    // ★ 编辑态：apiKey 不回填（契约 ModelInfo 本就不含密钥）；apiBase 可改但不强制重填
    form.apiKey = ''
    capSource.value = 'MANUAL'
  }
  await loadBaseModels()
}

const submit = async (): Promise<boolean> => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return false

  saving.value = true
  try {
    // 密钥只写不回显：留空 = 不修改，绝不把空串当成「清空密钥」下发
    const upstream: Partial<UpsertModelBody> = {
      ...(form.apiBase.trim() ? { apiBase: form.apiBase.trim() } : {}),
      ...(form.apiKey ? { apiKey: form.apiKey } : {})
    }
    if (isEdit.value && props.model) {
      await modelsApi.updateModel(props.model.id, {
        name: form.name,
        thinking: form.thinking,
        tools: form.tools,
        remark: form.remark,
        ...upstream
      })
      ElMessage.success('已保存')
    } else {
      // 维度以输入框为准：手填/目录/探测最终都落到 dimText，提交前再解析一次
      let dimension: number | null = null
      if (form.kind === 'EMBEDDING') {
        const raw = dimText.value.trim()
        const n = Number(raw)
        dimension = raw && Number.isInteger(n) && n > 0 ? n : null
      }
      const body: UpsertModelBody = {
        name: form.name,
        kind: form.kind,
        supplier: form.supplier,
        baseModel: form.baseModel,
        dimension,
        remark: form.remark,
        // ★ 向量模型不接受 thinking/tools（后端 400）—— 只在对话模型时下发
        ...(form.kind === 'CHAT'
          ? { thinking: form.thinking, tools: form.tools }
          : {}),
        ...upstream
      }
      await modelsApi.createModel(body)
      ElMessage.success('已保存。在列表中点「加载」即可启用该模型')
    }
    emit('saved')
    return true
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '保存失败，请稍后重试'
    const hint = error instanceof ApiError && error.hint ? ` ${error.hint}` : ''
    ElMessage.error(`${message}${hint}`)
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

.picked-name-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.source-tag {
  flex-shrink: 0;
}

.manual-base {
  width: 100%;
}

.manual-base-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  width: 100%;
}

.manual-base-row .el-input {
  flex: 1;
}

.manual-base .hint {
  margin-top: var(--space-3xs);
}

.cap-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.dim {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--text-xs);
}

.dim-input {
  width: 160px;
}

.catalog-foot {
  padding: var(--space-xs) var(--space-sm);
  border-top: 1px solid var(--color-rule-2);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
}

.manual-mode-hint {
  color: var(--color-muted);
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
