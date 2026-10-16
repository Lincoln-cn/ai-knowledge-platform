<template>
  <!-- ★ 第三轮：根元素补上 `.page`（页骨）。
       此前是 `<div class="wrap">` + `height:100%; place-items:center` ——
       一屏空白里居中一个 404。那既是反模式清单里的"居中一切"，
       又**拿不到页骨**（内边距 / `--content-max` 居中列 / 三段节奏）。
       由 `check_design.py` 的「页面骨架口径」抓出来（闸门先红、再修，见 design.md 台账）。 -->
  <div class="page">
    <div class="empty state-404">
      <div class="state-code">404</div>
      <div class="empty-title">页面不存在</div>
      <div class="rule state-rule" aria-hidden="true"></div>
      <p class="micro muted"><code>{{ originalPath }}</code></p>
      <el-button size="small" type="primary" @click="$router.push('/')">回到工作台</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 未登录被守卫转来时原始路径在 query.from；直接命中时就是当前路径 */
const originalPath = computed(() => (route.query.from as string) || route.fullPath)
</script>

<style scoped>
/* 空态：排版 + 一条发丝线，不许插画（design.md § Per-page allowance）。
   ★ 第三轮改为**左对齐**：居中的一整屏空白读起来像"页面没做完"，
   左对齐 + 页骨才是编辑式版面对"死路"的处理。 */
.state-404 {
  align-items: flex-start;
  text-align: left;
  padding-block: var(--space-2xl);
}

.state-code {
  font-family: var(--font-mono);
  font-size: var(--text-2xl);
  font-weight: 700;
  line-height: var(--leading-display);
  /* ★ 负字距只许加在纯拉丁 / 数字上 —— 这里是 "404"，合法且是唯一该用的地方 */
  letter-spacing: var(--tracking-display);
  color: var(--color-faint);
}

.state-rule {
  width: var(--space-xl);
}

code {
  background: var(--color-sunken);
  padding: 0 var(--space-2xs);
  border-radius: var(--radius-xs);
}
</style>
