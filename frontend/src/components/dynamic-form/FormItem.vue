<template>
  <el-form-item :style="formItemStyle" :prop="formfield.field" :rules="rules">
    <template v-if="formfield.label" #label>
      <FormItemLabel v-if="isStringLabel(formfield.label)" :form-field="formfield" />
      <component
        v-else
        :is="labelComponent || FormItemLabel"
        v-bind="labelAttrs"
        :form-field="formfield"
        :label="formfield.label.label"
      />
    </template>
    <component
      ref="componentFormRef"
      :is="fieldComponent"
      :view="view"
      :disabled="view"
      :model-value="modelValue"
      :form-field="formfield"
      :other-params="otherParams"
      :form-value="formValue"
      :formfield-list="formfieldList"
      :trigger="trigger"
      :style="componentStyle"
      :field="formfield.field"
      v-bind="attrs"
      @update:model-value="onChange"
    />
  </el-form-item>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import FormItemLabel from './FormItemLabel.vue'
import { resolveFieldComponent, resolveLabelComponent } from './registry'
import type { FormField, FormTrigger, FormValues } from './types'
import { isStringLabel, jsonTextValidator, labelText } from './utils'

const props = defineProps<{
  modelValue: any
  formfield: FormField
  view: boolean
  otherParams: any
  trigger: FormTrigger
  formValue: FormValues
  formfieldList: Array<FormField>
  parentField?: string
}>()

const emit = defineEmits<{
  change: [value: any]
}>()

const componentFormRef = ref<any>(null)

const fieldComponent = computed(() => resolveFieldComponent(props.formfield.input_type))

const labelComponent = computed(() => {
  const label = props.formfield.label
  if (!label || isStringLabel(label)) return null
  return resolveLabelComponent(label.input_type)
})

const labelAttrs = computed(() => {
  const label = props.formfield.label
  if (!label || isStringLabel(label)) return {}
  return label.attrs || {}
})

const propsInfo = computed(() => props.formfield.props_info || {})
const formItemStyle = computed(() => propsInfo.value.item_style || {})
const componentStyle = computed(() => propsInfo.value.style || {})
const attrs = computed(() => props.formfield.attrs || {})

function onChange(value: any) {
  emit('change', value)
}

/**
 * 声明式规则。旧版对 string validator 做 eval —— 这里只接受函数；
 * 字符串 validator 丢弃执行器，保留其余约束字段。
 */
const rules = computed(() => {
  const custom = propsInfo.value.rules
  const list: Array<Record<string, any>> = []
  if (custom && Array.isArray(custom)) {
    for (const rule of custom) {
      if (rule && typeof rule.validator === 'string') {
        const { validator: _drop, ...rest } = rule
        list.push(rest)
      } else {
        list.push({ ...rule })
      }
    }
  } else {
    list.push({
      message: propsInfo.value.err_msg || `${labelText(props.formfield.label)}不能为空`,
      trigger: 'blur',
      required: props.formfield.required !== false
    })
  }
  if (props.formfield.input_type === 'JsonInput') {
    list.push({ validator: jsonTextValidator, trigger: 'blur' })
  }
  return list
})

function validate(): Promise<void> {
  const child = componentFormRef.value as { validate?: () => Promise<void> } | null
  if (props.formfield.trigger_type === 'CHILD_FORMS' && child?.validate) {
    return child.validate()
  }
  return Promise.resolve()
}

defineExpose({ validate })
</script>
