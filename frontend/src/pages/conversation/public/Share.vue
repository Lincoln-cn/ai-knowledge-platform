<template>
  <div class="share-page">
    <!--
      会话分享的只读页（#187）。
      ★ 它**不是**公开对话页：没有输入框、没有推荐问题、也没有"继续提问"的入口 ——
        分享出去的是一份**记录**，不是一张能花的凭证。两者共用一个路由前缀会让人误以为能接着聊。
      ★ 失效态只按后端那句话显示：后端已经把「不存在 / 已撤销 / 已过期 / 会话不存在」分开报，
        这里合并成"链接无效"会把四种下一步动作压成一句（silent-failure 的界面版）。
    -->
    <div v-if="error" class="gate">
      <div class="card panel">
        <div class="gate-status">打不开这条分享</div>
        <h2 class="gate-title">{{ error.reason }}</h2>
        <p v-if="error.hint" class="gate-hint">{{ error.hint }}</p>
        <div class="notice info">
          分享链接是只读的：它不会过期后自动恢复，也不能通过重新登录找回；需要请分享者重新生成一条。
        </div>
      </div>
    </div>

    <template v-else-if="data">
      <header class="head-band">
        <span class="head-wash" aria-hidden="true"></span>
        <TopBar
          :title="data.title"
          subtitle="分享的对话 · 只读"
          :public-mode="true"
          :show-kb-link="false"
          :show-share="false"
          @back="onBack"
        />
        <div class="meta-row panel">
          <div class="meta">
            <div class="name">只读记录</div>
            <div class="desc micro muted">
              分享于 {{ formatInstant(data.createdAt) }}
              <template v-if="data.expiresAt">· 有效期至 {{ formatInstant(data.expiresAt) }}</template>
              <template v-else>· 长期有效</template>
            </div>
          </div>
          <div class="ceiling">
            <ClassificationBadge :level="data.maxClassification" :labels="CLASSIFICATION_LABELS" />
            <span class="micro muted">你看不到高于这个级别的内容</span>
          </div>
        </div>
      </header>

      <div class="body rise">
        <section class="center panel">
          <MessageList
            :messages="data.messages"
            :loading="false"
            :empty-text="'这条分享没有可读的消息'"
            :bot-icon="'AI'"
            :public-mode="true"
            :can-share="false"
          />
        </section>
      </div>
    </template>

    <div v-else class="gate">
      <div class="card panel">
        <el-icon class="gate-icon"><Loading /></el-icon>
        <p class="micro muted">正在打开分享…</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { conversationsApi, ApiError } from '@/api'
import type { PublicConversationShare } from '@/api/conversations'
import ClassificationBadge from '@/components/ClassificationBadge.vue'
import { CLASSIFICATION_LABELS } from '@/utils/classification'
import TopBar from '../components/TopBar.vue'
import MessageList from '../components/MessageList.vue'

const route = useRoute()
const router = useRouter()
const token = computed(() => String(route.params.token || ''))
const data = ref<PublicConversationShare | null>(null)
const error = ref<{ reason: string; hint?: string } | null>(null)

const formatInstant = (value: string): string => {
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('zh-CN', { hour12: false })
}

const load = async () => {
  data.value = null
  error.value = null
  try {
    data.value = await conversationsApi.resolvePublicShare(token.value)
  } catch (caught) {
    error.value =
      caught instanceof ApiError
        ? { reason: caught.message || '分享链接打不开', hint: caught.hint }
        : { reason: caught instanceof Error ? caught.message : '没能连上服务' }
  }
}

const onBack = () => {
  router.push('/')
}

onMounted(() => {
  void load()
})

watch(token, () => {
  void load()
})
</script>

<style scoped>
.share-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--color-paper);
  position: relative;
}

.head-band {
  position: relative;
  flex-shrink: 0;
  padding: var(--space-sm) var(--space-md) var(--space-md);
}

.head-wash {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-image: var(--art-wash);
  background-repeat: no-repeat;
  background-position: 0% 13%;
  background-size: 118% auto;
  mix-blend-mode: multiply;
  opacity: var(--art-opacity-wash);
  mask-image: linear-gradient(to bottom, black 78%, transparent);
  -webkit-mask-image: linear-gradient(to bottom, black 78%, transparent);
}

.head-band :deep(.topbar) {
  position: relative;
  z-index: 1;
  padding: 0;
  background: transparent;
}

.meta-row {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  background: var(--color-graphite);
  border: 1px solid var(--color-graphite-rule);
  border-radius: var(--radius-lg);
}

.name {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-graphite-ink);
  line-height: var(--leading-title);
}

.desc {
  margin-top: var(--space-3xs);
  color: var(--color-graphite-muted);
}

.ceiling {
  margin-left: auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-3xs);
  flex-shrink: 0;
}

.body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: var(--space-sm);
  padding: var(--space-sm);
}

.body.rise {
  animation-delay: 60ms;
}

.center {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.gate {
  flex: 1;
  display: grid;
  place-items: center;
  padding: var(--space-lg);
}

.card {
  max-width: 520px;
  padding: var(--space-lg);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
}

.gate-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-lg);
  background: var(--color-sunken);
  color: var(--color-muted);
  display: grid;
  place-items: center;
  font-size: var(--text-lg);
}

.gate-status {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  letter-spacing: var(--tracking-label);
  color: var(--color-muted);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm);
  padding: var(--space-3xs) var(--space-xs);
  background: var(--color-sunken);
}

.gate-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-md);
  font-weight: 600;
  line-height: var(--leading-title);
  color: var(--color-ink);
}

.gate-hint {
  margin: 0;
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-ink-2);
  text-align: left;
}

.gate .notice {
  text-align: left;
}
</style>
