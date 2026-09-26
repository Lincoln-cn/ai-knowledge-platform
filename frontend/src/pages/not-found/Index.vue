<template>
  <div class="wrap">
    <div class="empty">
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
.wrap {
  height: 100%;
  display: grid;
  place-items: center;
  background: var(--color-paper);
  padding: var(--space-lg);
}

/* 空态：排版 + 一条发丝线，不许插画 */
.state-code {
  font-family: var(--font-mono);
  font-size: var(--text-2xl);
  font-weight: 600;
  line-height: var(--leading-display);
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
