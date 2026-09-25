/**
 * input_type → 组件注册表。
 * 不用全局 app.component：字段驱动表单按名解析组件，注册表是唯一入口。
 */
import type { Component } from 'vue'
import TextInput from './items/TextInput.vue'
import PasswordInput from './items/PasswordInput.vue'
import JsonInput from './items/JsonInput.vue'
import DatePicker from './items/DatePicker.vue'
import SwitchInput from './items/SwitchInput.vue'
import Slider from './items/Slider.vue'
import SingleSelect from './items/SingleSelect.vue'
import MultiSelect from './items/MultiSelect.vue'
import Radio from './items/Radio.vue'
import RadioRow from './items/RadioRow.vue'
import RadioCard from './items/RadioCard.vue'
import RadioButton from './items/RadioButton.vue'
import ObjectCard from './items/ObjectCard.vue'
import ArrayObjectCard from './items/ArrayObjectCard.vue'
import TabCard from './items/TabCard.vue'
import TableRadio from './items/TableRadio.vue'
import TableCheckbox from './items/TableCheckbox.vue'
import TableColumn from './items/TableColumn.vue'
import ProgressTableItem from './items/ProgressTableItem.vue'
import TooltipLabel from './items/TooltipLabel.vue'

export const fieldComponents: Record<string, Component> = {
  TextInput,
  PasswordInput,
  JsonInput,
  DatePicker,
  SwitchInput,
  Slider,
  SingleSelect,
  MultiSelect,
  Radio,
  RadioRow,
  RadioCard,
  RadioButton,
  ObjectCard,
  ArrayObjectCard,
  TabCard,
  TableRadio,
  TableCheckbox,
  TableColumn,
  ProgressTableItem
}

export const labelComponents: Record<string, Component> = {
  TooltipLabel
}

export function resolveFieldComponent(inputType: string | undefined): Component {
  if (inputType && fieldComponents[inputType]) return fieldComponents[inputType]!
  return TextInput
}

export function resolveLabelComponent(inputType: string | undefined): Component | null {
  if (inputType && labelComponents[inputType]) return labelComponents[inputType]!
  return null
}
