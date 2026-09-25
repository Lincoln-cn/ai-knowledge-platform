<template>
  <!--
    三种情况，**顺序有意义**：
    ① 有真实图标（URL）→ 显示它
    ② 有名字没有图标 → 字母头像
    ③ 都没有 → 兜底图标
    ☠ 第 ③ 支必须有：左侧「添加组件」面板只渲染 `<component :is="...-icon" :size="32" />`，
    **不传 `item`** —— 少了兜底，面板里「应用调用」这一行就是一块空白。
  -->
  <AppAvatar
    v-if="isAppIcon(item?.icon)"
    shape="square"
    :size="32"
    style="background: none"
    class="mr-8"
  >
    <img :src="item?.icon" alt="" />
  </AppAvatar>
  <AppAvatar
    v-else-if="item?.name"
    :name="item?.name"
    pinyinColor
    shape="square"
    :size="32"
    class="mr-8"
  />
  <AppAvatar v-else shape="square" :size="32" style="background: none" class="mr-8">
    <img src="@/assets/icon_web.svg" alt="" />
  </AppAvatar>
</template>
<script setup lang="ts">
import AppAvatar from '../app-avatar/index.vue'
import { isAppIcon } from '@/utils/application'
defineProps<{
  item?: {
    name: string
    icon: string
  }
}>()
</script>
