/**
 * 按目录声明渲染节点配置表单。
 *
 * ★ 这个组件存在的理由：面板以前是「每个节点手写一遍 el-form」，于是同一个键要写三遍
 *   （契约里一遍、转换层一遍、面板一遍），三处任何一处漂了都不会红（`#335` 就是这么来的）。
 *   现在控件由 `node-types.json` 的 `configFields` 决定，面板只剩「渲染」与「写回」。
 *
 * ★ 三条边界（有意留下的逃生舱，不是没做完）：
 *   1. 不认识的 `type` 会**显示「这个类型还没有控件」**，不会静默画成输入框 ——
 *      静默兜底的代价是「用户以为能填，其实存了一串没人读的字符串」。
 *   2. 布局不归这里管（间距、分组、折叠）。复杂面板（条件分支的两层嵌套、表单字段表）
 *      仍手写，见 `#337` 第二步 B 的逐面板分析。
 *   3. 不处理 `.vue` 之外的联动。`dependsOn` 只表达「候选为空则禁用」，表达不了
 *      「显隐由另一个字段的取值决定」（那种用 `visible` 之类另加，别塞进表达式语言）。
 */
<template>
  <el-form
    :model="model"
    label-position="top"
    require-asterisk-position="right"
    label-width="auto"
    @submit.prevent
  >
    <el-form-item v-for="field in visibleFields" :key="field.key" :label="field.label">
      <!-- ① 选项来自知识库列表 -->
      <el-select
        v-if="field.optionsSource === 'knowledge-bases'"
        :model-value="read(field.key)"
        size="small"
        filterable
        clearable
        placeholder="选择知识库"
        style="width: 100%"
        @change="(value: string | null) => pickDependencySource(field, value)"
      >
        <el-option v-for="kb in kbs" :key="kb.value" :label="kb.label" :value="kb.value" />
      </el-select>

      <!-- ② 选项来自依赖字段选中的那个库；依赖为空则禁用（必然非法的选项不进候选） -->
      <el-select
        v-else-if="field.optionsSource === 'documents'"
        :model-value="read(field.key)"
        :disabled="!dependencyValue(field)"
        :placeholder="dependencyValue(field) ? '选择文档' : '先选知识库'"
        size="small"
        filterable
        clearable
        style="width: 100%"
        @change="(value: string | null) => write(field.key, value || null)"
      >
        <el-option
          v-for="doc in documentsOf(field)"
          :key="doc.value"
          :label="doc.label"
          :value="doc.value"
        />
      </el-select>

      <!-- ②' 选项来自模型列表，按 `modelKind` 过滤（视觉理解也要按 CHAT 过滤，见目录里的说明） -->
      <el-select
        v-else-if="field.optionsSource === 'models'"
        :model-value="read(field.key)"
        size="small"
        filterable
        clearable
        :placeholder="modelsOf(field).length ? '选择模型' : '没有这种种类的模型，先去模型管理里加'"
        style="width: 100%"
        @change="(value: string | null) => write(field.key, value || null)"
      >
        <el-option
          v-for="model in modelsOf(field)"
          :key="model.value"
          :label="model.label"
          :value="model.value"
        />
      </el-select>

      <!-- ③ Markdown 长文本 -->
      <MdEditorMagnify
        v-else-if="field.editor === 'markdown'"
        :model-value="read(field.key) ?? ''"
        :title="field.label"
        style="height: 150px"
        @update:model-value="(value: string) => write(field.key, value)"
        @wheel="wheel"
      />

      <!-- ④ 固定选项 -->
      <el-select
        v-else-if="field.options && field.options.length > 0"
        :model-value="read(field.key) ?? field.options[0]"
        size="small"
        style="width: 100%"
        @change="(value: string) => write(field.key, value)"
      >
        <el-option
          v-for="option in field.options"
          :key="option"
          :label="optionLabel(field, option)"
          :value="option"
        />
      </el-select>

      <!-- ⑤ 布尔 -->
      <el-switch
        v-else-if="field.type === 'boolean'"
        :model-value="read(field.key) === true"
        size="small"
        @change="(value: boolean) => write(field.key, value)"
      />

      <!-- ⑥ 普通文本 / UUID -->
      <el-input
        v-else-if="field.type === 'string' || field.type === 'uuid'"
        :model-value="read(field.key) ?? ''"
        :placeholder="field.hint ? '' : `填写${field.label}`"
        size="small"
        @update:model-value="(value: string) => write(field.key, value)"
      />

      <!-- ⑦ 到这里就是「声明了但没控件」—— 明说，不兜底 -->
      <div v-else class="schema-form__unsupported">
        字段「{{ field.label }}」（{{ field.type }}）还没有对应控件，这一项暂时不能在画布上编辑。
      </div>

      <div v-if="field.hint" class="schema-form__hint">{{ field.hint }}</div>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get, set } from 'lodash-es'
import type { NodeConfigField } from '@/api/workflow'
import type { ModelKind } from '@/api/models'
import { listKnowledgeBases } from '@/api/knowledge'
import { listDocuments } from '@/api/ingestion'
import { listModels } from '@/api/models'

interface Option {
  label: string
  value: string
}

const props = defineProps<{
  nodeModel: any
  fields: readonly NodeConfigField[]
}>()

/**
 * `changed` 是为了让父组件（节点卡片的只读摘要）能跟着变：
 * 摘要里要显示"抽的是哪一篇文档"这种**名字**，而名字只有这里知道（候选列表在 /api 里）。
 */
const emit = defineEmits<{ changed: [] }>()

/**
 * `nodeModel.properties` 不是响应式对象，改了它不会触发重渲染。
 * 沿用其它面板的做法：一个版本号，读之前在 getter 里读它一次，写之后 +1。
 */
