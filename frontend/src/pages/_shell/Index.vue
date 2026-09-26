<template>
  <div class="shell">
    <header class="topbar" :class="{ scrolled }">
      <router-link to="/" class="brand">
        <BrandMark :size="22" />
        <span class="brand-name">{{ APP_TITLE }}</span>
      </router-link>

      <span class="brand-rule" aria-hidden="true" />

      <nav ref="navEl" class="nav" aria-label="功能导航">
        <router-link to="/" class="nav-item" :class="{ active: route.path === '/' }">
          工作台
        </router-link>

        <div v-for="(group, index) in visibleGroups" :key="group.title" class="nav-group">
          <button
            class="nav-item nav-trigger"
            type="button"
            aria-haspopup="true"
            :aria-expanded="openGroup === group.title"
            :aria-controls="menuId(index)"
            :class="{ active: activeGroup === group.title, open: openGroup === group.title }"
            @click="toggleGroup(group.title)"
            @keydown="onTriggerKeydown($event, group.title)"
          >
            <span>{{ group.title }}</span>
            <el-icon class="caret" aria-hidden="true"><ArrowDown /></el-icon>
          </button>

          <div
            v-if="openGroup === group.title"
            :id="menuId(index)"
            class="menu"
            role="menu"
            :aria-label="group.title"
          >
            <router-link
              v-for="(item, itemIndex) in group.items"
              :key="item.path"
              :to="item.path"
              class="menu-item"
              role="menuitem"
              :class="{ active: activePath === item.path }"
              @click="closeGroup()"
              @keydown="onMenuKeydown($event, itemIndex)"
            >
              <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
              <span class="menu-label">{{ item.label }}</span>
              <span v-if="activePath === item.path" class="menu-flag">当前</span>
            </router-link>
          </div>
        </div>
      </nav>

      <div class="grow" />

      <button
        ref="hintEl"
        class="hint"
        type="button"
        :aria-keyshortcuts="shortcutKeys"
        :aria-label="`打开命令面板（${shortcutLabel}）`"
        @click="openPalette"
      >
        <kbd class="hint-key">{{ modKey }}</kbd>
        <span class="hint-k">K</span>
      </button>

      <el-button type="primary" @click="router.push('/conversation')">开始对话</el-button>

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
            <el-dropdown-item command="my-account">我的账号</el-dropdown-item>
            <el-dropdown-item command="change-password">修改密码</el-dropdown-item>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <main class="main scroll-y" @scroll.passive="onScroll">
      <router-view />
    </main>

    <CommandPalette v-model="paletteOpen" />
    <AccountDialog ref="accountRef" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import BrandMark from '@/components/BrandMark.vue'
import CommandPalette from '@/components/CommandPalette.vue'
import AccountDialog from './AccountDialog.vue'
import { APP_TITLE } from '@/config/env'
import { canReach } from '@/lib/reach'
import { navGroups } from '@/router/routes'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const paletteOpen = ref(false)
const openGroup = ref('')
const scrolled = ref(false)
const navEl = ref<HTMLElement | null>(null)
const hintEl = ref<HTMLButtonElement | null>(null)
const accountRef = ref<InstanceType<typeof AccountDialog>>()

/* ⌘K 提示按平台写字 —— Windows 上画一个 ⌘ 是骗人。 */
const isMac = /mac/i.test(navigator.userAgent)
const modKey = isMac ? '⌘' : 'ctrl'
const shortcutLabel = isMac ? '⌘K' : 'Ctrl+K'
const shortcutKeys = isMac ? 'Meta+K' : 'Control+K'

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

const activeGroup = computed(
  () => visibleGroups.value.find((g) => g.items.some((i) => i.path === activePath.value))?.title ?? ''
)

/* 菜单只列「进得去」的项。
   判据走 `canReach()` —— 外壳、命令面板、403 页、工作台入口共用同一个函数，
   不再各写一份（漂移的表现是「某入口在 A 处隐藏、在 B 处仍可见」）。
   它同时管两维：权限点（meta.permission）与数据范围（meta.scopeForms，07 §五）——
   所以消费者看不到「知识库」这一项，而不是进去之后少几个按钮。
   分组空了就连标题一起去掉。 */
