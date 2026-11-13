<template>
  <NodeContainer :nodeModel="nodeModel">
    <!--
      画布卡片的只读摘要：重排节点最该一眼看到的是「排前几名」——
      「重排」这个词本身不说明窗口，而窗口直接决定这一节点花多少时间。
    -->
    <template #essential>
      重排 · 前 {{ topKLabel }} 名
    </template>

    <h5 class="title-decoration-1 mb-8">节点设置</h5>
    <el-card shadow="never" class="card-never">
      <SchemaForm
        ref="schemaFormRef"
        :node-model="nodeModel"
        :fields="fields"
        @changed="schemaTick++"
      />
      <!--
        ★ 说清它读的是谁：这个节点不自己去检索，只对上游的候选重排。
          写在面板上而不是只在 issue 里，是因为「重排节点为什么跑失败」最常见的原因就是没接检索节点。
      -->
      <p class="reranker-hint">
        它读上游检索节点写进流程变量 <code>retrieval_hits</code> 的候选，重排后写
        <code>rerank_result</code>（下游引用它就够）。没有上游候选时会明确报错，不会静默跳过。
      </p>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import SchemaForm from '@/pages/workflow/components/common/SchemaForm.vue'
import { NODE_CONFIG_FIELDS } from '@/api/workflow'

/**
 * 面板由目录驱动：`topK` / `question` 两个字段都来自 `node-types.json` 的 `configFields`。
 *
 * ★ 这个节点是很短的验算：它没有任何节点特有的控件，也不需要自己的校验（topK 的类型与范围由目录的
 *   `type: 'number'` 表达）。如果 SchemaForm 连这种节点都渲染不出来，那它就不该被推广到别的面板。
 */
defineProps<{ nodeModel: any }>()

const fields = NODE_CONFIG_FIELDS.RERANKER_NODE

const schemaFormRef = ref<InstanceType<typeof SchemaForm>>()
const schemaTick = ref(0)

const topKLabel = computed(() => {
  void schemaTick.value
  // ★ 目录给的默认值是 5（与 RerankPort 的离线读数一致）；这里只做显示，不改值。
  const raw = schemaFormRef.value?.labelOf('topK') ?? ''
  return raw || '5'
})
</script>

<style scoped>
.reranker-hint {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.reranker-hint code {
  padding: 0 3px;
  font-size: 12px;
  background: var(--el-fill-color-light);
  border-radius: 3px;
}
</style>
