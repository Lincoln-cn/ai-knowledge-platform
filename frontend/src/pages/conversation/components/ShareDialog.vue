<template>
  <el-dialog v-model="visible" title="分享" width="520px" append-to-body>
    <p class="micro muted">
      生成公开链接后，外部访客无需登录即可查看本会话。凭证可随时撤销，撤销后链接立即失效。
    </p>

    <!-- 未发布 / 无可分享对象：可读提示，不静默 -->
    <div v-if="!conversationId && !messageId" class="notice warn micro">
      当前还没有可分享的会话。请先选择或发送一条消息，再回来生成链接。
    </div>

    <template v-else>
      <div class="gen">
        <div class="field">
          <span class="label">有效期</span>
          <el-radio-group v-model="expiresMode" size="small">
            <el-radio-button value="never">长期有效</el-radio-button>
            <el-radio-button value="date">指定日期</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-if="expiresMode === 'date'"
            v-model="expiresAt"
            type="datetime"
            placeholder="选择失效时间"
            class="expire-pick"
            :disabled-date="disabledDate"
          />
        </div>
        <el-button type="primary" :loading="creating" @click="create">
          {{ messageId ? '生成消息链接' : '生成会话链接' }}
        </el-button>
      </div>

      <div v-if="messagePreview" class="preview">
        <div class="micro muted">将同时复制这段内容</div>
        <div class="preview-text micro">{{ messagePreview }}</div>
      </div>

      <div class="list-hd">
        <span class="label">已生成链接</span>
        <el-button link size="small" :loading="loading" @click="load">刷新</el-button>
      </div>

      <div v-if="loadError" class="notice err micro">
        <div><b>加载失败：</b>{{ loadError.reason }}</div>
        <div v-if="loadError.hint">{{ loadError.hint }}</div>
      </div>
      <div v-else-if="loading" class="micro muted">加载中…</div>
      <div v-else-if="!shares.length" class="micro muted">还没有生成过链接。</div>
      <ul v-else class="share-list">
        <li v-for="s in shares" :key="s.token" class="share-item">
          <div class="share-main">
            <div class="share-scope micro muted">
              {{ s.revokedAt ? '已撤销' : isExpired(s) ? '已失效' : '有效' }}
              · {{ s.expiresAt ? formatTime(s.expiresAt) + ' 失效' : '长期有效' }}
            </div>
            <div class="share-url">
              <code class="ellipsis">{{ s.url }}</code>
            </div>
            <div class="micro muted">token: {{ s.token }}</div>
          </div>
          <div class="share-ops">
            <el-button link size="small" type="primary" @click="copyUrl(s.url)">复制</el-button>
            <el-button
              v-if="!s.revokedAt"
              link
              size="small"
              type="danger"
              @click="revoke(s)"
            >撤销</el-button>
          </div>
        </li>
      </ul>
    </template>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { conversationsApi } from '@/api'
import type { ConversationShare } from '@/api/conversations'
import { errText } from '../composables/useChatSession'

/** 契约只有 token/url/expiresAt；撤销态是本地收尾（撤销成功后立刻置位） */
type ShareRow = ConversationShare & { revokedAt?: string | null }

const props = defineProps<{
  /** 要分享的会话；与 messageId 二选一，都没有时提示不可分享 */
  conversationId?: string | null
  /** 消息级分享时传入 */
  messageId?: string | null
  /** 随分享附带的消息正文（复制时一并带上） */
  messageText?: string
}>()

const visible = ref(false)
const creating = ref(false)
const loading = ref(false)
const loadError = ref<{ reason: string; hint?: string } | null>(null)
const shares = ref<ShareRow[]>([])
const expiresMode = ref<'never' | 'date'>('never')
const expiresAt = ref<Date | null>(null)

const messagePreview = computed(() => props.messageText || '')

const formatTime = (iso: string) => dayjs(iso).format('YYYY-MM-DD HH:mm')
const disabledDate = (d: Date) => d.getTime() < Date.now() - 24 * 3600_000

const isExpired = (s: ShareRow) =>
  !!s.expiresAt && new Date(s.expiresAt).getTime() < Date.now()

const open = () => {
  visible.value = true
  loadError.value = null
  expiresMode.value = 'never'
  expiresAt.value = null
  shares.value = []
  if (props.conversationId) load()
}

const load = async () => {
  if (!props.conversationId) return
  loading.value = true
  loadError.value = null
  try {
    const list = await conversationsApi.listConversationShares(props.conversationId)
    shares.value = list.map((s: ConversationShare) => ({ ...s, revokedAt: null }))
  } catch (error) {
    loadError.value = errText(error, '加载已生成链接失败')
  } finally {
    loading.value = false
  }
}

const create = async () => {
  const conversationId = props.conversationId
  if (!conversationId) return
  if (expiresMode.value === 'date' && !expiresAt.value) {
    ElMessage.warning('请选择失效时间，或改回「长期有效」')
    return
  }
  creating.value = true
  try {
    const created = await conversationsApi.createConversationShare(conversationId, {
      scope: props.messageId ? 'message' : 'conversation',
      messageId: props.messageId || undefined,
      expiresAt:
        expiresMode.value === 'date' && expiresAt.value
          ? dayjs(expiresAt.value).toISOString()
          : null
    })
    shares.value = [{ ...created, revokedAt: null }, ...shares.value]
    ElMessage.success('链接已生成')
    await copyUrl(created.url)
  } catch (error) {
    const { reason, hint } = errText(error, '生成分享链接失败')
    ElMessage.error(hint ? `${reason}（${hint}）` : reason)
  } finally {
    creating.value = false
  }
}

const copyUrl = async (url: string) => {
  const payload = [url, messagePreview.value].filter(Boolean).join('\n\n')
  try {
    await navigator.clipboard.writeText(payload)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

const revoke = async (s: ShareRow) => {
  if (!props.conversationId) return
  try {
    await ElMessageBox.confirm(
      '撤销是终态：撤销后该链接立即失效，且不可恢复，只能重新生成。确认撤销？',
      '确认撤销链接',
      { type: 'warning', confirmButtonText: '撤销' }
    )
  } catch {
    return
  }
  try {
    await conversationsApi.revokeConversationShare(props.conversationId, s.token)
    s.revokedAt = new Date().toISOString()
    ElMessage.success('已撤销，链接立即失效')
  } catch (error) {
    const { reason, hint } = errText(error, '撤销失败')
    ElMessage.error(hint ? `${reason}（${hint}）` : reason)
  }
}

defineExpose({ open })
</script>

<style scoped>
.gen {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.field {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.expire-pick {
  flex: 1;
  min-width: 180px;
}

.url-row {
  display: flex;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
}

.preview {
  margin-top: var(--space-md);
  padding: var(--space-sm);
  background: var(--color-sunken);
  border-radius: var(--radius-sm);
}

.preview-text {
  margin-top: var(--space-xs);
  line-height: var(--leading-body);
  max-height: 100px;
  overflow: auto;
  white-space: pre-wrap;
}

.list-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-md);
  margin-bottom: var(--space-xs);
}

.share-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  max-height: 220px;
  overflow: auto;
}

.share-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.share-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
}

.share-url code {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
  display: block;
  max-width: 100%;
}

.share-ops {
  display: flex;
  flex-shrink: 0;
  gap: var(--space-2xs);
}
</style>
