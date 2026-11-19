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
        <!--
          ★ 提示词入口改成「上弹抽屉」而不是气泡下拉（用户原话：「不要做成下拉，
          多了不好看，能不能集成在输入框上方，做一个上弹的抽屉，按照分类使用」）。

          抽屉从输入框上沿向上展开：左分类、右列表、搜索置顶。宽度跟输入框一致，
          打开时不遮住输入框本身 —— 用户是在"一边看题目一边改"的状态里选模板的。
        -->
        <button
          type="button"
          class="tool"
          :class="{ on: drawerOpen }"
          :disabled="disabled"
          @click="toggleDrawer"
        >
          <el-icon><MagicStick /></el-icon><span>提示词</span>
        </button>
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

    <!-- 上弹抽屉：分类在左、题目在右。`v-if` 而不是 `v-show` —— 关着时不占 DOM -->
    <div v-if="drawerOpen" class="prompt-drawer panel" @keydown.esc="drawerOpen = false">
      <div class="pd-head">
        <el-input
          v-model="promptKeyword"
          size="small"
          placeholder="搜索模板名或问题"
          clearable
          class="pd-search"
        />
        <button type="button" class="pd-close" title="关闭（Esc）" @click="drawerOpen = false">
          ×
        </button>
      </div>

      <div class="pd-body">
        <div class="pd-cats">
          <button
            type="button"
            class="pd-cat"
            :class="{ on: !activeType }"
            @click="activeType = ''"
          >全部</button>
          <button
            v-for="t in promptTypes"
            :key="t"
            type="button"
            class="pd-cat"
            :class="{ on: activeType === t }"
            @click="activeType = t"
          >{{ t }}</button>
        </div>

        <div class="pd-list">
          <div v-if="promptLoading" class="micro muted pd-state">正在读取提问模板…</div>
          <div v-else-if="promptError" class="micro muted pd-state">
            {{ promptError }}
            <button type="button" class="pd-retry" @click="loadPrompts">重试</button>
          </div>
          <div v-else-if="!visiblePrompts.length" class="micro muted pd-state">
            {{
              promptKeyword.trim()
                ? '没有匹配的模板'
                : '还没有提问模板 —— 管理员可在「提示词 → 提问模板」里添加'
            }}
          </div>
          <button
            v-for="p in visiblePrompts"
            :key="p.id"
            type="button"
            class="pd-item"
            :title="p.callWord"
            @click="insertPrompt(p)"
          >
            <span class="pd-icon">{{ p.icon }}</span>
            <span class="pd-text">
              <span class="pd-name">{{ p.roleName }}</span>
              <span class="pd-q">{{ p.callWord }}</span>
            </span>
          </button>
        </div>
      </div>

      <div class="pd-foot micro muted">
        点一条就填进输入框（已有内容会先问一句）。<code>{例}</code> 这样的花括号是留给你替换的。
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { MagicStick, Paperclip } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { conversationsApi, promptsApi } from '@/api'
import type { PromptConfig } from '@/api/prompts'
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

/**
 * 提问模板（`kind: 'QUESTION'`）—— **不是**角色指令。
 *
 * ★★ 这里接错一次对象，记下来免得再错：
 * 原先这里拉的是提示词库里的**角色指令**（`kind: 'ROLE'`，「请逐条审查这份合同…」），
 * 那是给**模型**用的身份设定，出现在对话页只会让用户看到一段看不懂的指令文本。
 * 对话页要的是**提问模板**：遇到"不知道该问什么"时，给用户一句照着问的话。
 * 两者的分界写在 `api/prompts.ts` 的 `PromptKind` 上。
 *
 * 拉取时机：点开抽屉时才拉。输入框挂载就发一次请求，属于给每个用户多付一次没人用的往返。
 */
const promptList = ref<PromptConfig[]>([])
const promptLoading = ref(false)
const promptError = ref('')
const promptLoaded = ref(false)
const promptKeyword = ref('')
const drawerOpen = ref(false)
/** 当前选中的分类；空串 = 全部 */
const activeType = ref('')

/** 分类清单取自数据本身（不另维护一份枚举），顺序沿用接口返回顺序 */
const promptTypes = computed(() => {
  const seen: string[] = []
  for (const p of promptList.value) {
    const name = p.typeName || '未分类'
    if (!seen.includes(name)) seen.push(name)
  }
  return seen
})

/** 当前分类 + 关键词过滤后的列表 */
const visiblePrompts = computed(() => {
  const kw = promptKeyword.value.trim().toLowerCase()
  return promptList.value.filter((p) => {
    if (activeType.value && (p.typeName || '未分类') !== activeType.value) return false
    if (!kw) return true
    return `${p.roleName} ${p.callWord} ${p.typeName}`.toLowerCase().includes(kw)
  })
})

