<template>
  <div class="composer panel">
    <!-- 附件标签：展示在输入框上方，可移除；上传中/失败有可读状态 -->
    <div v-if="attachments.length" class="atts">
      <span v-for="a in attachments" :key="a.id" class="att" :class="a.status">
        <span class="att-name ellipsis">{{ a.name }}</span>
        <span class="att-size num faint">{{ formatBytes(a.size) }}</span>
        <span v-if="a.status === 'uploading'" class="att-stat micro muted">上传中…</span>
        <span v-else-if="a.status === 'failed'" class="att-stat micro att-fail">
          {{ a.error || '上传失败' }}
        </span>
        <button type="button" class="att-x" title="移除" @click="removeAtt(a.id)">×</button>
      </span>
    </div>

    <textarea
      ref="inputEl"
      v-model="text"
      class="input"
      rows="3"
      :placeholder="placeholder"
      :disabled="disabled"
      @keydown="onKeydown"
    />

    <div class="bar">
      <div class="left">
        <label class="tool" :class="{ disabled }" title="添加附件">
          <el-icon><Paperclip /></el-icon><span>附件</span>
          <input
            type="file"
            class="hidden-file"
            multiple
            accept=".docx,.txt,.md,.pptx,.pdf"
            :disabled="disabled"
            @change="onPickFiles"
          />
        </label>
        <el-popover placement="top-start" :width="320" trigger="click">
          <template #reference>
            <button type="button" class="tool" :disabled="disabled">
              <el-icon><MagicStick /></el-icon><span>提示词</span>
            </button>
          </template>
          <div class="prompts">
            <div class="micro muted">点击插入到输入框（提示词库接口尚未接入，此处为本地示例）</div>
            <button
              v-for="p in LOCAL_PROMPTS"
              :key="p.title"
              type="button"
              class="prompt-item"
              @click="insertPrompt(p.body)"
            >
              <div class="prompt-title">{{ p.title }}</div>
              <div class="prompt-body micro muted">{{ p.body }}</div>
            </button>
          </div>
        </el-popover>
        <label v-if="showThinking" class="tool check">
          <input v-model="thinking" type="checkbox" :disabled="disabled" />
          <span>思考模式</span>
        </label>
      </div>

      <div class="right">
        <span class="micro muted hint">Enter 发送 · Shift+Enter 换行</span>
        <el-button
          v-if="generating"
          size="small"
          type="danger"
          plain
          @click="emit('stop')"
        >停止</el-button>
        <el-button
          v-else
          size="small"
          type="primary"
          :disabled="disabled || !text.trim() || hasBlockingAtt"
          @click="submit"
        >发送</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { MagicStick, Paperclip } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { conversationsApi } from '@/api'
import {
  errText,
  formatBytes,
  type AttachmentDraft
} from '../composables/useChatSession'

const props = defineProps<{
  generating: boolean
  disabled?: boolean
  placeholder?: string
  showThinking?: boolean
  thinking?: boolean
}>()

const emit = defineEmits<{
  send: [text: string, attachments: AttachmentDraft[]]
  stop: []
  'update:thinking': [value: boolean]
}>()

const text = ref('')
const attachments = ref<AttachmentDraft[]>([])
const inputEl = ref<HTMLTextAreaElement>()
const thinking = ref(props.thinking ?? false)

/** 上传中或已失败的附件不允许发出去 —— 发送时只带真实 id */
const hasBlockingAtt = computed(() =>
  attachments.value.some((a) => a.status === 'uploading' || a.status === 'failed')
)

watch(thinking, (v) => emit('update:thinking', v))
watch(
  () => props.thinking,
  (v) => {
    if (typeof v === 'boolean') thinking.value = v
  }
)

/** 本地示例提示词。契约里暂无提示词库列表接口，见报告「契约缺口」 */
const LOCAL_PROMPTS = [
  {
    title: '风险清单',
    body: '请逐条列出风险点，标注依据编号，并给出可执行的修改建议。'
  },
  {
    title: '对比摘要',
    body: '请对比两段内容的差异，并用表格总结。'
  },
  {
    title: '通俗解释',
    body: '请用非技术人员能听懂的语言解释以下概念，并举一个工作中的例子。'
  }
]

function insertPrompt(body: string) {
  text.value = text.value ? `${text.value}\n${body}` : body
  inputEl.value?.focus()
}

