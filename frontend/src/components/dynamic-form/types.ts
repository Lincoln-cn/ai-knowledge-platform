/** 动态表单字段配置与值类型。对外导出，供应用配置 / 知识库向导 / 编排节点复用。 */

export type FormValues = Record<string, any>

export type FormFieldLabel =
  | string
  | {
      label: string
      input_type: string
      attrs?: Record<string, any>
    }

export interface ColorItem {
  color: string
  percentage: number
}

export interface Attrs {
  placeholder?: string
  labelWidth?: string
  labelSuffix?: string
  requireAsteriskPosition?: 'left' | 'right'
  color?: Array<ColorItem>
  [propName: string]: any
}

export interface TableColumnConfig {
  /** 字段名 | 组件名 */
  property: string
  label: string
  text_field?: string
  value_field?: string
  attrs?: Attrs
  /** default=取 row[property]；component=按 property 渲染组件 */
  type: 'component' | 'default'
  props_info?: PropsInfo
}

export interface ViewCardItem {
  title: string
  value_field: string
}

export interface PropsInfo {
  view_card?: Array<ViewCardItem>
  table_columns?: Array<TableColumnConfig>
  active_msg?: string
  title?: string
  style?: Record<string, any>
  item_style?: Record<string, any>
  form_style?: Record<string, any>
  /** Element Plus 表单规则（声明式；validator 必须是函数，不接受字符串） */
  rules?: Array<Record<string, any>>
  err_msg?: string
  tabs_label?: string
  add_msg?: string
  [propName: string]: any
}

export interface FormField {
  field: string
  /** 输入组件名，见 registry.ts 的 fieldComponents */
  input_type: string
  label?: FormFieldLabel
  required?: boolean
  default_value?: any
  /** {field: values[]} 依赖字段取值落在列表内才显示；列表为空表示有值即显示 */
  relation_show_field_dict?: Record<string, Array<any>>
  /** {field: values[]} 依赖字段变化且取值命中时触发 trigger */
  relation_trigger_field_dict?: Record<string, Array<any>>
  /** OPTION_LIST=刷新 option_list；CHILD_FORMS=刷新 children */
  trigger_type?: 'OPTION_LIST' | 'CHILD_FORMS'
  attrs?: Attrs
  props_info?: PropsInfo
  text_field?: string
  value_field?: string
  option_list?: Array<any>
  provider?: string
  method?: string
  children?: Array<FormField>
}

/** 远程触发回调：由调用方注入，本组件不绑定任何具体后端接口 */
export type FormTrigger = (
  field: FormField,
  formValue: FormValues,
  otherParams?: any
) => Promise<{ option_list?: Array<any>; children?: Array<FormField> } | void>

export interface DynamicFormExpose {
  validate: () => Promise<void>
  getValues: () => FormValues
  initDefaultData: (field: FormField) => void
  clearValidate: () => void
}
