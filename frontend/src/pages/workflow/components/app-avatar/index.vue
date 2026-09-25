<template>
  <el-avatar :size="30" :style="{ background: props.pinyinColor ? avatarColor : undefined }" style="flex-shrink: 0" v-bind="$attrs">
    <slot>{{ firstUserName }}</slot>
  </el-avatar>
</template>
<script setup lang="ts">
import { computed } from 'vue'
defineOptions({ name: 'AppAvatar' })
const props = defineProps({
  name: {
    type: String,
    default: ''
  },
  pinyinColor: {
    type: Boolean,
    default: false
  }
})

const firstUserName = computed(() => props.name?.substring(0, 1) ?? '')

const colours = [
  '#3370FF',
  '#4954E6',
  '#F54A45',
  '#00B69D',
  '#2CA91F',
  '#98B600',
  '#F80F80',
  '#D136D1',
  '#F01D94',
  '#7F3BF5',
  '#8F959E'
]

const avatarColor = computed(() => {
  const code = props.name ? props.name.charCodeAt(0) : 0
  return colours[Math.abs(code % colours.length)]
})
</script>
<style lang="scss" scoped></style>
