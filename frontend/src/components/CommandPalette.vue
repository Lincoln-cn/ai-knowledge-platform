<template>
  <Teleport to="body">
    <div v-if="open" class="cmdk-scrim" @pointerdown.self="close">
      <div
        class="cmdk"
        role="dialog"
        aria-modal="true"
        aria-label="命令面板"
        @keydown.esc.stop.prevent="close"
        @keydown.tab.prevent
      >
        <div class="cmdk-field">
          <el-icon class="cmdk-field-icon" aria-hidden="true"><Search /></el-icon>
          <label class="cmdk-label" for="cmdk-query">搜索页面或动作</label>
          <input
            id="cmdk-query"
            ref="inputEl"
            v-model="query"
            class="cmdk-input"
            type="text"
            name="cmdk-query"
            placeholder="搜索页面或动作…"
            autocomplete="off"
            spellcheck="false"
            role="combobox"
            aria-controls="cmdk-results"
            aria-expanded="true"
            :aria-activedescendant="activeId"
            @keydown="onInputKeydown"
          />
          <kbd class="cmdk-esc">Esc</kbd>
        </div>

        <ul id="cmdk-results" ref="listEl" class="cmdk-results scroll-y" role="listbox" aria-label="命令">
          <li
            v-for="group in filtered"
            :key="group.title"
            class="cmdk-group"
            role="group"
            :aria-label="group.title"
          >
            <p class="cmdk-group-title label" aria-hidden="true">{{ group.title }}</p>
            <div role="presentation">
              <div
                v-for="item in group.items"
                :id="item.id"
                :key="item.id"
                class="cmdk-item"
                :class="{ active: item.id === activeId }"
                role="option"
                :aria-selected="item.id === activeId"
                @mouseenter="active = flat.indexOf(item)"
                @click="run(item)"
              >
                <el-icon class="cmdk-item-icon" aria-hidden="true">
                  <component :is="item.icon" />
                </el-icon>
                <span class="cmdk-item-label ellipsis">{{ item.label }}</span>
                <span v-if="item.hint" class="cmdk-item-hint">{{ item.hint }}</span>
              </div>
            </div>
          </li>
          <li v-if="!flat.length" class="cmdk-none">没有匹配的页面或动作</li>
        </ul>

        <div class="cmdk-foot">
          <span class="cmdk-foot-item"><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
          <span class="cmdk-foot-item"><kbd>↵</kbd> 打开</span>
          <span class="cmdk-foot-item"><kbd>Esc</kbd> 关闭</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PERM, type PermissionCode } from '@/constants/permissions'
import { canReach } from '@/lib/reach'
import { navGroups } from '@/router/routes'
import { useSessionStore } from '@/stores/session'

/**
 * 命令面板（design.md § 命令面板 · Cobalt 签名 5）。
 *
 * 两件事要守住：
 *  1. **跳页结果集与顶栏菜单同源** —— 同一份 `navGroups`、同一套
 *     `router meta.permission` + `session.can` 判据。另写一份过滤逻辑，
 *     迟早会漂移成「菜单里点得到、⌘K 里点进去 403」。
 *  2. 不许 `:teleported="false"`（本项目踩过：节点内浮层写了它用户就"选不了"）。
 *     这里自绘浮层 + Vue 的 `<Teleport to="body">`，不用 Element Plus 的 dialog。
 */

interface PaletteItem {
  id: string
  label: string
  icon: string
  /** 右侧的去处提示（分组名或目标页名） */
  hint?: string
  run: () => void
}

interface PaletteGroup {
  title: string
  items: PaletteItem[]
}

/** 开合状态由外壳持有（顶栏那个带框的 ⌘K 提示也要能开它）。 */
const open = defineModel<boolean>({ default: false })

const router = useRouter()
const session = useSessionStore()

const query = ref('')
const active = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)
const listEl = ref<HTMLElement | null>(null)

/** 打开前焦点在谁身上，关掉就还给谁 */
let restoreFocus: HTMLElement | null = null

/** 跳页：工作台 + navGroups，按权限过滤掉进不去的项，空了连分组一起去掉 */
const pageGroups = computed<PaletteGroup[]>(() => {
  const raw = [
    { title: '总览', items: [{ path: '/', label: '工作台', icon: 'DataBoard' }] },
    ...navGroups
  ]
  return raw
    .map((group) => ({
      title: group.title,
      items: group.items
        .filter((item) => canReach(router, item.path))
        .map((item) => {
          const path = item.path
          return {
            id: `page:${path}`,
            label: item.label,
            icon: item.icon,
            hint: group.title,
            run: () => router.push(path)
          }
        })
    }))
    .filter((group) => group.items.length > 0)
})

/** 动作：都要落到一个真实页面，权限不够就不出现（而不是点了报 403） */
const actionGroup = computed<PaletteGroup>(() => {
  const actions: Array<{ label: string; icon: string; path: string; permission?: PermissionCode }> = [
    { label: '建知识库', icon: 'FolderAdd', path: '/knowledge', permission: PERM.knowledgeCreate },
    { label: '建应用', icon: 'Grid', path: '/assistants', permission: PERM.assistantEdit },
    { label: '开始问答', icon: 'ChatDotRound', path: '/conversation' }
  ]
  return {
    title: '动作',
    items: actions
      .filter((action) => !action.permission || session.can(action.permission))
      .map((action) => {
        const path = action.path
        return {
          id: `action:${path}`,
          label: action.label,
          icon: action.icon,
          hint: router.resolve(path).meta.title ?? '',
          run: () => router.push(path)
        }
      })
  }
})

const groups = computed<PaletteGroup[]>(() => [...pageGroups.value, actionGroup.value])

