<template>
  <div class="page">
    <div class="empty">
      <div class="label">Not migrated</div>
      <div class="empty-title">{{ title }} · 尚未迁移</div>
      <div class="rule state-rule" aria-hidden="true"></div>
      <p class="micro muted">来源：<code>{{ source }}</code></p>
      <p class="micro muted">
        该模块在 <code>qa-ui-3</code> 里有可复用内容，但需要先去掉 avue / vuex 依赖并归一命名，
        迁移完成前这里不填假数据。
      </p>
      <el-button size="small" @click="$router.push('/')">返回迁移总览</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '该模块')
const source = computed(() => (route.meta.source as string) || '—')
</script>

<style scoped>
/* 空态：排版 + 一条发丝线，不许插画。保留「尚未迁移」的刻意语义。 */
.state-rule {
  width: var(--space-xl);
}

code {
  background: var(--color-sunken);
  padding: 0 var(--space-2xs);
  border-radius: var(--radius-xs);
}
</style>
