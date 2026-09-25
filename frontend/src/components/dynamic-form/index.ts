/**
 * 动态表单引擎 —— 全平台共享组件。
 *
 * 最小用法：
 * ```vue
 * <DynamicForm ref="form" v-model="values" :fields="fields" />
 * ```
 * ```ts
 * await form.validate()  // 通过则 resolve，失败 reject
 * ```
 */
export { default as DynamicForm } from './DynamicForm.vue'
export { default as FormItem } from './FormItem.vue'
export { default as FormItemLabel } from './FormItemLabel.vue'
export {
  fieldComponents,
  labelComponents,
  resolveFieldComponent,
  resolveLabelComponent
} from './registry'
export type {
  Attrs,
  ColorItem,
  DynamicFormExpose,
  FormField,
  FormFieldLabel,
  FormTrigger,
  FormValues,
  PropsInfo,
  TableColumnConfig,
  ViewCardItem
} from './types'
export { cloneValue, getByPath, initDefaultData, jsonTextValidator, labelText, shouldShow } from './utils'