const visibleGroups = computed(() =>
  navGroups
    .map((group) => ({ ...group, items: group.items.filter((i) => canReach(router, i.path)) }))
    .filter((group) => group.items.length > 0)
)

function menuId(index: number) {
  return `topbar-menu-${index}`
}

function toggleGroup(title: string) {
  openGroup.value = openGroup.value === title ? '' : title
}

function closeGroup() {
  openGroup.value = ''
}

/* 菜单里的锚点不需要各自的 ref：从触发按钮所在的 .nav-group 里查就够了，
   少一层模板 ref 就少一处"v-for 里 ref 变成数组"这类惊喜。 */
function focusMenuItem(trigger: HTMLElement, index: number) {
  const items = Array.from(trigger.parentElement?.querySelectorAll<HTMLElement>('a.menu-item') ?? [])
  if (!items.length) return
  items[(index + items.length) % items.length].focus()
}

async function onTriggerKeydown(event: KeyboardEvent, title: string) {
  // Esc 必须在这里也接住：鼠标点开菜单后焦点留在触发按钮上，
  // 只给菜单项写 Esc 的话，用户按 Esc 会以为"关不掉"（实测确实关不掉）。
  if (event.key === 'Escape') {
    closeGroup()
    return
  }

  if (event.key !== 'ArrowDown') return
  event.preventDefault()
  // currentTarget 在 await 之后就为 null 了，先抓住元素
  const trigger = event.currentTarget as HTMLElement
  if (openGroup.value !== title) {
    openGroup.value = title
    await nextTick()
  }
  focusMenuItem(trigger, 0)
}

function onMenuKeydown(event: KeyboardEvent, index: number) {
  const link = event.currentTarget as HTMLElement
  const trigger = link.closest('.nav-group')?.querySelector<HTMLElement>('.nav-trigger')

  if (event.key === 'ArrowDown') {
    event.preventDefault()
    focusMenuItem(link, index + 1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (index === 0) {
      closeGroup()
      trigger?.focus()
    } else {
      focusMenuItem(link, index - 1)
    }
  } else if (event.key === 'Escape') {
    event.preventDefault()
    closeGroup()
    trigger?.focus()
  } else if (event.key === 'Tab') {
    // 菜单不吸住焦点：Tab 走人，菜单关掉
    closeGroup()
  }
}

function onDocumentPointerdown(event: PointerEvent) {
  if (!openGroup.value) return
  if (navEl.value?.contains(event.target as Node)) return
  closeGroup()
}

function onScroll(event: Event) {
  scrolled.value = (event.target as HTMLElement).scrollTop > 4
}

function openPalette() {
  hintEl.value?.focus()
  paletteOpen.value = true
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerdown)
  // 刷新页面后把登录态找回来。失败说明令牌已过期 —— 给提示并回登录页，
  // 不能 `.catch(() => {})` 吞掉（那会留下「未登录」的坏壳：菜单塌掉、无提示、不跳转）
  session.loadProfile().catch((error: unknown) => {
    const message =
      error instanceof Error && error.message ? error.message : '登录已过期，请重新登录'
    ElMessage.warning(message)
    session.reset()
    router.push('/login')
  })
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerdown)
})

watch(
  () => route.path,
  () => closeGroup()
)

const onCommand = async (command: string) => {
  if (command === 'my-account') {
    accountRef.value?.open()
    return
  }
  if (command === 'change-password') {
    router.push('/change-password')
    return
  }
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

/* 带框顶栏（Cobalt 签名 5）：通栏 + 单条底部发丝线。
   全工程唯一允许 backdrop-filter 的位置。 */
.topbar {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 0 var(--space-md);
  background: color-mix(in oklch, var(--color-surface) 72%, transparent);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--color-rule-2);
  z-index: var(--z-sticky);
  transition:
    background-color var(--dur-short) var(--ease-out),
    border-color var(--dur-short) var(--ease-out);
}

