<template>
  <el-dialog
    v-model="visible"
    :title="editing ? '编辑应用' : '新建应用'"
    width="560px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
      <el-form-item label="应用类型" prop="type">
        <div class="type-cards" :class="{ readonly: editing }">
          <button
            type="button"
            class="type-card"
            :class="{ on: form.type === 'simple' }"
            :disabled="!!editing"
            @click="form.type = 'simple'"
          >
            <div class="tc-title">简单应用</div>
            <div class="tc-desc">只需提示词约束 + 知识库，适合绝大多数问答场景</div>
          </button>
          <button
            type="button"
            class="type-card"
            :class="{ on: form.type === 'workflow' }"
            :disabled="!!editing"
            @click="form.type = 'workflow'"
          >
            <div class="tc-title">编排应用</div>
            <div class="tc-desc">画布式多步流程，适合分支、条件与多节点协作</div>
          </button>
        </div>
        <div class="hint micro">
          {{ editing ? '应用类型创建后不可更改；如需改为编排/简单，请新建应用' : '创建后不可更改，请按场景选择' }}
        </div>
      </el-form-item>
      <el-form-item label="应用名称" prop="name">
        <el-input v-model="form.name" maxlength="30" show-word-limit placeholder="例如：合同审查助手" />
      </el-form-item>
      <el-form-item label="图标">
        <div class="icons">
          <button
            v-for="icon in ICONS"
            :key="icon"
            type="button"
            class="icon-btn"
            :class="{ on: form.icon === icon }"
            @click="form.icon = icon"
          >{{ icon }}</button>
        </div>
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="200"
          show-word-limit
          placeholder="一句话说明这个应用解决什么问题"
        />
      </el-form-item>
      <el-form-item label="对话模型" prop="modelId">
        <el-select v-model="form.modelId" placeholder="仅列出已加载的对话模型" style="width: 100%">
          <el-option v-for="m in chatModels" :key="m.id" :label="`${m.name}（已加载）`" :value="m.id" />
        </el-select>
        <div class="hint micro">
          {{ form.type === 'workflow'
            ? '创建后进入编排画布配置流程；模型可作为 AI 节点默认值'
            : '创建后进入配置页补全角色设定、知识库与检索参数' }}
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="submit">
        {{ editing ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { assistantsApi, modelsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Assistant, AssistantType } from '@/api/assistants'
import type { ModelInfo } from '@/api/models'

const emit = defineEmits<{ created: []; saved: [] }>()

const ICONS = ['⚖️', '🧾', '🏭', '🤖', '📋', '💬', '📚', '🔍']

const visible = ref(false)
const saving = ref(false)
const editing = ref<Assistant | null>(null)
const formRef = ref<FormInstance>()
const chatModels = ref<ModelInfo[]>([])

const form = reactive({
  type: 'simple' as AssistantType,
  name: '',
  icon: '🤖',
  description: '',
  modelId: ''
})

const rules: FormRules = {
  type: [
    {
      validator: (_rule, value: AssistantType, callback) => {
        if (value === 'simple' || value === 'workflow') callback()
        else callback(new Error('请选择应用类型'))
      },
      trigger: 'change'
    }
  ],
  name: [{ required: true, message: '请输入应用名称', trigger: 'blur' }],
  description: [{ required: true, message: '请填写描述', trigger: 'blur' }],
  modelId: [{ required: true, message: '请选择对话模型', trigger: 'change' }]
}

/** 创建：open()；编辑基本信息：open(existing) —— 编辑时类型只读 */
const open = async (existing?: Assistant | null) => {
  editing.value = existing ?? null
  if (existing) {
    form.type = existing.type
    form.name = existing.name
    form.icon = existing.icon
    form.description = existing.description
    form.modelId = existing.modelId ?? ''
  }
  visible.value = true
  const result = await modelsApi.listModels({ kind: 'chat', status: '已加载', size: 50 })
  chatModels.value = result.list
  if (!form.modelId && result.list.length) form.modelId = result.list[0].id
}

const reset = () => {
  formRef.value?.clearValidate()
  editing.value = null
  form.type = 'simple'
  form.name = ''
  form.icon = '🤖'
  form.description = ''
  form.modelId = ''
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editing.value) {
      // type 不进更新体：创建后不可改（后端对改 type 会直接拒绝）
      await assistantsApi.updateAssistant(editing.value.id, {
        name: form.name,
        icon: form.icon,
        description: form.description,
        modelId: form.modelId
      })
      ElMessage.success('已保存')
      visible.value = false
      emit('saved')
    } else {
      await assistantsApi.createAssistant({
        type: form.type,
        name: form.name,
        icon: form.icon,
        description: form.description,
        modelId: form.modelId,
        kbIds: [],
        rolePrompt: '',
        promptIds: [],
        retrieval: { mode: '混合检索', threshold: 0.6, topN: 5, noRefStrategy: '拒答' },
        multiTurn: true,
        thinkingEnabled: false,
        prologue: '',
        quickQuestions: []
      })
      ElMessage.success(
        form.type === 'workflow' ? '已创建，请进入编排画布配置流程' : '已创建，请继续完善配置'
      )
      visible.value = false
      emit('created')
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : editing.value ? '保存失败' : '创建失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.type-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-xs);
  width: 100%;
}

.type-card {
  text-align: left;
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
  cursor: pointer;
  transition: border-color var(--dur-micro) var(--ease-out),
    background-color var(--dur-micro) var(--ease-out);
}

.type-card:hover:not(:disabled) {
  background: var(--color-hover);
}

.type-card.on {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.type-card:disabled {
  cursor: not-allowed;
  opacity: 0.72;
}

.type-cards.readonly .type-card:not(.on) {
  opacity: 0.45;
}

.tc-title {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--color-ink);
  margin-bottom: var(--space-2xs);
}

.tc-desc {
  font-size: var(--text-2xs);
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.icons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.icon-btn {
  width: var(--space-xl);
  height: var(--space-xl);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
  font-size: var(--text-lg);
  cursor: pointer;
  transition: border-color var(--dur-micro) var(--ease-out),
    background-color var(--dur-micro) var(--ease-out);
}

.icon-btn:hover:not(.on) {
  background: var(--color-hover);
}

.icon-btn.on {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.hint {
  width: 100%;
  color: var(--color-muted);
  line-height: var(--leading-body);
}
</style>
