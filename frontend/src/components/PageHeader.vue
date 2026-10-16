<template>
  <header class="page-header">
    <div class="hd-main">
      <h2 class="hd-title">
        {{ title }}
        <slot name="after-title" />
      </h2>
      <p v-if="desc" class="hd-desc">{{ desc }}</p>
      <!-- meta 行（★ 第三轮）：mono 大写 + 0.12em 字距，只放元信息（区间 / 口径 / 计数 / 版本）。
           没有内容时整行不渲染 —— 不画一个空的行高。 -->
      <p v-if="$slots.meta" class="hd-meta meta"><slot name="meta" /></p>
    </div>
    <div class="hd-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
/**
 * 页面标题条。全平台统一一处，避免每页各写一遍标题排版。
 *
 * ★ 第三轮重写（design.md § Register — Editorial）。旧版是**全站唯一一处
 *   既脱令牌又脱字号阶梯**的组件，四个裸值：
 *     `font-size: 17px`（17 不在阶梯里）· `gap: 16px` · `margin-bottom: 14px`（14 不在 4pt 刻度）·
 *     `line-height: 1.7`（不走 --leading-* 令牌）· `margin: 5px 0 0`
 *   后果：同一层级界面的"页面标题"在工作台是 24px、在其余 12 页是 17px。
 *   现在标题统一到 `--text-xl`（28px）、字重 700，全部走令牌。
 */
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
  gap: var(--space-lg);
  padding-bottom: var(--space-md);
  /* 页头与内容之间用一条发丝线分面（Editorial：靠线，不靠盒子） */
  border-bottom: 1px solid var(--color-rule);
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

/* ★ 第三轮：17px → `--text-xl`（28px）+ 字重 700。
   与全局 `.page-head .h1`（工作台用的那一套）**同字号同字重** ——
   两套页头从此在排印上是一套，只是容器类名不同（两个类名都是闸门钩子，不许合并）。 */
.hd-title {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-xl);
  font-weight: 700;
  line-height: var(--leading-title);
  letter-spacing: var(--tracking-body); /* 中文标题不给负字距 */
  color: var(--color-ink);
}

.hd-desc {
  margin: var(--space-xs) 0 0;
  max-width: var(--measure);
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-muted);
}

.hd-meta {
  margin: var(--space-sm) 0 0;
}

.hd-actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-shrink: 0;
}
</style>
