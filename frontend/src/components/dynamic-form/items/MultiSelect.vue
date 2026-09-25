<template>
  <el-select
    v-bind="$attrs"
    multiple
    collapse-tags
    filterable
    clearable
    :disabled="disabled"
    :model-value="modelValue || []"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-option
      v-for="(item, index) in optionList"
      :key="index"
      :label="item[textField]"
      :value="item[valueField]"
    />
  </el-select>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { FormField, FormTrigger, FormValues } from '../types'

defineOptions({ name: 'MultiSelect', inheritAttrs: false })

const props = defineProps<{
  modelValue?: Array<any>
  formField: FormField
  formValue?: FormValues
  formfieldList?: Array<FormField>
  field?: string
  otherParams?: any
  view?: boolean
  disabled?: boolean
  trigger?: FormTrigger
}>()

const emit = defineEmits<{
  'update:modelValue': [value: any]
}>()

const textField = computed(() => props.formField.text_field || 'key')
const valueField = computed(() => props.formField.value_field || 'value')
const optionList = computed(() => props.formField.option_list || [])
</script>
