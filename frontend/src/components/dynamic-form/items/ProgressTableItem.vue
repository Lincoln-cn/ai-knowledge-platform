<template>
  <div class="progress-table-item">
    <el-popover placement="top-start" :title="String(row[textField] ?? '')" :width="200" trigger="hover">
      <template #reference>
        <el-progress v-bind="$attrs" :percentage="Number(row[valueField] ?? 0)" />
      </template>
      <div>
        <el-row v-for="(item, index) in viewCard" :key="index">
          <el-col :span="6">{{ item.title }}</el-col>
          <el-col :span="18"><span class="value">{{ cellText(item) }}</span></el-col>
        </el-row>
      </div>
    </el-popover>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { TableColumnConfig, ViewCardItem } from '../types'

/** 进度列 + 悬停摘要。旧版用 eval 拼模板字符串，这里只按字段路径取值。 */
const props = defineProps<{
  column: TableColumnConfig
  row: any
  modelValue?: any
  formField?: any
  formValue?: any
  formfieldList?: any
  field?: string
  otherParams?: any
  view?: boolean
  disabled?: boolean
  trigger?: any
}>()

const propsInfo = computed(() => props.column.props_info || {})
const textField = computed(() => props.column.text_field || 'key')
const valueField = computed(() => props.column.value_field || 'value')
const viewCard = computed(() => propsInfo.value.view_card || [])

function cellText(item: ViewCardItem): string {
  const v = props.row?.[item.value_field]
  return v == null ? '' : String(v)
}
</script>
<style scoped>
.progress-table-item .value {
  float: right;
  color: var(--color-ink);
  font-weight: 500;
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  font-variant-numeric: tabular-nums;
}
</style>
