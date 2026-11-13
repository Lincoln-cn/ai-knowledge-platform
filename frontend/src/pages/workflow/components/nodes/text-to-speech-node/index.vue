<template>
  <NodeContainer :nodeModel="nodeModel">
    <el-card shadow="never" class="card-never" style="--el-card-padding: 12px">
      <SchemaForm :node-model="nodeModel" :fields="fields" />
      <p class="multimodal-hint">
        读流程变量里的<strong>文字</strong>，把音频存进文件存储，流程变量里只留键与格式
        （<code>audio_key</code> / <code>audio_format</code>）。
      </p>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import SchemaForm from '@/pages/workflow/components/common/SchemaForm.vue'
import { NODE_CONFIG_FIELDS } from '@/api/workflow'

/**
 * 语音合成节点面板：控件全部由目录声明。
 *
 * ★ 音色（`voice`）没有默认值：各上游的可选值完全不同（`alloy` / `nova` /
 *   `zh-CN-XiaoxiaoNeural`…），猜一个的结果是"大部分时候能跑、换了服务商就 400"。
 */
defineProps<{ nodeModel: any }>()

const fields = NODE_CONFIG_FIELDS.TEXT_TO_SPEECH_NODE
</script>

<style scoped>
.multimodal-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
