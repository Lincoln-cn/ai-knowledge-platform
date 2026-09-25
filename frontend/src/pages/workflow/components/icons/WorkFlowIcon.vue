<template>
  <el-icon v-if="resolved" class="app-icon">
    <component :is="resolved" />
  </el-icon>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { Warning, CopyDocument, FullScreen, Fold, Expand, Rank } from '@element-plus/icons-vue'

defineOptions({ name: 'WorkFlowIcon' })

const props = withDefaults(
  defineProps<{
    iconName?: string
  }>(),
  {
    iconName: ''
  }
)

/** 旧工程的 app-* 自定义图标名 → Element Plus 图标 */
const map: Record<string, any> = {
  'app-warning': Warning,
  'app-copy': CopyDocument,
  'app-magnify': FullScreen,
  'app-fitview': FullScreen,
  'app-retract': Fold,
  'app-extend': Expand,
  'app-beautify': Rank
}

const resolved = computed(() => {
  if (!props.iconName) return null
  return map[props.iconName] ?? props.iconName
})
</script>
<style lang="scss" scoped></style>
