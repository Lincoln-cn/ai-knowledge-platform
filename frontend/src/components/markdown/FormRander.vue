<template>
  <div class="form-rander">
    <DynamicForm
      ref="dynamicsFormRef"
      label-position="top"
      require-asterisk-position="right"
      :view="disabled || isSubmit"
      :fields="fieldList"
      :model-value="formData"
      label-suffix=":"
      @update:model-value="(v) => (formData = v)"
    />
    <el-button
      v-if="!hideSubmit"
      size="small"
      :type="isSubmit ? 'info' : 'primary'"
      :disabled="disabled || isSubmit"
      :loading="submitting"
      @click="submit"
    >
      提交
    </el-button>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import DynamicForm from '@/components/dynamic-form/DynamicForm.vue'
import type { FormValues } from '@/components/dynamic-form/types'
import type { FormSettingData } from './types'

/**
 * 文本流里的可交互表单块。
 * 旧版通过 sendMessage 回灌工作流运行时；这里只抛 `submit`，由调用方决定怎么回传。
 */
defineOptions({ name: 'FormRander' })

const props = withDefaults(
  defineProps<{
    /** JSON 字符串或对象：{ form_field_list, form_data, is_submit } */
    formSetting: string | FormSettingData
    disabled?: boolean
    hideSubmit?: boolean
  }>(),
  {
    disabled: false,
    hideSubmit: false
  }
)

const emit = defineEmits<{
  submit: [data: FormValues]
}>()

const submitting = ref(false)
const localData = ref<FormValues>({})
const dynamicsFormRef = ref<InstanceType<typeof DynamicForm>>()

const setting = computed<FormSettingData>(() => {
  if (!props.formSetting) return {}
  if (typeof props.formSetting === 'string') {
    try {
      return JSON.parse(props.formSetting) as FormSettingData
    } catch {
      return {}
    }
  }
  return props.formSetting
})

const fieldList = computed(() => setting.value.form_field_list || [])
const isSubmit = computed(() => !!setting.value.is_submit)

const formData = computed<FormValues>({
  get: () => (isSubmit.value ? setting.value.form_data || {} : localData.value),
  set: (v) => {
    localData.value = v
  }
})

async function submit() {
  if (!dynamicsFormRef.value) return
  submitting.value = true
  try {
    await dynamicsFormRef.value.validate()
    emit('submit', formData.value)
  } finally {
    submitting.value = false
  }
}

defineExpose({ submit })
</script>
<style scoped>
.form-rander {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-sm);
  width: 100%;
}
</style>