async function onPickFiles(e: Event) {
  const input = e.target as HTMLInputElement
  // 先拷贝 FileList 再清 input —— 清 value 在部分浏览器会把 FileList 也抽空
  const picked = input.files ? Array.from(input.files) : []
  input.value = ''
  if (!picked.length) return

  for (const file of picked) {
    const localId = `att_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`
    const draft: AttachmentDraft = {
      id: localId,
      name: file.name,
      size: file.size,
      mime: file.type || undefined,
      status: 'uploading'
    }
    attachments.value.push(draft)

    try {
      const uploaded = await conversationsApi.uploadFile(file)
      // 换成服务端真实 id；若用户已在此期间移除，则不再写回
      const cur = attachments.value.find((a) => a.id === localId)
      if (!cur) continue
      cur.id = uploaded.id
      cur.name = uploaded.name || file.name
      cur.size = uploaded.size || file.size
      cur.mime = uploaded.mime || file.type || undefined
      cur.status = 'ready'
      cur.error = undefined
    } catch (error) {
      const cur = attachments.value.find((a) => a.id === localId)
      if (!cur) continue
      const { reason, hint } = errText(error, '附件上传失败')
      cur.status = 'failed'
      cur.error = hint ? `${reason}（${hint}）` : reason
    }
  }
}

function removeAtt(id: string) {
  attachments.value = attachments.value.filter((a) => a.id !== id)
}

function submit() {
  const value = text.value.trim()
  if (!value || props.generating || hasBlockingAtt.value) return
  // 只发已就绪的真实附件；失败/上传中的已在按钮上拦住，这里再兜一层
  const ready = attachments.value.filter((a) => a.status === 'ready')
  if (attachments.value.some((a) => a.status === 'failed')) {
    ElMessage.warning('有附件上传失败，请移除后再发送')
    return
  }
  emit('send', value, ready)
  text.value = ''
  attachments.value = []
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

/** 推荐问题点击后填入并发送 */
function sendText(value: string) {
  text.value = value
  submit()
}

/**
 * 清空草稿（输入框正文 + 已选附件）。
 *
 * <p>★★ 为什么必须有它（用户原话：「提示词选中后，切换会话，不会清空对话框」）：
 * 输入框里的是**这一条会话的草稿** —— 点「提示词」插进去的那段文字、以及待发的附件，
 * 都属于当前会话。切换到另一条会话时如果还留着，用户会在**另一个会话**里发出
 * 一条带着上一个会话意图的消息（而且很容易没注意到）。
 *
 * <p>★ 这也顺带修掉一个更隐蔽的：切会话后输入框里的旧文本会让"发送"按钮看起来可用，
 * 用户以为是新会话的内容，实际是上一条会话残留的草稿。
 */
function reset() {
  text.value = ''
  attachments.value = []
}

defineExpose({ sendText, reset, focus: () => inputEl.value?.focus() })
</script>

<style scoped>
.composer {
  padding: var(--space-sm) var(--space-md) var(--space-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  flex-shrink: 0;
  background: var(--color-sunken);
  border: 1px solid var(--color-rule-strong);
  border-radius: var(--radius);
}

.composer:focus-within {
  border-color: var(--color-accent);
}

.atts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2xs);
}

.att {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  padding: var(--space-3xs) var(--space-xs);
  font-size: var(--text-2xs);
  max-width: 220px;
}

.att-name {
  max-width: 140px;
}

.att.uploading {
  opacity: 0.75;
}

.att.failed {
  border-color: color-mix(in oklch, var(--color-danger) 30%, var(--color-rule));
}

.att-fail {
  color: var(--color-danger);
}

.att-x {
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--color-muted);
  font-size: var(--text-md);
  line-height: 1;
  padding: 0 var(--space-3xs);
}

.att-x:hover {
  color: var(--color-danger);
}

.input {
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  font-family: var(--font-body);
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-ink);
  background: transparent;
  min-height: 56px;
  padding: 0;
}

.input::placeholder {
  color: var(--color-faint);
}

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.left,
.right {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.tool {
  border: none;
  background: transparent;
  color: var(--color-muted);
  font-size: var(--text-2xs);
  font-family: inherit;
  cursor: pointer;
  padding: var(--space-3xs) var(--space-xs);
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  position: relative;
  transition:
    background var(--dur-micro) var(--ease-out),
    color var(--dur-micro) var(--ease-out);
}

.tool:hover:not(.disabled) {
  background: var(--color-hover);
  color: var(--color-ink-2);
}

.tool.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.tool.check {
  cursor: pointer;
}

.hidden-file {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.hint {
  margin-right: var(--space-2xs);
}

.prompts {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.prompt-item {
  text-align: left;
  border: 1px solid var(--color-rule);
  background: var(--color-surface);
  border-radius: var(--radius);
  padding: var(--space-xs) var(--space-sm);
  cursor: pointer;
  font-family: inherit;
  transition: border-color var(--dur-micro) var(--ease-out);
}

.prompt-item:hover {
  border-color: var(--color-accent-line);
}

.prompt-title {
  font-size: var(--text-2xs);
  font-weight: 600;
  color: var(--color-ink);
}

.prompt-body {
  margin-top: var(--space-3xs);
  line-height: var(--leading-tight);
}
</style>
