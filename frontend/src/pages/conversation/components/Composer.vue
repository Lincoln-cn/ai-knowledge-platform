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
          ★ 请求级重排开关（三态）。用三态而不是 checkbox：`跟随 / 开 / 关` 是三个决定，
          而 checkbox 只有两个 —— 把"跟随"塞进 unchecked 会让用户没法表达"这次强制关"。

          ★ 原先是一个 108px 宽的 `el-select`，三个选项文字被截断，选中了也看不出"到底生效了没有"
          （「跟随」时实际生效值来自应用 / 全局配置，界面上完全不可见）。改成三态分段按钮 +
          右侧一行「当前生效」读数：选「跟随」时必须把跟随到的那个值写出来。
        -->
        <div class="tool rerank">
          <span class="rerank-name">重排</span>
          <el-radio-group v-model="rerankChoice" size="small" :disabled="disabled">
            <el-radio-button value="auto">跟随</el-radio-button>
            <el-radio-button value="on">开</el-radio-button>
            <el-radio-button value="off">关</el-radio-button>
          </el-radio-group>
          <span class="micro muted rerank-state" :title="rerankHint">{{ rerankStateText }}</span>
        </div>
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
  /** 请求级重排开关三态：null = 跟随应用 / 全局 */
  rerankListwise?: boolean | null
  /** 平台全局的重排（LLM listwise）生效值；null = 读不到（不编一个值出来） */
  globalListwise?: boolean | null
}>()

const emit = defineEmits<{
  send: [text: string, attachments: AttachmentDraft[]]
  stop: []
  'update:thinking': [value: boolean]
  'update:rerankListwise': [value: boolean | null]
}>()

const text = ref('')
const attachments = ref<AttachmentDraft[]>([])
const inputEl = ref<HTMLTextAreaElement>()
const thinking = ref(props.thinking ?? false)
const rerankListwise = ref<boolean | null>(props.rerankListwise ?? null)

watch(rerankListwise, (v) => emit('update:rerankListwise', v))
watch(
  () => props.rerankListwise,
  (v) => {
    // ★ 只认三态里的值；`undefined` 不能传下去 —— 那会被当成"没设过"，把用户的"强制关"吞掉
    if (v === true || v === false || v === null) rerankListwise.value = v
  }
)

/**
 * 三态在界面上的表示。
 *
 * `el-radio-button` 用 `null` 当选中值会和"未选中"混在一起，所以内部用
 * `'auto' | 'on' | 'off'` 三个字符串，出门再翻译回 `boolean | null`。
 */
const rerankChoice = computed<'auto' | 'on' | 'off'>({
  get: () => (rerankListwise.value === true ? 'on' : rerankListwise.value === false ? 'off' : 'auto'),
  set: (v) => {
    rerankListwise.value = v === 'on' ? true : v === 'off' ? false : null
  }
})

/**
 * 「跟随」时到底跟随到了什么 —— 这是 1.4 的核心：三态开关本身没问题，
 * 问题是用户选了「跟随」之后界面上没有任何东西说清"现在到底开没开"。
 */
const rerankStateText = computed(() => {
  if (rerankListwise.value === true) return '本次开启'
  if (rerankListwise.value === false) return '本次关闭'
  if (props.globalListwise === true) return '跟随全局 · 开'
  if (props.globalListwise === false) return '跟随全局 · 关'
  return '跟随全局'
})

const rerankHint = computed(() =>
  props.globalListwise === null || props.globalListwise === undefined
    ? '列表重排（LLM listwise）：走交叉编码器把候选段重排一遍，质量更准但每题慢 2～8 秒。当前「跟随」的目标值读不到（全局设置接口不可用）'
    : `列表重排（LLM listwise）：走交叉编码器把候选段重排一遍，质量更准但每题慢 2～8 秒。全局当前${props.globalListwise ? '开' : '关'}`
)

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

/* 重排三态：外层只是排布容器，hover 高亮交给里面的分段按钮 */
.tool.rerank {
  gap: var(--space-2xs);
  padding-left: var(--space-3xs);
}

.tool.rerank:hover {
  background: transparent;
  color: var(--color-muted);
}

.rerank-name {
  color: var(--color-muted);
}

/* 固定宽度：切换三态时不让右侧「发送」按钮左右跳 */
.rerank-state {
  min-width: 6.5em;
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
