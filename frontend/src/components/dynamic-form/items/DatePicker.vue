<template>
  <el-date-picker v-bind="$attrs" :disabled="disabled" />
</template>
<script setup lang="ts">
import type { FormField, FormTrigger, FormValues } from '../types'

defineOptions({ name: 'DatePicker', inheritAttrs: false })

defineProps<{
  modelValue?: any
  formField?: FormField
  formValue?: FormValues
  formfieldList?: Array<FormField>
  field?: string
  otherParams?: any
  view?: boolean
  disabled?: boolean
  trigger?: FormTrigger
}>()
</script>
