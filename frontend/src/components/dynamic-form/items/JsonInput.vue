<template>
  <div class="json-input">
    <el-input
      v-bind="$attrs"
      type="textarea"
      :rows="6"
      :disabled="disabled"
      :model-value="text"
      :class="{ 'is-json-error': !!parseError }"
      @update:model-value="onInput"
    />
    <div class="json-bar">
      <span v-if="parseError" class="json-err">JSON 格式不正确</span>
      <el-button text size="small" type="info" :disabled="disabled" @click="format">格式化</el-button>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FormField, FormTrigger, FormValues } from '../types'

/** JSON 文本编辑。旧版用 vue-codemirror；本工程不加依赖，textarea + 解析校验足够。 */
defineOptions({ name: 'JsonInput', inheritAttrs: false })

const props = defineProps<{
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

const emit = defineEmits<{
  'update:modelValue': [value: any]
}>()

const cacheText = ref<string>('')
const parseError = ref(false)

const text = computed(() => {
  if (cacheText.value) return cacheText.value
  if (props.modelValue === undefined || props.modelValue === null) return ''
  if (typeof props.modelValue === 'string') return props.modelValue
  try {
    return JSON.stringify(props.modelValue, null, 2)
  } catch {
    return String(props.modelValue)
  }
})

watch(
  () => props.modelValue,
  () => {
    cacheText.value = ''
    parseError.value = false
  }
)

function onInput(v: string) {
  cacheText.value = v
  if (!v.trim()) {
    parseError.value = false
    emit('update:modelValue', undefined)
    return
  }
  try {
    const parsed = JSON.parse(v)
    parseError.value = false
    emit('update:modelValue', parsed)
  } catch {
    parseError.value = true
  }
}

function format() {
  try {
    const parsed = JSON.parse(text.value || '{}')
    cacheText.value = JSON.stringify(parsed, null, 2)
    parseError.value = false
    emit('update:modelValue', parsed)
  } catch {
    parseError.value = true
  }
}

/** 供 FormItem 追加到 rules 的校验器 */
function validateRules(_rule: any, _value: any, callback: (e?: Error) => void) {
  if (parseError.value) {
    callback(new Error('JSON 格式不正确'))
    return
  }
  callback()
}

defineExpose({ validate_rules: validateRules })
</script>
<style scoped>
.json-input {
  width: 100%;
  min-width: 0;
}

.json-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-xs);
  margin-top: var(--space-2xs);
}

.json-err {
  color: var(--color-danger);
  font-size: var(--text-2xs);
  margin-right: auto;
}

.is-json-error :deep(.el-textarea__inner) {
  border-color: var(--color-danger);
}

.is-json-error :deep(.el-textarea__inner:focus) {
  border-color: var(--color-danger);
}
</style>
