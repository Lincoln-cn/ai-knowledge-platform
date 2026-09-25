<template>
  <div class="radio-row">
    <div
      v-for="(item, index) in optionList"
      :key="index"
      class="item"
      :class="{ 'is-disabled': disabled, active: modelValue == item[valueField] }"
      @click="!disabled && selected(item[valueField])"
    >
      {{ item[textField] }}
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { FormField, FormTrigger, FormValues } from '../types'

defineOptions({ name: 'RadioRow' })

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

function selected(v: string | number) {
  emit('update:modelValue', v)
}
</script>
<style scoped>
.radio-row {
  display: inline-flex;
  flex-wrap: wrap;
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  font-size: var(--text-sm);
  color: var(--color-ink);
  padding: var(--space-3xs) var(--space-2xs);
  box-sizing: border-box;
  min-width: 0;
}

.item {
  cursor: pointer;
  margin: 0 var(--space-3xs);
  padding: var(--space-3xs) var(--space-xs);
  min-height: 24px;
  display: flex;
  justify-content: center;
  align-items: center;
  border-radius: var(--radius-xs);
  border: 1px solid transparent;
  color: var(--color-ink-2);
  transition: color var(--dur-micro) var(--ease-out), background var(--dur-micro) var(--ease-out),
    border-color var(--dur-micro) var(--ease-out);
}

.item:first-child {
  margin: 0 var(--space-3xs) 0 var(--space-2xs);
}

.item:last-child {
  margin: 0 var(--space-2xs) 0 var(--space-3xs);
}

.item:hover {
  background: var(--color-hover);
  color: var(--color-ink);
}

.item:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 1px;
}

.item:active {
  background: var(--color-sunken);
}

.item.is-disabled {
  color: var(--color-faint);
  cursor: not-allowed;
  background: transparent;
}

.item.is-disabled:hover {
  background: transparent;
  color: var(--color-faint);
}

.item.active {
  background: var(--color-accent-soft);
  border-color: var(--color-accent-line);
  color: var(--color-accent);
  font-weight: 500;
}
</style>
