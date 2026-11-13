<template>
  <NodeContainer :nodeModel="nodeModel">
    <el-card shadow="never" class="card-never" style="--el-card-padding: 12px">
      <SchemaForm :node-model="nodeModel" :fields="fields" />
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import SchemaForm from '@/pages/workflow/components/common/SchemaForm.vue'
import { NODE_CONFIG_FIELDS } from '@/api/workflow'

/**
 * 回复节点面板：控件全部由目录（`node-types.json`）声明，见 `SchemaForm` 的说明。
 *
 * ★ 这里**去掉**了原来那个「引用变量 / 自定义」的下拉与 `NodeCascader`。
 *   不是顺手删的：后端 `ReplyNodeExecutor` 对 `replyType != content` 直接抛
 *   「按上游字段回复要等 AI 对话节点落地」，而 `fields` 这个存值位置后端一个字都不读。
 *   也就是说那一支是「选了能存、一跑就抛」的假功能 —— 留着一个跑不通的选项，
 *   比没有这个选项更糟：它让用户以为配好了。
 *   等真的实现了「按上游字段回复」，它应该在 `configFields` 里作为第二种
 *   `replyType` 取值出现，并配一个 `visibleWhen` 之类的显隐声明 —— 而不是回到手写面板。
 */
defineProps<{ nodeModel: any }>()

const fields = NODE_CONFIG_FIELDS.REPLY_NODE
</script>
