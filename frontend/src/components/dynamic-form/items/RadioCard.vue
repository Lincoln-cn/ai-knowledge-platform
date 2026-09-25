<template>
  <div class="radio-card">
    <el-row :gutter="12" class="w-full">
      <el-col v-for="(item, index) in optionList" :key="index" :xs="24" :sm="24" :md="24" :lg="12" :xl="12">
        <el-card
          shadow="never"
          class="item"
          :class="{ 'is-disabled': disabled, active: modelValue == item[valueField] }"
          @click="!disabled && selected(item[valueField])"
        >
          {{ item[textField] }}
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { FormField, FormTrigger, FormValues } from '../types'

defineOptions({ name: 'RadioCard' })

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
.radio-card {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-start;
  width: 100%;
  min-width: 0;
}

.w-full {
  width: 100%;
  min-width: 0;
}

.item {
  cursor: pointer;
  min-height: 36px;
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  margin: var(--space-2xs);
  border-radius: var(--radius);
  border: 1px solid var(--color-rule);
  font-size: var(--text-sm);
  color: var(--color-ink-2);
  transition: color var(--dur-micro) var(--ease-out), background var(--dur-micro) var(--ease-out),
    border-color var(--dur-micro) var(--ease-out);
}

.item:hover {
  background: var(--color-hover);
  color: var(--color-ink);
  border-color: var(--color-rule-strong);
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
  background: var(--color-sunken);
  border-color: var(--color-rule);
}

.item.is-disabled:hover {
  background: var(--color-sunken);
  color: var(--color-faint);
  border-color: var(--color-rule);
}

.item.active {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-weight: 500;
  box-shadow: inset 2px 0 0 var(--color-accent);
}
</style>
