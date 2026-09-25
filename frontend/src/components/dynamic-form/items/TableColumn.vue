<template>
  <component :is="resolved" v-bind="attrs" :column="column" :row="row" />
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { resolveFieldComponent } from '../registry'
import type { TableColumnConfig } from '../types'

const props = defineProps<{
  column: TableColumnConfig
  row: any
  modelValue?: any
  formField?: any
  formValue?: any
  formfieldList?: any
  field?: string
  otherParams?: any
  view?: boolean
  disabled?: boolean
  trigger?: any
}>()

const attrs = computed(() => props.column.attrs || {})
const resolved = computed(() => resolveFieldComponent(props.column.property))
</script>
