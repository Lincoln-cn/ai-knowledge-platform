<template>
  <header class="page-header">
    <div class="hd-main">
      <h2 class="hd-title">
        {{ title }}
        <slot name="after-title" />
      </h2>
      <p v-if="desc" class="hd-desc micro muted">{{ desc }}</p>
    </div>
    <div class="hd-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
/** 页面标题条。全平台统一一处，避免每页各写一遍标题排版。 */
defineProps<{
  title: string
  desc?: string
}>()
</script>

<style scoped>
.page-header {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

/* ── 页头通栏底（design.md § Art layer「★ 页头通栏底」）─────────────────────
   蓝图细网，每页一条带。选 hatch 而不是 wash：wash 是"区块强调"，
   页头每页都有 ⇒ 铺 wash 等于做了全局处理；**纹理本来就允许平铺**。

   为什么是伪元素 + `inset: 0`（而不是给页头铺整页底）：
   这就是"一条带"的定义 —— 它的高度**等于页头自己的盒高**，
   既不往内容区溢出，也不随页面高度拉伸。

   为什么 multiply：页面底是浅色纸（oklch 97.4%），multiply 让网线压下去；
   深色底上这么做会等于消失（见 InstrumentCard）。上限走令牌，页面里不许再调高。 */
.page-header::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: var(--art-hatch);
  background-repeat: repeat;
  mix-blend-mode: multiply;
  opacity: var(--art-opacity-hatch);
  pointer-events: none;
}

.hd-main {
  min-width: 0;
}

/* 标题与操作要压在带**之上**。这里只给 z-index、**不给 position**：
   flex item 的 z-index 按 Flexbox 规范本身就生效（无需定位），
   而一旦写上 position: relative，页面 #actions 插槽里的绝对定位元素
   就会换掉包含块 —— 那是跨页面的隐性回归。 */
.hd-main,
.hd-actions {
  z-index: 1;
}

.hd-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}

.hd-desc {
  margin: 5px 0 0;
  line-height: 1.7;
  max-width: 820px;
}

.hd-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
