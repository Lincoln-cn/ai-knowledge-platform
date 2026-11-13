<template>
  <NodeContainer :nodeModel="nodeModel">
    <!-- 画布卡片的只读摘要：这个节点最该一眼看到的是「要收几项」 -->
    <template #essential>
      表单 ·
      <template v-if="fieldCount">{{ fieldCount }} 个字段</template>
      <template v-else>未定义字段</template>
    </template>

    <h5 class="title-decoration-1 mb-8">节点设置</h5>
    <el-card shadow="never" class="card-never">
      <el-form @submit.prevent :model="form_data" label-position="top">
        <el-form-item label="表单字段">
          <div v-if="!fieldCount" class="empty-hint">还没有字段。加一个，用户运行时才会被问到。</div>
          <div v-for="(field, index) in form_data.formFields" :key="index" class="field-row">
            <el-input
              :model-value="field.label"
              placeholder="字段名（问用户的话）"
              class="field-label"
              @update:model-value="updateField(index, 'label', $event)"
            />
            <el-input
              :model-value="field.key"
              placeholder="变量名"
              class="field-key"
              @update:model-value="updateField(index, 'key', $event)"
            />
            <el-select
              :model-value="field.type"
              class="field-type"
              @change="updateField(index, 'type', $event)"
            >
              <el-option label="单行文本" value="text" />
              <el-option label="多行文本" value="textarea" />
              <el-option label="数字" value="number" />
              <el-option label="日期" value="date" />
              <el-option label="下拉选择" value="select" />
            </el-select>
            <el-checkbox
              :model-value="field.required"
              @change="updateField(index, 'required', $event)"
            >
              必填
            </el-checkbox>
            <el-button link type="danger" @click="removeField(index)">删除</el-button>
          </div>
          <el-button class="mt-8" @click="addField">添加字段</el-button>
        </el-form-item>

        <el-form-item label="引导文案">
          <el-input
            :model-value="form_data.contentFormat"
            type="textarea"
            :rows="4"
            placeholder="填表格前对用户说的话"
            @update:model-value="updateContentFormat"
          />
          <div class="doc-hint">
            <code v-pre>{{form}}</code> 会被替换成表单本身，别删它。
          </div>
        </el-form-item>

        <el-form-item label="提交即结束">
          <el-switch :model-value="form_data.asResult" @change="updateAsResult" />
          <div class="doc-hint">
            <template v-if="form_data.asResult">用户提交这份表单后，流程直接结束，不再往下走。</template>
            <template v-else>用户提交后继续走后面的节点，填的内容写进 form_data。</template>
          </div>
        </el-form-item>
      </el-form>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import { set } from 'lodash-es'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import { ref, computed } from 'vue'

const props = defineProps<{ nodeModel: any }>()

/**
 * `props.nodeModel` 是 LogicFlow 的模型对象，不是 Vue 的响应式对象：
 * 只改 `properties.node_data` 里的字段，模板不会重渲染（同 search-dataset-node 的坑）。
 * 这里同样用版本号 `tick` 建依赖。
 *
 * ★ 数组一律**换新引用**后再写回：原地 push/splice 不会让 computed 重算，
 *   而"看起来改了、保存下去还是旧的"是这一片最容易出的错。
 */
const tick = ref(0)

const defaultContentFormat = '你好，请先填写下面的内容：\n{{form}}\n填写后点击【提交】。'

const form_data = computed({
  get: () => {
    void tick.value
    const existing = props.nodeModel.properties.node_data
    if (existing) return existing
    set(props.nodeModel.properties, 'node_data', {
      formFields: [],
      contentFormat: defaultContentFormat,
      asResult: true
    })
    return props.nodeModel.properties.node_data
  },
  set: (value) => {
    set(props.nodeModel.properties, 'node_data', value)
    tick.value++
  }
})

const fieldCount = computed(() => {
  void tick.value
  return Array.isArray(form_data.value.formFields) ? form_data.value.formFields.length : 0
})

function writeFields(fields: any[]) {
  // 换新数组再写回：原地改元素不会触发重渲染
  set(props.nodeModel.properties.node_data, 'formFields', fields)
  tick.value++
}

function nextKey(): string {
  const taken = new Set(
    (props.nodeModel.properties.node_data?.formFields ?? []).map((f: any) => f?.key)
  )
  let n = 1
  while (taken.has(`field_${n}`)) n += 1
  return `field_${n}`
}

function addField() {
  const fields = [...(props.nodeModel.properties.node_data?.formFields ?? [])]
  fields.push({ key: nextKey(), label: `字段 ${fields.length + 1}`, type: 'text', required: false })
  writeFields(fields)
}

function removeField(index: number) {
  const fields = [...(props.nodeModel.properties.node_data?.formFields ?? [])]
  fields.splice(index, 1)
  writeFields(fields)
}

function updateField(index: number, key: string, value: unknown) {
  const fields = (props.nodeModel.properties.node_data?.formFields ?? []).map(
    (f: any, i: number) => (i === index ? { ...f, [key]: value } : f)
  )
  writeFields(fields)
}

function updateContentFormat(value: string) {
  set(props.nodeModel.properties.node_data, 'contentFormat', value)
  tick.value++
}

function updateAsResult(value: boolean) {
  set(props.nodeModel.properties.node_data, 'asResult', value)
  tick.value++
}
</script>

<style lang="scss" scoped>
:deep(.el-form-item--label-top .el-form-item__label) {
  padding-right: 0;
}

.empty-hint,
.doc-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.empty-hint {
  margin-bottom: 4px;
}

.field-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;

  .field-label,
  .field-key {
    flex: 1;
  }

  .field-type {
    width: 110px;
  }
}
</style>
