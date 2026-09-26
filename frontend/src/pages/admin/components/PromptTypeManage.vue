<template>
  <div class="type-manage">
    <div class="notice info">
      提示词类型是<b>分类法</b>，不是内容：它决定提示词配置归到哪一类，也决定应用里
      「从提示词选用」的分组。改一次类型，所有引用它的地方都跟着变 ——
      所以这里归管理员维护，应用配置人只<b>引用</b>类型。
    </div>

    <div class="panel wrap">
      <div class="hd">
        <span class="h4">提示词类型</span>
        <el-button size="small" type="primary" @click="openForm()">新建类型</el-button>
      </div>
      <el-table v-loading="loading" :data="list" size="small" empty-text="暂无提示词类型">
        <el-table-column label="图标" width="60">
          <template #default="{ row }"><span class="icon">{{ row.icon }}</span></template>
        </el-table-column>
        <el-table-column label="类型名称" prop="typeName" min-width="140" />
        <el-table-column label="提示词数" width="100" prop="promptCount" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="openForm(row)">编辑</el-button>
            <el-button link size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="visible"
      :title="editing ? '编辑提示词类型' : '新建提示词类型'"
      width="480px"
      :close-on-click-modal="false"
      @closed="reset"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="类型名称" prop="typeName">
          <el-input v-model="form.typeName" maxlength="30" show-word-limit placeholder="例如：合同法务" />
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { promptsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { PromptType, UpsertPromptTypeBody } from '@/api/prompts'

const ICONS = ['💬', '📜', '📋', '✍️', '🤖', '📚']

const list = ref<PromptType[]>([])
const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const editing = ref<PromptType | null>(null)
const formRef = ref<FormInstance>()

const form = reactive<UpsertPromptTypeBody>({ typeName: '', icon: '💬' })

const rules: FormRules = {
  typeName: [{ required: true, message: '请输入类型名称', trigger: 'blur' }]
}

const load = async () => {
  loading.value = true
  try {
    const result = await promptsApi.listPromptTypes({ page: 1, size: 100 })
    list.value = result.list
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载提示词类型失败')
  } finally {
    loading.value = false
  }
}

const openForm = (row?: PromptType) => {
  editing.value = row ?? null
  form.typeName = row?.typeName ?? ''
  form.icon = row?.icon ?? '💬'
  visible.value = true
}

const reset = () => {
  formRef.value?.resetFields()
  formRef.value?.clearValidate()
  form.typeName = ''
  form.icon = '💬'
  editing.value = null
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const body: UpsertPromptTypeBody = { typeName: form.typeName, icon: form.icon }
    if (editing.value) {
      await promptsApi.updatePromptType(editing.value.id, body)
    } else {
      await promptsApi.createPromptType(body)
    }
    ElMessage.success('已保存')
    visible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const remove = async (row: PromptType) => {
  const confirmed = await ElMessageBox.confirm(
    `删除类型「${row.typeName}」后，其下的提示词将失去分类。确认删除？`,
    '确认删除类型',
    { type: 'warning', confirmButtonText: '删除' }
  ).then(
    () => true,
    () => false
  )
  if (!confirmed) return
  try {
    await promptsApi.deletePromptType(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.type-manage {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.wrap {
  padding: var(--space-md);
}

.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-xs);
}

.icon {
  font-size: var(--text-md);
}

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
