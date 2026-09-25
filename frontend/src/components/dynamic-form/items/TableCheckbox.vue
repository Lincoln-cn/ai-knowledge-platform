<template>
  <div class="table-checkbox">
    <div class="header">
      <div class="title">{{ title }}</div>
      <el-input
        v-model="filterText"
        :validate-event="false"
        placeholder="请输入关键字搜索"
        class="filter"
        clearable
      >
        <template #prepend>
          <el-button :icon="Search" />
        </template>
      </el-input>
    </div>

    <el-table
      :data="tableData"
      highlight-current-row
      class="df-table"
      @selection-change="onSelection"
    >
      <el-table-column type="selection" width="55" :selectable="() => !disabled && !view" />
      <el-table-column
        v-for="(column, index) in tableColumns"
        :key="index"
        :prop="column.property"
        :label="column.label"
        v-bind="column.attrs"
      >
        <template #default="scope">
          <TableColumn v-if="column.type === 'component'" :column="column" :row="scope.row" />
          <span v-else>{{ scope.row[column.property] }}</span>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="modelValue && modelValue.length" class="msg">
      {{ activeMsg }}
      <span class="active">{{ activeText }}</span>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import type { FormField, TableColumnConfig } from '../types'
import TableColumn from './TableColumn.vue'

defineOptions({ name: 'TableCheckbox', inheritAttrs: false })

const props = defineProps<{
  modelValue?: Array<any>
  formField: FormField
  formValue?: any
  formfieldList?: Array<FormField>
  field?: string
  otherParams?: any
  view?: boolean
  disabled?: boolean
  trigger?: any
}>()

const emit = defineEmits<{
  'update:modelValue': [value: any]
}>()

const filterText = ref('')

const propsInfo = computed(() => props.formField.props_info || {})
const activeMsg = computed(() => propsInfo.value.active_msg || '')
const title = computed(() => propsInfo.value.title || '')
const tableColumns = computed(() => (propsInfo.value.table_columns || []) as TableColumnConfig[])
const optionList = computed(() => props.formField.option_list || [])
const textField = computed(() => props.formField.text_field || 'key')
const valueField = computed(() => props.formField.value_field || 'value')

const tableData = computed(() => {
  const list = optionList.value
  if (!list) return []
  if (filterText.value) {
    return list.filter((item: any) =>
      tableColumns.value.some((c) => {
        if (c.type === 'component') return false
        const v = item[c.property]
        return typeof v === 'string' ? v.indexOf(filterText.value) >= 0 : false
      })
    )
  }
  return list.filter((item: any) => item[valueField.value])
})

function onSelection(rows: any[]) {
  emit(
    'update:modelValue',
    rows.map((row) => row[valueField.value])
  )
}

const activeText = computed(() => {
  const selected = props.modelValue
  if (selected && selected.length) {
    const rows = optionList.value.filter((f: any) => selected.includes(f[valueField.value]))
    const names = rows.map((row: any) => row[textField.value])
    return names.length > 3 ? names.slice(0, 3).join(',') + '...' : names.join(',')
  }
  return ''
})
</script>
<style scoped>
.table-checkbox .header {
  display: flex;
  justify-content: space-between;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.table-checkbox .title {
  color: var(--color-ink);
  font-size: var(--text-sm);
  line-height: var(--leading-tight);
}

.table-checkbox .filter {
  width: 45%;
  min-width: 0;
}

.df-table {
  width: 100%;
  --el-bg-color: var(--color-sunken);
}

.msg {
  margin-top: var(--space-sm);
  color: var(--color-ink-2);
  font-size: var(--text-sm);
}

.msg .active {
  margin-left: var(--space-3xs);
  color: var(--color-accent);
}
</style>
