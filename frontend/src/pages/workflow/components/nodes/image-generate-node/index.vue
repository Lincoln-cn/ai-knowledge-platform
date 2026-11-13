<template>
  <NodeContainer :nodeModel="nodeModel">
    <el-card shadow="never" class="card-never" style="--el-card-padding: 12px">
      <SchemaForm :node-model="nodeModel" :fields="fields" />
      <p class="multimodal-hint">
        读流程变量里的<strong>提示词</strong>（默认 <code>query</code>），把图片存进文件存储，
        流程变量里只留键与类型（<code>image_key</code> / <code>image_mime</code>）。
      </p>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import SchemaForm from '@/pages/workflow/components/common/SchemaForm.vue'
import { NODE_CONFIG_FIELDS } from '@/api/workflow'

/**
 * 图像生成节点面板：控件全部由目录声明。
 *
 * ★ 提示词从流程变量取而不是写死在配置里 —— 写死等于这个节点只能生成同一张图，
 *   那它就不该出现在流程里。
 */
defineProps<{ nodeModel: any }>()

const fields = NODE_CONFIG_FIELDS.IMAGE_GENERATE_NODE
</script>

<style scoped>
.multimodal-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