const filtered = computed<PaletteGroup[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return groups.value
  return groups.value
    .map((group) => ({
      title: group.title,
      items: group.items.filter((item) =>
        `${item.label} ${item.hint ?? ''} ${item.id}`.toLowerCase().includes(q)
      )
    }))
    .filter((group) => group.items.length > 0)
})

/* 拍平后的顺序 = ↑↓ 的走位顺序，也是 active 的下标。
   ⚠️ 必须拍**过滤后**的那一份 —— 拍未过滤的 `groups` 会让 Enter 执行到
   用户看不见的那一项（实测：搜「反馈」按 Enter 落回 `/`）。 */
const flat = computed<PaletteItem[]>(() => filtered.value.flatMap((group) => group.items))

const activeId = computed(() => flat.value[active.value]?.id ?? '')

watch(filtered, () => {
  active.value = 0
})

watch(activeId, async (id) => {
  await nextTick()
  listEl.value?.querySelector(`[id="${id}"]`)?.scrollIntoView({ block: 'nearest' })
})

function run(item?: PaletteItem) {
  const target = item ?? flat.value[active.value]
  if (!target) return
  close()
  target.run()
}

function close() {
  open.value = false
}

watch(open, async (isOpen) => {
  if (isOpen) {
    restoreFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    query.value = ''
    active.value = 0
    await nextTick()
    inputEl.value?.focus()
  } else {
    restoreFocus?.focus()
    restoreFocus = null
  }
})

function onInputKeydown(event: KeyboardEvent) {
  const total = flat.value.length
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    active.value = total ? (active.value + 1) % total : 0
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    active.value = total ? (active.value - 1 + total) % total : 0
  } else if (event.key === 'Home') {
    event.preventDefault()
    active.value = 0
  } else if (event.key === 'End') {
    event.preventDefault()
    active.value = Math.max(0, total - 1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    run()
  }
}

function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    open.value = !open.value
    return
  }
  // 焦点跑到面板外时（例如点了浮层）Esc 仍要能关
  if (open.value && event.key === 'Escape') {
    event.preventDefault()
    close()
  }
}

onMounted(() => document.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onGlobalKeydown))
</script>

<style scoped>
.cmdk-scrim {
  position: fixed;
  inset: 0;
  z-index: var(--z-cmdk);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: var(--space-2xl) var(--space-md);
  background: color-mix(in oklch, var(--color-ink) 30%, transparent);
  animation: cmdk-fade var(--dur-short) var(--ease-out) both;
}

.cmdk {
  width: 100%;
  max-width: 560px;
  display: flex;
  flex-direction: column;
  max-height: 100%;
  background: var(--color-surface-2);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-float);
  overflow: hidden;
  animation: cmdk-in var(--dur-short) var(--ease-out) both;
}

@keyframes cmdk-fade {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

@keyframes cmdk-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }

  to {
    opacity: 1;
    transform: none;
  }
}

/* ── 输入行：凹槽底 + 1px 线，焦点环由全局 :focus-visible 给（瞬间出现） ── */
.cmdk-field {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  border-bottom: 1px solid var(--color-rule);
}

.cmdk-field-icon {
  color: var(--color-muted);
}

/* 输入框的 label：给读屏用，视觉上由 placeholder 承担 */
.cmdk-label {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  border: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

.cmdk-input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  font-family: var(--font-body);
  font-size: var(--text-sm);
  line-height: var(--leading-ui);
  color: var(--color-ink);
}

.cmdk-input::placeholder {
  color: var(--color-faint);
}

.cmdk-esc {
  flex-shrink: 0;
  padding: 0 var(--space-2xs);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-xs);
  color: var(--color-muted);
  text-transform: uppercase;
}

.cmdk-results {
  margin: 0;
  padding: var(--space-2xs);
  list-style: none;
  max-height: 60vh; /* 视口矮时面板不许顶到边 */
}

.cmdk-group {
  margin: 0;
}

.cmdk-group-title {
  margin: 0;
  padding: var(--space-xs) var(--space-sm) var(--space-2xs);
}

.cmdk-item {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  /* 竖向内边距取 4pt 刻度的最小档：命令面板是「扫」不是「读」，
     一行 38px 太松 —— 12 项会被挤到折叠线以下。 */
  padding: var(--space-2xs) var(--space-sm);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--color-ink-2);
  cursor: pointer;
}

/* 选中态：极浅钴底 + 一条 accent 左竖条。全系统只有这一处用 accent 标"光标在哪"。 */
.cmdk-item.active {
  position: relative;
  background: var(--color-accent-soft);
  color: var(--color-ink);
}

.cmdk-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: var(--space-2xs);
  bottom: var(--space-2xs);
  width: 2px;
  border-radius: var(--radius-xs);
  background: var(--color-accent);
}

.cmdk-item-icon {
  color: var(--color-muted);
}

.cmdk-item.active .cmdk-item-icon {
  color: var(--color-accent);
}

.cmdk-item-label {
  flex: 1;
  min-width: 0;
}

.cmdk-item-hint {
  flex-shrink: 0;
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  letter-spacing: var(--tracking-label);
  color: var(--color-muted);
}

.cmdk-none {
  padding: var(--space-lg) var(--space-sm);
  text-align: center;
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.cmdk-foot {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-xs) var(--space-md);
  border-top: 1px solid var(--color-rule-2);
  background: var(--color-sunken);
}

.cmdk-foot-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-2xs);
  color: var(--color-muted);
}

.cmdk-foot kbd {
  padding: 0 var(--space-2xs);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-xs);
  background: var(--color-surface);
  color: var(--color-ink-2);
}

/* 降低动效：不许有位移，只留透明度交叉淡入 */
@media (prefers-reduced-motion: reduce) {
  .cmdk {
    animation-name: cmdk-fade;
  }
}
</style>
