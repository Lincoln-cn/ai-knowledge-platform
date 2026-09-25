<template>
  <svg :height="height" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 232.4409 232.4409">
    <title>AI</title>
    <path
      class="cls-1"
      d="M166.8344,48.8968H65.6064A33.7544,33.7544,0,0,0,31.89,82.6131v57.07A33.7548,33.7548,0,0,0,65.6064,173.4h101.228a33.7549,33.7549,0,0,0,33.7168-33.7168v-57.07A33.7545,33.7545,0,0,0,166.8344,48.8968Zm2.831,90.4457a6.0733,6.0733,0,0,1-6.0732,6.0733H114.2168a43.5922,43.5922,0,0,0-21.3313,5.5757l-16.5647,9.2946v-14.87h-7.472a6.0733,6.0733,0,0,1-6.0733-6.0733v-60.5a6.0733,6.0733,0,0,1,6.0733-6.0733h94.7434a6.0733,6.0733,0,0,1,6.0732,6.0733Z"
    />
  </svg>
</template>
<script setup lang="ts">
defineOptions({ name: 'LogoIcon' })

defineProps({
  height: {
    type: String,
    default: '36px'
  }
})
</script>
<style lang="scss" scoped>
.cls-1 {
  fill: var(--el-color-primary);
}
</style>
