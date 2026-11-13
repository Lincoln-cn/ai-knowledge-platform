<template>
  <NodeContainer :nodeModel="nodeModel">
    <!--
      画布卡片的只读摘要：这个节点最该一眼看到的是「抽哪一篇」。
      ★ 不用 docId 本身（UUID 看不出来是什么），用文档名；名字由表单组件回传。
    -->
    <template #essential>
      文档 ·
      <template v-if="documentLabel">{{ documentLabel }}</template>
      <template v-else>未选择</template>
    </template>

    <h5 class="title-decoration-1 mb-8">节点设置</h5>
    <el-card shadow="never" class="card-never">
      <SchemaForm
        ref="schemaFormRef"
        :node-model="nodeModel"
        :fields="fields"
        @changed="schemaTick++"
      />
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import SchemaForm from '@/pages/workflow/components/common/SchemaForm.vue'
import { NODE_CONFIG_FIELDS } from '@/api/workflow'

/**
 * 这个面板的控件、标签、必填、候选项来源、提示文案全部来自目录（`node-types.json`）：
 * 库 → 文档的级联由字段上的 `optionsSource` / `dependsOn` 表达。
 *
 * ★ 以前这里有 177 行手写表单（自己的 tick、自己的 kbList/docList、自己的校验、
 *   自己的"未解析也列出来"逻辑）。那些逻辑不是节点特有的，是所有"选库里某个东西"的面板共用的。
 */
defineProps<{ nodeModel: any }>()

const fields = NODE_CONFIG_FIELDS.DOCUMENT_EXTRACT_NODE

const schemaFormRef = ref<InstanceType<typeof SchemaForm>>()
/** 表单组件改了值之后 +1，让下面的 computed 重新去问它要显示名。 */
const schemaTick = ref(0)

const documentLabel = computed(() => {
  void schemaTick.value
  return schemaFormRef.value?.labelOf('docId') ?? ''
})
</script>
