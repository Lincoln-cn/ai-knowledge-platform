<template>
  <header class="topbar" :class="{ 'with-panel': alignPanel }">
    <div class="lead">
      <button type="button" class="back" @click="emit('back')">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回</span>
      </button>
      <span class="lead-rule" aria-hidden="true" />
    </div>

    <!--
      ★ 2026-10-18：标题从「居中两行、与副标题同字」改成**左对齐的页头**。
        原来的 `.titles { flex: 1 }` 后面还跟着一个 `.grow { flex: 1 }`，
        两段各分一半剩余宽度 ⇒ 标题实际停在页面左半边的中间（截图里那个
        像浮在空中的「标签」就是这么来的），而副标题在通用问答下又原样
        重复了一遍标题。页头三要素（标题 + 元信息行 + 操作区）在本项目的
        design.md 里是**左对齐**的，这里回到同一套节奏。
    -->
    <div class="identity">
      <h1 class="title ellipsis" :title="title">{{ title }}</h1>
      <p v-if="subtitle" class="subtitle ellipsis" :title="subtitle">{{ subtitle }}</p>
    </div>

    <div class="actions" role="group" aria-label="会话操作">
      <template v-if="!publicMode">
        <router-link
          v-if="showKbLink"
          to="/knowledge"
          class="tlink"
          aria-label="知识库"
          title="知识库"
        >
          <el-icon><Collection /></el-icon><span>知识库</span>
        </router-link>
        <!--
          ★ 对话设置：从对话页直达配置（召回开关 / 阈值 / 提示词 / 超时）。

          用户报「点击对话配置没有跳转」—— 此前这个入口**根本不存在**：
          顶栏只有「返回 / 分享」，而「应用配置」只在有 appId 时出现，
          通用问答（无 appId）的页面上没有任何通往配置的路。
          ⇒ 不是"点了没反应"，是"那个按钮不存在"（两者在界面上很像，都不动）。
        -->
        <router-link
          v-if="showChatSettings"
          to="/settings/chat"
          class="tlink"
          aria-label="对话设置"
          title="对话设置"
        >
          <el-icon><Timer /></el-icon><span>对话设置</span>
        </router-link>
        <router-link
          v-if="showAppConfig"
          :to="appConfigTo"
          class="tlink"
          aria-label="应用配置"
          title="应用配置"
        >
          <el-icon><Setting /></el-icon><span>应用配置</span>
        </router-link>
      </template>
      <button
        v-if="showShare"
        type="button"
        class="tlink share"
        @click="emit('share')"
      >
        <el-icon><Share /></el-icon><span>分享</span>
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ArrowLeft, Collection, Setting, Share, Timer } from '@element-plus/icons-vue'
import { computed } from 'vue'

const props = defineProps<{
  title: string
  subtitle?: string
  appId?: string | null
  publicMode?: boolean
  showKbLink?: boolean
  /**
   * 与对话页的三栅对齐：返回按钮独占「历史会话」那一列，
   * 标题落在会话面板的左缘。公开页没有侧栏，不传。
   */
  alignPanel?: boolean
  /** 分享入口显隐由调用方按权限点决定（conversation:share） */
  showShare?: boolean
  /** 「对话设置」入口（按 conversation:view；改配置需要 system:manage，在页面内再把一次） */
  showChatSettings?: boolean
}>()

const emit = defineEmits<{
  back: []
  share: []
}>()

const showAppConfig = computed(() => !!props.appId && !props.publicMode)
const appConfigTo = computed(() =>
  props.appId ? `/assistants?focus=${props.appId}` : '/assistants'
)
const showShare = computed(
  () => props.showShare !== false && !props.publicMode
)
const showKbLink = computed(() => props.showKbLink !== false)
</script>

<style scoped>
.topbar {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 0 var(--space-sm);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-rule);
}

/* 对话页：与下方三栅共用同一列宽（`--history-w` 由页面定义，历史面板也读它）。
   返回独占「历史会话」那一列，发丝线落在列边界上；标题因此与会话面板左缘对齐，
   而不是浮在侧栏与正文之间的半空中。 */
.topbar.with-panel {
  display: grid;
  grid-template-columns: var(--history-w, 220px) minmax(0, 1fr) auto;
}

.topbar.with-panel .lead {
  justify-content: space-between;
}

/* 返回与身份之间的发丝线：页头由此分成「导航 / 身份 / 操作」三段，
   比把返回按钮直接贴在标题左边更容易看出层级。 */
.lead-rule {
  width: 1px;
  height: 18px;
  background: var(--color-rule);
}

/* 返回 + 发丝线收成一个 lead 组：它与标题之间的间距由 .topbar 的 gap 统一给。 */
.lead {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-shrink: 0;
}

.back {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  height: 30px;
  border: none;
  background: transparent;
  color: var(--color-ink-2);
  font-size: var(--text-xs);
  font-family: inherit;
  cursor: pointer;
  padding: 0 var(--space-xs);
  border-radius: var(--radius);
  transition:
    background var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.back .el-icon {
  font-size: 15px;
}

.back:hover {
  background: var(--color-hover);
  color: var(--color-ink);
}

.back:active {
  background: var(--color-sunken);
}

/* 标题与元信息：两行、左对齐，行间只留 2px。
   ★ 不再居中 —— 居中时它既不对齐左缘也不对齐正文，只会浮在半空。 */
.identity {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--space-3xs);
}

.title {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-md);
  font-weight: 600;
  line-height: var(--leading-title);
  color: var(--color-ink);
  letter-spacing: var(--tracking-body);
}

.subtitle {
  margin: 0;
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  color: var(--color-muted);
}

/* 操作区：次级动作是安静的图标文字，分享用 1px 框把自己和它们分开一档 ——
   整条顶栏只有这一个「有边界」的按钮，右端因此有落点。 */
.actions {
  display: flex;
  align-items: center;
  gap: var(--space-3xs);
  flex-shrink: 0;
}

.tlink {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  height: 30px;
  padding: 0 var(--space-xs);
  font-size: var(--text-xs);
  color: var(--color-ink-2);
  border: none;
  border-radius: var(--radius);
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
  transition:
    background var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out),
    border-color var(--dur-micro) var(--ease-out);
}

.tlink .el-icon {
  font-size: 14px;
  color: var(--color-muted);
  transition: color var(--dur-micro) var(--ease-out);
}

.tlink:hover {
  background: var(--color-hover);
  color: var(--color-ink);
}

.tlink:hover .el-icon {
  color: var(--color-accent);
}

.tlink:active {
  background: var(--color-sunken);
}

.share {
  padding: 0 var(--space-sm);
  border: 1px solid var(--color-rule-strong);
  background: var(--color-surface);
  color: var(--color-ink);
  font-weight: 500;
}

.share:hover {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
  color: var(--color-accent);
}

/* 窄屏：次级入口收成图标（aria-label 仍在），把宽度留给会话标题与分享。 */
@media (max-width: 900px) {
  .actions .tlink:not(.share) span {
    display: none;
  }

  .actions .tlink:not(.share) {
    padding: 0 var(--space-xs);
  }
}
</style>
