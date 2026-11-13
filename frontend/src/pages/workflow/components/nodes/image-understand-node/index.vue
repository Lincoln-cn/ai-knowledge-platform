<template>
  <NodeContainer :nodeModel="nodeModel">
    <el-card shadow="never" class="card-never" style="--el-card-padding: 12px">
      <SchemaForm :node-model="nodeModel" :fields="fields" />
      <p class="multimodal-hint">
        读流程变量里的<strong>图片文件键</strong>（不是图片本身），把描述写进
        <code>image_description</code>。
      </p>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import SchemaForm from '@/pages/workflow/components/common/SchemaForm.vue'
import { NODE_CONFIG_FIELDS } from '@/api/workflow'

/**
 * 图像理解节点面板：控件全部由目录声明（见 `SchemaForm`）。
 *
 * ★ 模型下拉按 `modelKind: CHAT` 过滤 —— 视觉输入是对话协议的扩展，不是另一种模型种类。
 *   这一点写在目录里而不是面板里：面板手写的过滤条件，在换一个人改目录时不会跟着动。
 */
defineProps<{ nodeModel: any }>()

const fields = NODE_CONFIG_FIELDS.IMAGE_UNDERSTAND_NODE
</script>

<style scoped>
.multimodal-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
