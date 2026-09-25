<template>
  <div class="table-radio">
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
      @current-change="onCurrent"
    >
      <el-table-column width="50px">
        <template #default="scope">
          <input type="radio" :checked="modelValue === scope.row[valueField]" />
        </template>
      </el-table-column>
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
    <div v-if="modelValue" class="msg">
      {{ activeMsg }}
      <span class="active">{{ activeText }}</span>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import type { FormField, TableColumnConfig } from '../types'
import TableColumn from './TableColumn.vue'

defineOptions({ name: 'TableRadio', inheritAttrs: false })

const props = defineProps<{
  modelValue?: any
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
  change: [field: FormField]
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

function onCurrent(row: any) {
  if (!row) return
  emit('update:modelValue', row[valueField.value])
  emit('change', props.formField)
}

watch(
  () => tableData.value,
  () => {
    if (tableData.value.length > 0) {
      const first = tableData.value[0]
      let next = first?.[valueField.value]
      if (props.modelValue != null) {
        const row = optionList.value.find((f: any) => f[valueField.value] === props.modelValue)
        if (row) next = row[valueField.value]
      }
      emit('update:modelValue', next)
    } else {
      emit('update:modelValue', undefined)
    }
    emit('change', props.formField)
  }
)

const activeText = computed(() => {
  if (props.modelValue != null) {
    const row = optionList.value.find((f: any) => f[valueField.value] === props.modelValue)
    return row ? row[textField.value] : props.modelValue
  }
  return ''
})
</script>
<style scoped>
.table-radio .header {
  display: flex;
  justify-content: space-between;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.table-radio .title {
  color: var(--color-ink);
  font-size: var(--text-sm);
  line-height: var(--leading-tight);
}

.table-radio .filter {
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
