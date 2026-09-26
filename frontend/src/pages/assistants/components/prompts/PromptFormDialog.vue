<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="560px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
      <el-form-item label="角色名称" prop="roleName">
        <el-input v-model="form.roleName" maxlength="30" show-word-limit placeholder="例如：合同审查助手" />
      </el-form-item>
      <el-form-item label="提示词" prop="callWord">
        <el-input
          v-model="form.callWord"
          type="textarea"
          :rows="5"
          resize="none"
          maxlength="500"
          show-word-limit
          placeholder="定义助手的行为指令，可复用在多个应用上"
        />
      </el-form-item>
      <el-form-item label="类别" prop="typeId">
        <el-select v-model="form.typeId" placeholder="选择提示词类型" style="width: 100%">
          <el-option v-for="t in types" :key="t.id" :label="t.typeName" :value="t.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="300"
          show-word-limit
        />
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
    </el-form>

    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { promptsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { PromptConfig, PromptType, UpsertPromptConfigBody } from '@/api/prompts'

const props = defineProps<{
  types: PromptType[]
}>()
const emit = defineEmits<{ saved: [] }>()

const ICONS = ['⚖️', '🧾', '🔍', '⚠️', '👋', '📝', '📄', '🤖']

const visible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const editing = ref<PromptConfig | null>(null)

const form = reactive({
  roleName: '',
  callWord: '',
  typeId: '',
  description: '',
  icon: ICONS[0]
})

const title = computed(() => (editing.value ? '编辑提示词' : '新建提示词'))

const rules: FormRules = {
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  callWord: [{ required: true, message: '请输入提示词', trigger: 'blur' }],
  typeId: [{ required: true, message: '请选择类别', trigger: 'change' }],
  description: [{ required: true, message: '请填写描述', trigger: 'blur' }]
}

const reset = () => {
  formRef.value?.resetFields()
  formRef.value?.clearValidate()
  form.roleName = ''
  form.callWord = ''
  form.typeId = ''
  form.description = ''
  form.icon = ICONS[0]
  editing.value = null
}

const openConfig = (row?: PromptConfig) => {
  editing.value = row ?? null
  if (row) {
    form.roleName = row.roleName
    form.callWord = row.callWord
    form.typeId = row.typeId
    form.description = row.description
    form.icon = row.icon
  } else if (props.types.length) {
    form.typeId = props.types[0].id
    form.icon = '⚖️'
  }
  visible.value = true
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const body: UpsertPromptConfigBody = {
      roleName: form.roleName,
      callWord: form.callWord,
      typeId: form.typeId,
      description: form.description,
      icon: form.icon
    }
    if (editing.value) {
      await promptsApi.updatePromptConfig(editing.value.id, body)
    } else {
      await promptsApi.createPromptConfig(body)
    }
    ElMessage.success('已保存')
    visible.value = false
    emit('saved')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ openConfig })
</script>

<style scoped>
.icons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.icon-btn {
  width: calc(var(--space-lg) + var(--space-xs));
  height: calc(var(--space-lg) + var(--space-xs));
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
  font-size: var(--text-md);
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
</style>
