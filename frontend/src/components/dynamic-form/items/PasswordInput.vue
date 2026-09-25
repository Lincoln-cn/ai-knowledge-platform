<template>
  <el-input v-bind="$attrs" type="password" show-password :disabled="disabled" />
</template>
<script setup lang="ts">
import type { FormField, FormTrigger, FormValues } from '../types'

defineOptions({ name: 'PasswordInput', inheritAttrs: false })

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