/* 页面滚动后顶栏与内容的分界收紧一档：从次线变成主线 */
.topbar.scrolled {
  background: color-mix(in oklch, var(--color-surface) 92%, transparent);
  border-bottom-color: var(--color-rule);
}

/* 字标：几何标记 + 一条发丝竖线 + 平台名。色由调用方定（这里是浅色纸 ⇒ 深色框）。 */
.brand {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  color: var(--color-ink);
}

.brand-name {
  font-family: var(--font-display);
  font-size: var(--text-md);
  font-weight: 600;
  line-height: var(--leading-tight);
  letter-spacing: var(--tracking-body);
}

.brand-rule {
  width: 1px;
  height: var(--space-md);
  background: var(--color-rule-strong);
}

.nav {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  min-width: 0;
}

.nav-group {
  position: relative;
}

/* 去处：13 个模块**按分组收纳**，不平铺成 13 个链接 */
.nav-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  height: 28px;
  padding: 0 var(--space-xs);
  border: none;
  border-radius: var(--radius);
  background: transparent;
  font-family: inherit;
  font-size: var(--text-sm);
  line-height: var(--leading-tight);
  color: var(--color-ink-2);
  cursor: pointer;
  white-space: nowrap;
  transition:
    background-color var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.nav-item:hover {
  background: var(--color-hover);
  color: var(--color-ink);
}

.nav-item:active {
  background: var(--color-sunken);
}

.nav-item.active,
.nav-item.open,
.nav-item[aria-expanded='true'] {
  background: var(--color-hover);
  color: var(--color-ink);
  font-weight: 500;
}

.caret {
  font-size: var(--text-2xs);
  color: var(--color-muted);
  transition: transform var(--dur-micro) var(--ease-out);
}

.nav-item.open .caret {
  transform: rotate(180deg);
}

.menu {
  position: absolute;
  top: calc(100% + var(--space-2xs));
  left: 0;
  min-width: 200px;
  display: flex;
  flex-direction: column;
  padding: var(--space-2xs);
  background: var(--color-surface-2);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  box-shadow: var(--shadow-float);
  z-index: var(--z-dropdown);
  animation: menu-in var(--dur-short) var(--ease-out) both;
}

@keyframes menu-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }

  to {
    opacity: 1;
    transform: none;
  }
}

.menu-item {
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

.menu-item:hover {
  background: var(--color-hover);
  color: var(--color-ink);
}

.menu-item:active {
  background: var(--color-sunken);
}

.menu-item.active {
  color: var(--color-ink);
  font-weight: 500;
}

.menu-label {
  flex: 1;
  min-width: 0;
}

.menu-flag {
  flex-shrink: 0;
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
  color: var(--color-muted);
}

/* ── 带框的 ⌘K 提示：mono 大写 + 1px 框，点它等同按键 ── */
.hint {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  height: 28px;
  padding: 0 var(--space-xs);
  background: var(--color-surface);
  border: 1px solid var(--color-rule-strong);
  border-radius: var(--radius);
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
  color: var(--color-muted);
  cursor: pointer;
  transition:
    background-color var(--dur-micro) var(--ease-out),
    border-color var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.hint:hover {
  background: var(--color-hover);
  border-color: var(--color-muted);
  color: var(--color-ink);
}

.hint:active {
  background: var(--color-sunken);
}

.hint:disabled {
  background: transparent;
  border-color: var(--color-rule);
  color: var(--color-faint);
  cursor: not-allowed;
}

.hint-key,
.hint-k {
  font-family: inherit;
}

.hint-k {
  color: var(--color-ink-2);
}

.hint:disabled .hint-k {
  color: var(--color-faint);
}

/* ── 用户 ── */
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

.main {
  flex: 1;
  min-width: 0;
  background: var(--color-paper);
}

/* 降低动效：不许有位移，只留透明度交叉淡入 */
@media (prefers-reduced-motion: reduce) {
  .menu {
    animation-name: menu-fade;
  }

  .caret,
  .nav-item.open .caret {
    transform: none;
  }
}

@keyframes menu-fade {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}
</style>