async function loadPrompts() {
  promptLoading.value = true
  promptError.value = ''
  try {
    const result = await promptsApi.listPromptConfigs({
      // ★ 显式传 QUESTION：不传会把角色指令一起拉回来
      kind: 'QUESTION',
      page: 1,
      size: 100
    })
    promptList.value = result.list
    promptLoaded.value = true
  } catch (e) {
    // ★ 不静默：抽屉里显示失败原因 + 重试，否则用户会以为"模板库是空的"
    promptError.value = errText(e, '提问模板读取失败').reason
  } finally {
    promptLoading.value = false
  }
}

function toggleDrawer() {
  drawerOpen.value = !drawerOpen.value
  if (drawerOpen.value && !promptLoaded.value && !promptLoading.value) void loadPrompts()
}

/**
 * 把一条提问模板填进输入框。
 *
 * ★ 规则是用户定的：输入框空 ⇒ 直接填入；已有文字 ⇒ **先确认再覆盖**。
 * 不做"追加到末尾"：模板要解决的是"我不知道该怎么问"，而在一段已有文字后面
 * 再挂一个问题，得到的是两个问题拼成的一段。
 */
async function insertPrompt(p: PromptConfig) {
  if (text.value.trim()) {
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
  text.value = p.callWord
  drawerOpen.value = false
  await nextTick()
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

/**
 * 只把文字放进输入框，不发送。
 *
 * ★ 提问模板走这条路：「点了只填不发送，留一步可改」（用户拍板的规则）。
 * 与应用自带的快捷问题不同 —— 那些是"试试这样问"，点即发；模板是"照着这个问"，
 * 通常还要改掉里面的 {占位}。
 */
async function fillText(value: string) {
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

/*
 * 上弹抽屉：从输入框上沿向上展开。
 * ★ 用 `bottom: 100%` 而不是 `top` —— 输入框高度随字数变化，钉在底部才不动。
 */
.prompt-drawer {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + var(--space-2xs));
  z-index: 20;
  padding: var(--space-xs) var(--space-sm) var(--space-2xs);
  max-height: 360px;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-float);
}

.pd-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding-bottom: var(--space-2xs);
  border-bottom: 1px solid var(--color-rule);
}

.pd-search {
  flex: 1;
}

.pd-close {
  border: none;
  background: transparent;
  color: var(--color-muted);
  font-size: var(--text-lg);
  line-height: 1;
  cursor: pointer;
  padding: 0 var(--space-3xs);
}

.pd-close:hover {
  color: var(--color-ink);
}

.pd-body {
  display: flex;
  gap: var(--space-sm);
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

/* 分类常驻左栏：题目一多，靠搜索不如按分类点两下 */
.pd-cats {
  /* 分类多了也要能滚，且不跟着列表一起滚 */
  overflow-y: auto;
  flex: none;
  width: 96px;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
  padding: var(--space-2xs) 0;
  overflow-y: auto;
}

.pd-cat {
  text-align: left;
  border: none;
  background: transparent;
  color: var(--color-muted);
  font-family: inherit;
  font-size: var(--text-xs);
  padding: var(--space-3xs) var(--space-2xs);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.pd-cat:hover {
  background: var(--color-hover);
  color: var(--color-ink);
}

.pd-cat.on {
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-weight: 600;
}

.pd-list {
  flex: 1;
  min-width: 0;
  min-height: 0;
  /* ★ 必须能滚：抽屉定高 360px，14 条模板加上每条两行正文会顶出去，
     没有 overflow 时最后几条会被 `.pd-foot` 压在下面 —— 看得见入口、点不到那一条。 */
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
  padding: var(--space-2xs) 0;
  overflow-y: auto;
}

.pd-state {
  padding: var(--space-xs) 0;
}

.pd-retry {
  border: none;
  background: transparent;
  color: var(--color-accent);
  font-family: inherit;
  font-size: var(--text-2xs);
  cursor: pointer;
  padding: 0 var(--space-3xs);
}

.pd-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2xs);
  text-align: left;
  border: 1px solid transparent;
  background: transparent;
  border-radius: var(--radius-sm);
  padding: var(--space-3xs) var(--space-2xs);
  cursor: pointer;
  font-family: inherit;
}

.pd-item:hover {
  border-color: var(--color-accent-line);
  background: var(--color-surface);
}

.pd-icon {
  flex: none;
  font-size: var(--text-sm);
  line-height: var(--leading-tight);
}

.pd-text {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.pd-name {
  font-size: var(--text-2xs);
  font-weight: 600;
  color: var(--color-ink);
}

.pd-q {
  font-size: var(--text-xs);
  color: var(--color-muted);
  line-height: var(--leading-tight);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.pd-foot {
  padding-top: var(--space-2xs);
  border-top: 1px solid var(--color-rule);
}

.pd-foot code {
  font-family: var(--font-mono);
}
</style>
