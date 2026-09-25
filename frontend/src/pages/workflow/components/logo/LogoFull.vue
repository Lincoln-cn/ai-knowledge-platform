<template>
  <svg viewBox="0 0 122 36" xmlns="http://www.w3.org/2000/svg" :height="height">
    <text x="0" y="26" font-size="20" fill="currentColor">AI Platform</text>
  </svg>
</template>
<script setup lang="ts">
defineOptions({ name: 'LogoFull' })

defineProps({
  height: {
    type: String,
    default: '36px'
  }
})
</script>
<style lang="scss" scoped></style>
