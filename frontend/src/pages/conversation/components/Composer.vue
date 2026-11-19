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
        <label v-if="showThinking" class="tool check">
          <input v-model="thinking" type="checkbox" :disabled="disabled" />
          <span>思考模式</span>
        </label>
        <!--
          ★ 这里原先有一个请求级「重排」三态开关（跟随 / 开 / 关），已删除。

          理由（2026-11-19 评审）：重排是**检索运行参数**，不是使用选项 ——
          终端用户不知道 listwise 是什么，也不知道开了每题会慢 2–8 秒，
          他只能感觉到「这个平台卡」。而决策责任已经有两处归属：
          管理员在「检索引擎」页管全局，配置者在应用配置 / 知识库面板管本应用。

          所以请求里保持**不传** `rerankListwise`（= 跟随全局），后端字段与
          `RetrievalService` 的三档优先级一行都不改，这是纯前端删除。

          ★ 注意：`thinking`（思考模式）**保留**给用户 —— 它是可感知的（更慢但更深入），
          用户能凭使用体验自己判断要不要开。重排做不到这一点。
        -->
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
import { computed, nextTick, ref, watch } from 'vue'
import { Paperclip } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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

/*
 * ★ 这里原先有一个「提示词」入口（上弹抽屉 + 分类 + 搜索 + 12 条模板库）。
 *   它被撤掉了 —— **同一页有两个入口做同一件事**：空态已经平铺了一排推荐问题，
 *   再挂一个"翻全部模板"的抽屉，用户要先决定"我该点哪一个"，而他来这一页只想问一句话。
 *
 *   模板库仍然存在，位置变成两处：① 运营在「提示词 → 提问模板」里维护；
 *   ② 对话页空态按问法形态挑几条展示（见 conversation/Index.vue 的推荐问题）。
 *   终端用户不需要看到"目录"，他只需要看到"几个好问题"。
 */

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

/**
 * 只把文字放进输入框，不发送。
 *
 * ★ 提问模板走这条路：「点了只填不发送，留一步可改」（用户拍板的规则）。
 * 与应用自带的快捷问题不同 —— 那些是"试试这样问"，点即发；模板是"照着这个问"，
 * 通常还要改掉里面的 {占位}。
 */
async function fillText(value: string) {
  // ★ 确认逻辑放在这里，而不是各个入口各写一遍：空态推荐问题与应用快捷问题都会走到
  //   这条路上，规则分叉过一次（一处直接覆盖、另一处会问）—— 用户看到的就是
  //   "有时候会问、有时候不问"，而这类不一致最终会被当成"这功能不稳"。
  if (text.value.trim() && text.value.trim() !== value.trim()) {
    const confirmed = await ElMessageBox.confirm(
      '输入框里已经有内容，填入模板会替换掉它。',
      '替换输入框内容',
      { confirmButtonText: '替换', cancelButtonText: '取消', type: 'warning' }
    ).then(
      () => true,
      () => false
    )
    if (!confirmed) return
  }
  text.value = value
  await nextTick()
  inputEl.value?.focus()
}

defineExpose({ sendText, fillText, reset, focus: () => inputEl.value?.focus() })
</script>

<style scoped>
.composer {
  /* 上弹抽屉相对这里定位：抽屉从输入框上沿向上展开，不遮住输入框本身 */
  position: relative;
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

.tool.on {
  background: var(--color-accent-soft);
  color: var(--color-accent);
}

</style>
