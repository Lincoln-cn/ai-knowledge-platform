<template>
  <el-icon class="send-icon" :size="size">
    <Promotion />
  </el-icon>
</template>
<script setup lang="ts">
defineOptions({ name: 'SendIcon' })

defineProps({
  size: {
    type: Number,
    default: 16
  }
})
</script>
<style lang="scss" scoped>
.send-icon {
  color: var(--el-color-primary);
}
</style>