const tick = ref(0)

const kbs = ref<Option[]>([])
const documents = ref<Record<string, Option[]>>({})
/** 模型候选，按种类分开缓存：同一个面板里可能既有对话模型字段又有图像模型字段。 */
const models = ref<Record<string, Option[]>>({})

const visibleFields = computed(() => props.fields)

const model = computed(() => {
  void tick.value
  return (props.nodeModel?.properties?.node_data ?? {}) as Record<string, any>
})

function read(key: string): any {
  void tick.value
  return get(props.nodeModel?.properties?.node_data ?? {}, key)
}

function write(key: string, value: any): void {
  set(props.nodeModel.properties.node_data, key, value)
  tick.value++
  emit('changed')
}

/**
 * 当前选中项的显示名（卡片摘要用）。
 * 找不到就返回空串 —— 摘要显示"未选择"，不拿 UUID 糊弄。
 */
function labelOf(key: string): string {
  void tick.value
  const value = read(key)
  if (!value) return ''
  const field = props.fields.find((item) => item.key === key)
  if (!field) return ''
  if (field.optionsSource === 'knowledge-bases') {
    return kbs.value.find((option) => option.value === value)?.label ?? ''
  }
  if (field.optionsSource === 'documents') {
    return documentsOf(field).find((option) => option.value === value)?.label ?? ''
  }
  if (field.optionsSource === 'models') {
    return modelsOf(field).find((option) => option.value === value)?.label ?? ''
  }
  return ''
}

/** 依赖字段的当前取值（`documents` 依赖库字段）。 */
function dependencyValue(field: NodeConfigField): string {
  const dependsOn = field.dependsOn
  if (!dependsOn) return ''
  const raw = read(dependsOn)
  return typeof raw === 'string' ? raw : ''
}

/**
 * 换依赖字段的取值时，把依赖它的字段清空。
 * 留着上一个库的文档 id 会指向另一个库的文档，而画面上看不出这件事。
 */
function pickDependencySource(field: NodeConfigField, value: string | null): void {
  write(field.key, value || null)
  for (const other of props.fields) {
    if (other.dependsOn === field.key) write(other.key, null)
  }
  if (value) void loadDocuments(value)
}

function documentsOf(field: NodeConfigField): Option[] {
  const dependsOn = field.dependsOn
  if (!dependsOn) return []
  return documents.value[dependencyValue(field)] ?? []
}

function modelsOf(field: NodeConfigField): Option[] {
  return models.value[field.modelKind ?? ''] ?? []
}

function optionLabel(field: NodeConfigField, option: string): string {
  if (field.key === 'replyType' && option === 'content') return '自定义内容'
  return option
}

const wheel = (e: any) => {
  if (e.ctrlKey === true) {
    e.preventDefault()
    return true
  }
  e.stopPropagation()
  return true
}

async function loadKnowledgeBases(): Promise<void> {
  const result = await listKnowledgeBases({ page: 1, size: 100 })
  kbs.value = (result.list ?? []).map((kb) => ({ label: kb.name, value: kb.id }))
}

/**
 * 文档候选：**只有已解析出正文的文档能用**，但未解析的也要列出来 ——
 * 藏掉它们会让用户以为"我明明上传了却没在列表里"。
 */
async function loadDocuments(kbId: string): Promise<void> {
  if (documents.value[kbId]) return
  const result = await listDocuments(kbId, { page: 1, size: 100 })
  documents.value = {
    ...documents.value,
    [kbId]: (result.list ?? []).map((doc) => ({
      label: doc.status === 'SUCCEEDED' ? doc.name : `${doc.name}（${doc.status}）`,
      value: doc.id
    }))
  }
  // 候选到位了才查得到显示名，摘要要的就是这个名字
  emit('changed')
}

/**
 * 模型候选：按 `modelKind` 拉一次，缓存起来。
 *
 * ★ 只列**已加载可用**的？不 —— 与文档候选同一个取舍：列出来并标注状态，让用户看到
 *   "我加过这个模型但它没加载"，而不是以为它不存在。
 */
async function loadModels(kind: ModelKind): Promise<void> {
  if (models.value[kind]) return
  const result = await listModels({ kind, page: 1, size: 100 })
  models.value = {
    ...models.value,
    [kind]: (result.list ?? []).map((model) => ({
      label: model.status === 'LOADED' ? model.name : `${model.name}（${model.status}）`,
      value: model.id
    }))
  }
  emit('changed')
}

/** 供节点面板挂到 `nodeModel.validate`（发请求前跑一遍）。 */
function validate(): boolean {
  for (const field of props.fields) {
    if (!field.required) continue
    const value = read(field.key)
    const empty =
      value === undefined || value === null || (typeof value === 'string' && value.trim() === '')
    if (empty) {
      throw new Error(`「${field.label}」还没有填。`)
    }
  }
  return true
}

onMounted(async () => {
  if (props.fields.some((field) => field.optionsSource === 'knowledge-bases')) {
    await loadKnowledgeBases()
  }
  const kinds = new Set(
    props.fields
      .filter((field) => field.optionsSource === 'models' && field.modelKind)
      .map((field) => field.modelKind as ModelKind)
  )
  for (const kind of kinds) {
    await loadModels(kind)
  }
  for (const field of props.fields) {
    const dependsOn = field.dependsOn
    if (!dependsOn) continue
    const value = dependencyValue(field)
    if (value) await loadDocuments(value)
  }
  if (props.nodeModel) props.nodeModel.validate = validate
  emit('changed')
})

defineExpose({ validate, labelOf })
</script>

<style scoped>
.schema-form__hint {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.schema-form__unsupported {
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.6;
}
</style>
