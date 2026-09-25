<template>
  <div class="page">
    <div class="empty">
      <div class="state-code">403</div>
      <div class="empty-title">当前角色没有访问该页面的权限</div>
      <div class="rule state-rule" aria-hidden="true"></div>
      <p class="micro muted">
        被拒绝的地址 <code>{{ deniedPath }}</code>
        <template v-if="required"> · 需要权限点 <code>{{ required }}</code></template>
      </p>
      <p class="micro muted">你的角色：{{ session.roleName || '—' }}</p>

      <div v-if="reachable.length" class="reach">
        <div class="label">当前角色可访问</div>
        <div class="chips">
          <el-button
            v-for="item in reachable"
            :key="item.path"
            size="small"
            @click="$router.push(item.path)"
          >
            {{ item.label }}
          </el-button>
        </div>
      </div>

      <el-button size="small" type="primary" @click="$router.push('/')">回到工作台</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { navGroups } from '@/router/routes'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

/** 守卫把被拒地址放在 query 里 —— 直接敲 URL 进来时它就是那个 URL。 */
const deniedPath = computed(() => (route.query.from as string) || route.fullPath)
const required = computed(() => router.resolve(deniedPath.value).meta.permission ?? '')

/** 同一个过滤口径：能访问的路由才列出来，避免 403 页自己给出死链。 */
const reachable = computed(() =>
  navGroups.flatMap((g) =>
    g.items.filter((i) => {
      const need = router.resolve(i.path).meta.permission
      return !need || session.can(need)
    })
  )
)
</script>

<style scoped>
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

.reach {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);
  margin: var(--space-2xs) 0;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2xs);
  justify-content: center;
}

code {
  background: var(--color-sunken);
  padding: 0 var(--space-2xs);
  border-radius: var(--radius-xs);
}
</style>
