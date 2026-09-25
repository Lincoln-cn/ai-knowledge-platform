<template>
  <el-form
    ref="ruleFormRef"
    :model="formValue"
    label-width="130px"
    label-suffix=":"
    @submit.prevent
  >
    <slot :form_value="formValue" />
    <template v-for="item in fields" :key="item.field">
      <FormItem
        v-if="show(item)"
        ref="formFieldRef"
        :formfield="item"
        :model-value="formValue[item.field]"
        :trigger="triggerFn"
        :view="view"
        :other-params="otherParams"
        :form-value="formValue"
        :formfield-list="fields"
        :parent-field="parentField"
        @change="change(item, $event)"
      />
    </template>
  </el-form>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import FormItem from './FormItem.vue'
import type { DynamicFormExpose, FormField, FormTrigger, FormValues } from './types'
import { cloneValue, getByPath, initDefaultData, shouldShow, triggerHit, watchesField } from './utils'

/**
 * 字段驱动的动态表单引擎。
 * 给 `fields` + `v-model` 即可渲染；`validate()` 拿到校验结果。
 * 不依赖任何特定 UI 框架或状态库；远程选项由调用方通过 `trigger` 注入。
 */
defineOptions({ name: 'DynamicForm' })

const props = withDefaults(
  defineProps<{
    /** 字段配置数组 */
    fields: Array<FormField>
    /** 表单值 */
    modelValue?: FormValues
    /** 只读 */
    view?: boolean
    defaultItemWidth?: string
    /** 透传给触发器与子表单的额外参数 */
    otherParams?: any
    /** 嵌套子表单时的父字段路径前缀 */
    parentField?: string
    /** 远程选项 / 子表单加载器，可选 */
    trigger?: FormTrigger
  }>(),
  {
    view: false,
    defaultItemWidth: '75%',
    otherParams: () => ({})
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: FormValues]
  change: [value: FormValues]
}>()

const formValue = ref<FormValues>({})
const ruleFormRef = ref<FormInstance>()
const formFieldRef = ref<Array<InstanceType<typeof FormItem>>>([])
const initializing = ref(false)

const triggerFn: FormTrigger = (field, values, other) => {
  if (props.trigger) return props.trigger(field, values, other)
  return Promise.resolve()
}

function show(field: FormField): boolean {
  return shouldShow(field, formValue.value)
}

function change(field: FormField, value: any) {
  formValue.value[field.field] = value
}

function emitValue() {
  emit('update:modelValue', formValue.value)
  emit('change', formValue.value)
}

watch(
  formValue,
  () => {
    if (initializing.value) return
    emitValue()
  },
  { deep: true }
)

function applyTriggerResult(
  field: FormField,
  ok: { option_list?: Array<any>; children?: Array<FormField> } | void
) {
  if (!ok || typeof ok !== 'object') return
  if (field.trigger_type === 'CHILD_FORMS' && ok.children) {
    field.children = ok.children
  } else if (ok.option_list) {
    field.option_list = ok.option_list
  }
}

function runTriggers(list: Array<FormField>, values: FormValues) {
  for (const item of list) {
    const dict = item.relation_trigger_field_dict
    if (!dict) continue
    for (const key of Object.keys(dict)) {
      if (!watchesField(item, key)) continue
      const v = getByPath(values, key)
      if (triggerHit(item, key, v)) {
        void triggerFn(item, values, props.otherParams).then((ok) => {
          applyTriggerResult(item, ok)
        })
      }
    }
    if (item.provider && item.method) {
      void triggerFn(item, values, props.otherParams).then((ok) => {
        applyTriggerResult(item, ok)
      })
    }
  }
}

/** 按 fields + 外部值填充表单（外部值优先，其次 default_value） */
function render(nextFields: Array<FormField>, data?: FormValues) {
  initializing.value = true
  const source = data ?? props.modelValue ?? {}
  const value: FormValues = {}
  for (const item of nextFields) {
    if (source[item.field] !== undefined) {
      value[item.field] = cloneValue(source[item.field])
    } else if (item.default_value !== undefined && item.default_value !== null) {
      value[item.field] = cloneValue(item.default_value)
    } else {
      value[item.field] = undefined
    }
  }
  formValue.value = value
  for (const item of nextFields) {
    initDefaultData(item, formValue.value)
  }
  initializing.value = false
  runTriggers(nextFields, formValue.value)
}

watch(
  () => props.fields,
  (next) => render(next || [], props.modelValue),
  { immediate: true, deep: true }
)

watch(
  () => props.modelValue,
  (next) => {
    if (initializing.value || !next) return
    // 外部整体替换时同步；字段级改动由 FormItem change 写回
    if (next !== formValue.value) {
      for (const k of Object.keys(next)) {
        if (formValue.value[k] !== next[k]) {
          formValue.value[k] = next[k]
        }
      }
    }
  },
  { deep: true }
)

/** 字段值变化后，刷新声明了 relation_trigger_field_dict 的依赖项（替代旧版 mitt bus） */
watch(
  formValue,
  (val) => {
    if (initializing.value) return
    for (const item of props.fields || []) {
      const dict = item.relation_trigger_field_dict
      if (!dict) continue
      for (const key of Object.keys(dict)) {
        if (!watchesField(item, key)) continue
        const v = getByPath(val, key)
        if (triggerHit(item, key, v)) {
          void triggerFn(item, val, props.otherParams).then((ok) => {
            applyTriggerResult(item, ok)
          })
        }
      }
    }
  },
  { deep: true }
)

function validate(): Promise<void> {
  const formPart = ruleFormRef.value
    ? ruleFormRef.value
        .validate()
        .then(() => undefined)
        .catch((e: unknown) => Promise.reject(e))
    : Promise.resolve()
  const childParts = formFieldRef.value.map((item) => item.validate())
  return Promise.all([formPart, ...childParts]).then(() => undefined)
}

function clearValidate() {
  ruleFormRef.value?.clearValidate()
}

function getValues(): FormValues {
  return formValue.value
}

const exposeApi: DynamicFormExpose = {
  validate,
  getValues,
  initDefaultData: (field: FormField) => initDefaultData(field, formValue.value),
  clearValidate
}

defineExpose(exposeApi)
</script>
