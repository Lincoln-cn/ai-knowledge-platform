<template>
  <!-- NONE：整项不渲染。不展示比展示后禁用更清晰 -->
  <el-form-item v-if="mode === 'TOGGLEABLE' || mode === 'ALWAYS_ON'" label="思考模式">
    <el-switch
      :model-value="effectiveValue"
      :disabled="mode === 'ALWAYS_ON'"
      @change="onChange"
    />
    <el-tag v-if="mode === 'ALWAYS_ON'" size="small" effect="plain" type="warning" class="ml8">
      强制开启
    </el-tag>
    <div class="hint micro">
      <template v-if="mode === 'ALWAYS_ON'">
        该模型始终输出思考过程，无法关闭。结构化生成场景不会把该模型列入候选。
      </template>
      <template v-else>
        开启后回答更审慎，但更慢、更耗 token。
      </template>
    </div>
  </el-form-item>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ThinkingMode } from '@/api/models'

const props = defineProps<{
  mode: ThinkingMode
  modelValue: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

/** ALWAYS_ON 置开且禁用；NONE 不渲染；TOGGLEABLE 跟随用户 */
const effectiveValue = computed(() => (props.mode === 'ALWAYS_ON' ? true : props.modelValue))

const onChange = (val: boolean | string | number) => {
  if (props.mode === 'ALWAYS_ON') return
  emit('update:modelValue', Boolean(val))
}
</script>

<style scoped>
.ml8 {
  margin-left: var(--space-xs);
}

.hint {
  width: 100%;
  color: var(--color-muted);
  line-height: var(--leading-body);
}
</style>
