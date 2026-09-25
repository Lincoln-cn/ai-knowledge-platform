<template>
  <div class="tab-card">
    <el-tabs v-model="activeTab" :editable="!view" type="card" @edit="handleEdit">
      <el-tab-pane v-for="(item, index) in dataList" :key="index" :label="tabsLabel + (index + 1)" :name="index">
        <el-card :style="cardStyle">
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
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import type { TabPaneName } from 'element-plus'
import type { FormField, FormTrigger, FormValues } from '../types'

const DynamicForm = defineAsyncComponent(() => import('../DynamicForm.vue'))

defineOptions({ name: 'TabCard', inheritAttrs: false })

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

const activeTab = ref<number>(0)
const innerForms = ref<Array<{ validate: () => Promise<void> }>>([])

const dataList = computed(() => props.modelValue || [])
const fields = computed(() => props.formField.children || [])
const propsInfo = computed(() => props.formField.props_info || {})
const tabsLabel = computed(() => propsInfo.value.tabs_label || 'label')
const cardStyle = computed(() => propsInfo.value.style || {})
const formStyle = computed(() => propsInfo.value.form_style || {})
const other = computed(() => ({ ...(props.formValue || {}), ...(props.otherParams || {}) }))

function onItemUpdate(index: number, v: FormValues) {
  const next = dataList.value.map((row, i) => (i === index ? v : row))
  emit('update:modelValue', next)
}

function handleEdit(targetName: TabPaneName | undefined, action: 'remove' | 'add') {
  if (action === 'add') {
    const next = [...dataList.value, {}]
    emit('update:modelValue', next)
    activeTab.value = next.length - 1
  } else if (action === 'remove' && typeof targetName === 'number') {
    const next = dataList.value.filter((_, i) => i !== targetName)
    emit('update:modelValue', next)
    activeTab.value = Math.max(0, next.length - 1)
  }
}

function validate() {
  return Promise.all(innerForms.value.map((f) => f.validate())).then(() => undefined)
}

defineExpose({ validate })
</script>
<style scoped>
.tab-card {
  width: 100%;
}
</style>
