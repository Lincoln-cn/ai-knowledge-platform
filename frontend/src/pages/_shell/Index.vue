<template>
  <div class="shell">
    <header class="topbar">
      <router-link to="/" class="brand">
        <span class="brand-mark">知</span>
        <span class="brand-rule" aria-hidden="true"></span>
        <span class="brand-name">{{ APP_TITLE }}</span>
      </router-link>

      <div class="grow" />

      <router-link to="/conversation" class="topbar-link">
        <el-icon><ChatDotRound /></el-icon><span>去问答</span>
      </router-link>

      <el-dropdown @command="onCommand">
        <button class="user">
          <span class="avatar">{{ session.displayName.slice(0, 1) || '—' }}</span>
          <span class="u-name">{{ session.displayName || '未登录' }}</span>
          <!-- 角色与姓名相同时不重复显示 —— mock 里 displayName 与 roleName 都是
               「管理员」，并排出现读起来像 bug。信息重复不是信息。 -->
          <span v-if="session.roleName && session.roleName !== session.displayName" class="u-role">
            {{ session.roleName }}
          </span>
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <div class="body">
      <aside class="rail">
        <router-link to="/" class="rail-item" :class="{ active: route.path === '/' }">
          <el-icon><DataBoard /></el-icon><span>工作台</span>
        </router-link>

        <div v-for="group in visibleGroups" :key="group.title" class="rail-group">
          <div class="rail-group-title label">{{ group.title }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="rail-item"
            :class="{ active: activePath === item.path }"
          >
            <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
          </router-link>
        </div>
      </aside>

      <main class="main scroll-y">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { APP_TITLE } from '@/config/env'
import { navGroups } from '@/router/routes'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

/* 取最长匹配的那个导航项为高亮项。
   否则 /assistants/prompts 会让 /assistants 与它同时高亮。 */
const activePath = computed(() => {
  const all = navGroups.flatMap((g) => g.items.map((i) => i.path))
  return (
    all
      .filter((p) => route.path === p || route.path.startsWith(p + '/'))
      .sort((a, b) => b.length - a.length)[0] ?? ''
  )
})

/* 侧栏只列「进得去」的项。
   权限从**路由 meta** 读，不在这里另存一份 —— 否则菜单与守卫会漂移成
   「菜单看得见、点进去 403」。分组空了就连标题一起去掉。 */
const visibleGroups = computed(() =>
  navGroups
    .map((group) => ({ ...group, items: group.items.filter(canReach) }))
    .filter((group) => group.items.length > 0)
)

function canReach(item: { path: string }) {
  const need = router.resolve(item.path).meta.permission
  return !need || session.can(need)
}

onMounted(() => {
  // 刷新页面后把登录态找回来；失败则交给路由守卫处理
  session.loadProfile().catch(() => {})
})

const onCommand = async (command: string) => {
  if (command !== 'logout') return
  await session.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<style scoped>
.shell {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* 顶栏：齐边全宽 + 一条发丝下边线。全工程唯一允许 backdrop-filter 的位置。 */
.topbar {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 0 var(--space-md);
  background: color-mix(in oklch, var(--color-surface) 88%, transparent);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--color-rule);
  z-index: var(--z-sticky);
}

/* 字标：display 重字 + 发丝竖线 + 品牌名，一组。不使用圆角色块。 */
.brand {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  color: var(--color-ink);
}

.brand-mark {
  font-family: var(--font-display);
  font-size: var(--text-lg);
  font-weight: 600;
  line-height: 1;
  letter-spacing: var(--tracking-body);
  color: var(--color-ink);
}

.brand-rule {
  width: 1px;
  height: var(--space-md);
  background: var(--color-rule-strong);
}

.brand-name {
  font-family: var(--font-body);
  font-size: var(--text-sm);
  font-weight: 600;
  line-height: 1;
  letter-spacing: var(--tracking-body);
  color: var(--color-ink);
}

.brand:hover .brand-name {
  color: var(--color-accent);
}

.topbar-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
  padding: var(--space-2xs) var(--space-xs);
  border-radius: var(--radius-sm);
  transition:
    background-color var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.topbar-link:hover {
  background: var(--color-sunken);
  color: var(--color-ink);
}

.user {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  background: transparent;
  border: none;
  cursor: pointer;
  font-family: inherit;
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
  padding: var(--space-2xs) var(--space-xs);
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-micro) var(--ease-out);
}

.user:hover {
  background: var(--color-sunken);
  color: var(--color-ink);
}

.user:active {
  background: var(--color-hover);
}

.avatar {
  width: 22px;
  height: 22px;
  border-radius: var(--radius-pill);
  background: var(--color-sunken);
  color: var(--color-ink);
  display: grid;
  place-items: center;
  font-size: var(--text-2xs);
  font-weight: 500;
}

.u-role {
  color: var(--color-muted);
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.rail {
  width: 200px;
  flex-shrink: 0;
  background: var(--color-surface);
  border-right: 1px solid var(--color-rule);
  padding: var(--space-xs);
  overflow-y: auto;
}

.rail-group {
  margin-top: var(--space-sm);
}

/* 分组标题走全局 .label（mono 全大写小标签，12px） */
.rail-group-title {
  padding: 0 var(--space-xs) var(--space-2xs);
}

/* 选中态：accent 左竖条 + 文字转 ink + 轻微底色。不用整块彩底。 */
.rail-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--color-ink-2);
  transition:
    background-color var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.rail-item:hover {
  background: var(--color-hover);
  color: var(--color-ink);
}

.rail-item:active {
  background: var(--color-sunken);
}

.rail-item.active {
  background: var(--color-hover);
  color: var(--color-ink);
  font-weight: 500;
}

.rail-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: var(--space-2xs);
  bottom: var(--space-2xs);
  width: 2px;
  border-radius: var(--radius-xs);
  background: var(--color-accent);
}

.main {
  flex: 1;
  min-width: 0;
  background: var(--color-paper);
}
</style>
