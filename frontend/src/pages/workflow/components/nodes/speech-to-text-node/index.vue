<template>
  <NodeContainer :nodeModel="nodeModel">
    <el-card shadow="never" class="card-never" style="--el-card-padding: 12px">
      <SchemaForm :node-model="nodeModel" :fields="fields" />
      <p class="multimodal-hint">
        读流程变量里的<strong>音频文件键</strong>（默认 <code>audio_key</code>，与语音合成节点的产出同名），
        把识别出的文字写进 <code>speech_transcript</code>。
      </p>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import SchemaForm from '@/pages/workflow/components/common/SchemaForm.vue'
import { NODE_CONFIG_FIELDS } from '@/api/workflow'

/**
 * 语音识别节点面板：控件全部由目录声明。
 *
 * ★ 识别出空文本是**成功**（一段没人说话的录音），所以面板不该提示"必须有内容"。
 */
defineProps<{ nodeModel: any }>()

const fields = NODE_CONFIG_FIELDS.SPEECH_TO_TEXT_NODE
</script>

<style scoped>
.multimodal-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
