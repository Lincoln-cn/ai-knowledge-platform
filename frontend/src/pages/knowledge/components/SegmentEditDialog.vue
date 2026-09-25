<template>
  <el-dialog
    v-model="visible"
    title="编辑分段"
    width="640px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <el-form ref="formRef" :model="form" label-width="72px">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="80" show-word-limit placeholder="分段标题" />
      </el-form-item>
      <el-form-item label="内容" prop="content">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="10"
          resize="vertical"
          placeholder="分段正文"
        />
        <div class="hint micro" :class="{ warn: isOversized }">
          {{ form.content.length }} 字
          <template v-if="isOversized">
            · ⚠ 超过向量模型上限约 {{ modelLimit }} 字，超出部分将被截断，建议拆分
          </template>
        </div>
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
        <span class="hint micro">停用后该分段不参与检索</span>
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
import { ElMessage } from 'element-plus'
import { ingestionApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Segment } from '@/api/ingestion'

const props = defineProps<{ modelLimit: number }>()
const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const editingId = ref('')

const form = reactive({
  title: '',
  content: '',
  enabled: true
})

const isOversized = computed(() => form.content.length > props.modelLimit)

const open = (seg: Segment) => {
  editingId.value = seg.id
  form.title = seg.title
  form.content = seg.content
  form.enabled = seg.enabled
  visible.value = true
}

const reset = () => {
  editingId.value = ''
  form.title = ''
  form.content = ''
  form.enabled = true
}

const submit = async () => {
  saving.value = true
  try {
    await ingestionApi.updateSegment(editingId.value, {
      title: form.title,
      content: form.content
    })
    if (form.enabled !== undefined) {
      await ingestionApi.setSegmentEnabled(editingId.value, form.enabled)
    }
    ElMessage.success('已保存。内容变更后需重新向量化')
    visible.value = false
    emit('saved')
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '保存失败，请稍后重试'
    ElMessage.error(message)
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.hint {
  color: var(--color-muted);
  line-height: var(--leading-body);
  margin-top: var(--space-2xs);
}

.hint.warn {
  color: var(--color-warn);
}

.hint .num {
  font-size: var(--text-2xs);
  font-variant-numeric: tabular-nums;
}
</style>
