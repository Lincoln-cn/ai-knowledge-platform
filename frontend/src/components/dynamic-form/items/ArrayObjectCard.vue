<template>
  <div class="array-object-card">
    <el-card v-for="(item, index) in dataList" :key="index" class="box-card" :style="cardStyle">
      <DynamicForm
        ref="innerForms"
        :style="formStyle"
        label-position="top"
        require-asterisk-position="right"
        :view="view"
        :fields="fields"
        :other-params="other"
        :trigger="trigger"
        :parent-field="formField.field + '.' + index"
        :model-value="item"
        @update:model-value="(v) => onItemUpdate(index, v)"
      />
      <el-tooltip v-if="!view" effect="dark" content="删除" placement="top">
        <el-button text class="delete-button" @click.stop="removeAt(index)">
          <el-icon><Delete /></el-icon>
        </el-button>
      </el-tooltip>
    </el-card>
    <el-card v-if="!view" shadow="never" class="card-add box-card" @click="addCard">
      <div class="add-row">
        <el-icon class="add-icon"><Plus /></el-icon>
        <span>{{ addMsg }}</span>
      </div>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { FormField, FormTrigger, FormValues } from '../types'

const DynamicForm = defineAsyncComponent(() => import('../DynamicForm.vue'))

defineOptions({ name: 'ArrayObjectCard', inheritAttrs: false })

const props = defineProps<{
  modelValue?: Array<any>
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

const innerForms = ref<Array<{ validate: () => Promise<void> }>>([])

const dataList = computed(() => props.modelValue || [])
const fields = computed(() => props.formField.children || [])
const propsInfo = computed(() => props.formField.props_info || {})
const cardStyle = computed(() => propsInfo.value.style || {})
const formStyle = computed(() => propsInfo.value.form_style || {})
const addMsg = computed(() => propsInfo.value.add_msg || '添加')
const other = computed(() => ({ ...(props.formValue || {}), ...(props.otherParams || {}) }))

function onItemUpdate(index: number, v: FormValues) {
  const next = dataList.value.map((row, i) => (i === index ? v : row))
  emit('update:modelValue', next)
}

function removeAt(index: number) {
  emit(
    'update:modelValue',
    dataList.value.filter((_, i) => i !== index)
  )
}

function addCard() {
  emit('update:modelValue', [...dataList.value, {}])
}

function validate() {
  return Promise.all(innerForms.value.map((f) => f.validate())).then(() => undefined)
}

defineExpose({ validate })
</script>
<style scoped>
.array-object-card {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  width: 100%;
  min-width: 0;
}

.box-card {
  flex: 1 1 240px;
  min-width: 0;
  position: relative;
  border-radius: var(--radius-lg);
}

.card-add {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  font-size: var(--text-sm);
  cursor: pointer;
  min-height: 96px;
  border: 1px dashed var(--color-accent);
  background: var(--color-sunken);
  border-radius: var(--radius-lg);
  transition: background var(--dur-micro) var(--ease-out), color var(--dur-micro) var(--ease-out),
    border-color var(--dur-micro) var(--ease-out);
}

.card-add:hover {
  color: var(--color-accent);
  background: var(--color-surface);
  border-color: var(--color-accent-hover);
}

.card-add:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 1px;
}

.card-add:active {
  background: var(--color-accent-soft);
}

.add-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.add-icon {
  font-size: var(--text-sm);
  border: 1px solid var(--color-rule);
  padding: var(--space-2xs);
  border-radius: var(--radius-xs);
}

.delete-button {
  position: absolute;
  right: var(--space-sm);
  top: var(--space-xs);
  height: auto;
}
</style>
