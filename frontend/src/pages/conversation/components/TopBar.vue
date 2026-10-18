<template>
  <header class="topbar">
    <button type="button" class="back" @click="emit('back')">
      <el-icon><ArrowLeft /></el-icon>
      <span>返回</span>
    </button>

    <div class="titles">
      <div class="title ellipsis">{{ title }}</div>
      <div v-if="subtitle" class="subtitle label">{{ subtitle }}</div>
    </div>

    <div class="grow" />

    <template v-if="!publicMode">
      <router-link v-if="showKbLink" to="/knowledge" class="tlink">
        <el-icon><Collection /></el-icon><span>知识库</span>
      </router-link>
      <!--
        ★ 对话设置：从对话页直达配置（召回开关 / 阈值 / 提示词 / 超时）。

        用户报「点击对话配置没有跳转」—— 此前这个入口**根本不存在**：
        顶栏只有「返回 / 分享」，而「应用配置」只在有 appId 时出现，
        通用问答（无 appId）的页面上没有任何通往配置的路。
        ⇒ 不是"点了没反应"，是"那个按钮不存在"（两者在界面上很像，都不动）。
      -->
      <router-link v-if="showChatSettings" to="/settings/chat" class="tlink">
        <el-icon><Timer /></el-icon><span>对话设置</span>
      </router-link>
      <router-link v-if="showAppConfig" :to="appConfigTo" class="tlink">
        <el-icon><Setting /></el-icon><span>应用配置</span>
      </router-link>
    </template>
    <button v-if="showShare" type="button" class="tlink" @click="emit('share')">
      <el-icon><Share /></el-icon><span>分享</span>
    </button>
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
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 0 var(--space-md);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-rule);
}

.back {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  border: none;
  background: transparent;
  color: var(--color-ink-2);
  font-size: var(--text-2xs);
  font-family: inherit;
  cursor: pointer;
  padding: var(--space-2xs) var(--space-xs);
  border-radius: var(--radius-sm);
  transition:
    background var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.back:hover {
  background: var(--color-hover);
  color: var(--color-accent);
}

.titles {
  min-width: 0;
  text-align: center;
  flex: 1;
}

.title {
  font-size: var(--text-md);
  font-weight: 600;
  line-height: var(--leading-title);
  color: var(--color-ink);
}

.subtitle {
  line-height: var(--leading-tight);
}

.tlink {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
  padding: var(--space-2xs) var(--space-xs);
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  transition:
    background var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.tlink:hover {
  background: var(--color-hover);
  color: var(--color-accent);
}
</style>
