<template>
  <el-dialog v-model="visible" title="提示词类型" width="640px" @closed="reset">
    <div class="type-form">
      <el-input
        v-model="form.typeName"
        placeholder="类型名称，例如：客服"
        maxlength="30"
        show-word-limit
        style="width: 200px"
      />
      <el-input
        v-model="form.icon"
        placeholder="图标（emoji 或短文本）"
        maxlength="16"
        style="width: 170px"
      />
      <el-button type="primary" :loading="saving" @click="save">
        {{ editingId ? '保存' : '新增' }}
      </el-button>
      <el-button v-if="editingId" @click="reset">取消</el-button>
    </div>
    <div class="type-hint">
      类型是模板的分类法；仍被模板引用的类型不能删除（后端会返回可读原因）。
    </div>
    <el-table v-loading="loading" :data="types" size="small" max-height="360">
      <el-table-column prop="typeName" label="名称" />
      <el-table-column prop="icon" label="图标" width="90" />
      <el-table-column prop="promptCount" label="模板数" width="80" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button link type="primary" @click="edit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { promptsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { PromptType } from '@/api/prompts'

defineProps<{
  types: PromptType[]
  loading: boolean
}>()

const emit = defineEmits<{
  changed: []
}>()

const visible = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({ typeName: '', icon: '' })

const reset = () => {
  editingId.value = null
  form.typeName = ''
  form.icon = ''
}

const open = () => {
  reset()
  visible.value = true
}

const edit = (row: PromptType) => {
  editingId.value = row.id
  form.typeName = row.typeName
  form.icon = row.icon
}

const save = async () => {
  const typeName = form.typeName.trim()
  const icon = form.icon.trim()
  if (!typeName) {
    ElMessage.warning('类型名称不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await promptsApi.updatePromptType(editingId.value, { typeName, icon })
      ElMessage.success('已保存')
    } else {
      await promptsApi.createPromptType({ typeName, icon })
      ElMessage.success('已新增')
    }
    reset()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const remove = async (row: PromptType) => {
  const confirmed = await ElMessageBox.confirm(
    `删除类型「${row.typeName}」？仍被模板引用时会被阻止。`,
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
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

defineExpose({ open })
</script>

<style scoped>
.type-form {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.type-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin: 8px 0 12px;
}
</style>
