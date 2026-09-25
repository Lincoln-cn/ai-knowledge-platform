<!--
  EmptyArt —— 空态专用的「mono 线稿示意图 + 一片极浅色场」。

  design.md § Art layer 那张表是**穷举**的：位图只许出现在四个具名表面（沉浸页 / 空态 /
  石墨带 / 仪器面板内）。本组件只服务「空态」那一格，别处引用它就是违规。

  ★ 组件只画图本身，**不决定站位**：这里没有 margin:auto、没有 text-align:center。
    design.md § Per-page allowance 明写空态「不许居中大图」——「放哪一侧」由调用方
    用两栏排布决定（本工程的会话页 / 分段面板即两处调用方）。
  ★ 浅色纸上 multiply（design.md § Art layer）。深色底上必须换 screen ——
    实测深底 multiply 会让素材彻底消失（见 login/Index.vue 那条注释）。
  ★ 静态线稿：没有位移，因此不需要 prefers-reduced-motion 分支。
-->
<template>
  <span class="empty-art" :class="`empty-art--${kind}`" aria-hidden="true">
    <span class="empty-art__wash"></span>
    <span class="empty-art__schematic"></span>
  </span>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ kind?: 'pipeline' | 'retrieval' }>(), { kind: 'pipeline' })
</script>

<style scoped>
.empty-art {
  /* 唯一的尺寸旋钮。1200×720 的素材 ⇒ 5:3，contain 不会拉伸变形。 */
  --empty-art-w: 240px;

  position: relative;
  display: block;
  flex: none;
  width: var(--empty-art-w);
  aspect-ratio: 5 / 3;
}

/* 极浅色场。三条实测结论（都是截图量出来的，不是估的）：
   ① 素材 `wash-cool-01.webp` 的蓝**压在它自己的四角**（左缘 alpha 至 118/255、
      右缘 118、上缘 94、下缘 108）。所以任何直切都会切出一条硬边 ——
      第一版 `cover` 直接铺上去，量到盒子左缘一像素内从 (252,253,254) 跳到 (222,233,250)，
      整块读成「贴了一张蓝色纸」。
   ② 修法是 mask 羽化到**恰好 0**：`closest-side` 的椭圆在四条边中点处正好等于半径的
      100%，四角还被钳到最后一档 ⇒ 硬边归零。实测四边相邻像素差 = 0。
      单靠降 opacity 治不了硬边。
   ③ 素材的能量集中在左上那一团，所以把背景放大 3.4 倍并对准左上，让那一团落在
      mask 的不透核心内；否则羽化会把蓝全部吃掉（实测 tint=0，等于没放）。
   opacity 取硬上限的一半：满上限 + 放大对准后色场过重，实测峰值色差 118/765，
   蓝盖住了线稿本身（线稿才是主角）。半档后峰值 60/765，剖面平缓、四边为 0。 */
.empty-art__wash {
  position: absolute;
  inset: calc(var(--space-2xl) * -1) calc(var(--space-xl) * -1);
  pointer-events: none;
  background-image: var(--art-wash);
  background-repeat: no-repeat;
  background-position: 0% 6%;
  background-size: 340% auto;
  mix-blend-mode: multiply;
  opacity: calc(var(--art-opacity-wash) * 0.5);
  mask-image: radial-gradient(ellipse closest-side, black 50%, transparent 100%);
  -webkit-mask-image: radial-gradient(ellipse closest-side, black 50%, transparent 100%);
}

/* 线稿本身。素材是 1200×720 的透明底 PNG（角点 alpha=0），
   所以这里**不加** mix-blend-mode：线色是淡蓝，再乘一遍色场会让压在
   色场浓区的笔画变重、同一条线忽粗忽细。色场走 multiply 就够了。 */
.empty-art__schematic {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-repeat: no-repeat;
  background-position: center;
  background-size: contain;
  opacity: 0.85;
}

.empty-art--pipeline .empty-art__schematic {
  background-image: var(--art-empty-1);
}

.empty-art--retrieval .empty-art__schematic {
  background-image: var(--art-empty-2);
}
</style>
