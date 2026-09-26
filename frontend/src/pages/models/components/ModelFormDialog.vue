<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑模型' : '添加模型'"
    width="820px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <ModelCreateForm ref="formRef" :model="props.model" @saved="onSaved" />

    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="submit">
        {{ isEdit ? '保存' : '确定' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { ModelInfo } from '@/api/models'
import ModelCreateForm from './ModelCreateForm.vue'

const props = defineProps<{ model: ModelInfo | null }>()
const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const saving = ref(false)
const formRef = ref<InstanceType<typeof ModelCreateForm>>()

const isEdit = computed(() => props.model !== null)

const open = async () => {
  visible.value = true
  // 弹窗内容要等下一拍才挂上，不能同步 ref（同 CreateModelDialog）
  await nextTick()
  await formRef.value?.load()
}

const reset = () => {
  formRef.value?.reset()
}

const submit = async () => {
  saving.value = true
  try {
    const ok = await formRef.value?.submit()
    if (ok) visible.value = false
  } finally {
    saving.value = false
  }
}

const onSaved = () => {
  emit('saved')
}

// 编辑对象变了要重新装载，避免打开第二个模型时还带着上一个的值
watch(() => props.model, () => {
  if (visible.value) open()
})

defineExpose({ open })
</script>
