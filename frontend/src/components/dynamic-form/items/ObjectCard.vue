<template>
  <el-card :style="cardStyle">
    <DynamicForm
      ref="innerForm"
      :style="formStyle"
      label-position="top"
      require-asterisk-position="right"
      :view="view"
      :fields="fields"
      :other-params="other"
      :trigger="trigger"
      :parent-field="formField.field"
      :model-value="data"
      @update:model-value="onUpdate"
    />
  </el-card>
</template>
<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import type { FormField, FormTrigger, FormValues } from '../types'

const DynamicForm = defineAsyncComponent(() => import('../DynamicForm.vue'))

defineOptions({ name: 'ObjectCard', inheritAttrs: false })

const props = defineProps<{
  modelValue?: any
  formValue?: FormValues
  formfieldList?: Array<FormField>
  field?: string
  otherParams?: any
  formField: FormField
  view?: boolean
  disabled?: boolean
  trigger?: FormTrigger
}>()

const emit = defineEmits<{
  'update:modelValue': [value: any]
}>()

const innerForm = ref<{ validate: () => Promise<void> } | null>(null)

const data = computed(() => props.modelValue || {})
const fields = computed(() => props.formField.children || [])
const propsInfo = computed(() => props.formField.props_info || {})
const cardStyle = computed(() => propsInfo.value.style || {})
const formStyle = computed(() => propsInfo.value.form_style || {})
const other = computed(() => ({ ...(props.formValue || {}), ...(props.otherParams || {}) }))

function onUpdate(v: FormValues) {
  emit('update:modelValue', v)
}

function validate() {
  return innerForm.value ? innerForm.value.validate() : Promise.resolve()
}

defineExpose({ validate })
</script>
