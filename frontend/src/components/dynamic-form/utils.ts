import type { FormField, FormFieldLabel, FormValues } from './types'

/** 按点路径取值，支持 `a.0.b` 这类嵌套（嵌套子表单用） */
export function getByPath(obj: any, path: string): any {
  if (!path) return undefined
  return path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj)
}

export function cloneValue<T>(value: T): T {
  if (value == null || typeof value !== 'object') return value
  return JSON.parse(JSON.stringify(value)) as T
}

export function isStringLabel(label: FormFieldLabel | undefined): label is string {
  return typeof label === 'string'
}

export function labelText(label: FormFieldLabel | undefined): string {
  if (!label) return ''
  return isStringLabel(label) ? label : label.label
}

/** 初始化字段默认值：外部值优先，其次 default_value（false 视为显式值） */
export function initDefaultData(field: FormField, formValue: FormValues): void {
  const current = formValue[field.field]
  const empty =
    current === undefined || current === null || current === '' || (Array.isArray(current) && current.length === 0)
  if (empty && current !== false && field.default_value !== undefined && field.default_value !== null) {
    formValue[field.field] = cloneValue(field.default_value)
  }
}

/**
 * relation_show_field_dict 是否满足。
 * 语义：所有约束键都需「有值」，且值落在允许列表内（空列表 = 有值即显示）。
 */
export function shouldShow(field: FormField, formValue: FormValues): boolean {
  const dict = field.relation_show_field_dict
  if (!dict) return true
  const keys = Object.keys(dict)
  if (!keys.length) return true
  return keys.every((key) => {
    const v = getByPath(formValue, key)
    if (v === undefined || v === null || v === '') return false
    const allowed = dict[key]
    if (allowed && allowed.length > 0) return allowed.includes(v)
    return true
  })
}

/** 判断某个字段是否被 relation_trigger_field_dict 监听 */
export function watchesField(field: FormField, depPath: string): boolean {
  const dict = field.relation_trigger_field_dict
  return !!dict && Object.prototype.hasOwnProperty.call(dict, depPath)
}

/** 依赖字段新值是否命中触发条件 */
export function triggerHit(field: FormField, depPath: string, value: any): boolean {
  const dict = field.relation_trigger_field_dict
  if (!dict) return false
  const allowed = dict[depPath]
  if (allowed && allowed.length > 0) return allowed.includes(value)
  return value !== undefined && value !== null && value !== ''
}

/** JSON 文本校验（JsonInput 用；不用 eval） */
export function jsonTextValidator(_rule: any, value: any, callback: (e?: Error) => void): void {
  if (value === undefined || value === null || value === '') {
    callback()
    return
  }
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  try {
    JSON.parse(text)
    callback()
  } catch {
    callback(new Error('JSON 格式不正确'))
  }
}
