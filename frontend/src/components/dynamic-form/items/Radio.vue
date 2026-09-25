<template>
  <el-radio-group
    v-bind="$attrs"
    :disabled="disabled"
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-radio v-for="(item, index) in optionList" :key="index" :value="item[valueField]">
      {{ item[textField] }}
    </el-radio>
  </el-radio-group>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { FormField, FormTrigger, FormValues } from '../types'

defineOptions({ name: 'Radio', inheritAttrs: false })

const props = defineProps<{
  modelValue?: any
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
