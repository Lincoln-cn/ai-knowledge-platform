<template>
  <el-slider v-bind="$attrs" :disabled="disabled" show-input :show-input-controls="false" class="df-slider" />
</template>
<script setup lang="ts">
import type { FormField, FormTrigger, FormValues } from '../types'

defineOptions({ name: 'Slider', inheritAttrs: false })

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
<style scoped>
.df-slider :deep(.el-input-number.is-without-controls .el-input__wrapper) {
  padding: 0;
}
</style>
