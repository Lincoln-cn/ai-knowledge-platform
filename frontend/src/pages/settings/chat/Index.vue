<template>
  <div class="page settings-page">
    <PageHeader
      title="对话设置"
      desc="通用问答（不挂应用的那条链路）的模型、提示词、召回与流式行为 —— 改完对新会话立即生效"
    >
      <template #actions>
        <!--
          ★ 状态摘要放在**页头的元信息行**，不另起四个卡片。

          理由（`design.md` 的硬约束）：
          · 「应用页 → Workbench：标题小而功能性，正文由**发丝线**分区，数据/界面本身是内容」；
          · 结构禁令：「不许三列等宽『图标在上-标题-两行文案』的功能格子」——
            四个「标签 + 大数值 + 一行说明」的等宽卡正是那个形状的变体；
          · 仪器面板（签名 4）虽然就是"mono 读数"的形状，但它**每视口只许一个**，
            且各页落点里**没有**这一页。
          ⇒ 所以状态用一行 mono 元信息表达，页面里只留"发丝线分区"的正文。
        -->
        <span class="meta-line micro">
          <span>召回 <b>{{ form.retrievalEnabled ? '启用' : '未启用' }}</b></span>
          <span>空闲 <b>{{ idleSec }}s</b></span>
          <span>心跳 <b>{{ heartbeatSec }}s</b></span>
          <span>输出 <b>{{ form.streamEnabled ? '逐字' : '一次性' }}</b></span>
          <span v-if="currentModelName">模型 <b>{{ currentModelName }}</b></span>
          <span v-if="updatedAtText" class="muted">上次修改 {{ updatedAtText }}</span>
        </span>
      </template>
    </PageHeader>

    <div v-if="loadError" class="notice err">
      <div><b>读取失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" class="mt8" @click="load">重试</el-button>
    </div>

    <template v-else>
      <!-- ── ① 模型与提示词 ─────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">模型与提示词</div>
            <div class="micro muted">决定「谁来回答」和「以什么身份回答」</div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">默认对话模型</label>
            <el-select
              v-model="form.defaultModelId"
              clearable
              placeholder="自动挑选一个可用的"
              :disabled="!canEdit"
              class="field-control"
            >
              <el-option
                v-for="m in chatModels"
                :key="m.id"
                :label="`${m.name}（${m.baseModel}）`"
                :value="m.id"
              />
            </el-select>
            <div class="field-note micro muted">
              留空时沿用旧行为（<code>pickChatModel()</code> 挑第一个可用的）。想固定用哪个就在这里选。
            </div>
          </div>

          <div class="field field-wide">
            <label class="field-label">初始提示词</label>
            <el-input
              v-model="form.systemPrompt"
              type="textarea"
              :rows="4"
              :disabled="!canEdit"
              maxlength="4000"
              show-word-limit
              placeholder="例如：你是企业知识助理。回答简洁、先给结论；不确定时说明不确定，不要编造。"
            />
            <div class="field-note micro muted">
              拼在每轮问题之前（<code>ChatMessage.Role</code> 只有 user/assistant，系统口径在工程里由提示词承担）。
              <b>只对通用问答生效</b> —— 应用有自己的 <code>role_prompt</code>，两者不叠加。
            </div>
          </div>
        </div>
      </section>

      <!-- ── ② 召回 ───────────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">
              知识库召回
              <span class="badge" :class="form.retrievalEnabled ? 'badge-ok' : 'badge-off'">
                {{ form.retrievalEnabled ? '已启用' : '未启用' }}
              </span>
            </div>
            <div class="micro muted">未启用时通用问答<b>不查任何知识库</b>，只用模型自身知识</div>
          </div>
          <el-switch v-model="form.retrievalEnabled" :disabled="!canEdit" />
        </div>
        <div class="panel-body">
          <div v-if="!form.retrievalEnabled" class="notice warn">
            之前通用问答在<b>静默做全库检索</b> —— 问「java hello world」时把 Vue 文档、电子书等以
            <b>0.25 分</b>塞进上下文，模型就照着复述成「创建一个 Vue 单文件组件…」。
            <b>那是答非所问的直接原因。</b>保持不启用是最安全的默认。
          </div>

          <div class="form-grid" :class="{ inert: !form.retrievalEnabled }">
            <div class="field">
              <label class="field-label">相似度下限</label>
              <div class="range-row">
                <el-slider
                  v-model="form.retrievalThreshold"
                  :min="0"
                  :max="1"
                  :step="0.05"
                  :disabled="!canEdit || !form.retrievalEnabled"
                  class="range"
                />
                <span class="range-value">{{ form.retrievalThreshold.toFixed(2) }}</span>
              </div>
              <div class="field-note micro muted">
                低于它的命中直接丢弃。★ 实测 0.25 分就是噪声，建议 <b>0.35 ~ 0.5</b>。
              </div>
            </div>

            <div class="field">
              <label class="field-label">召回条数</label>
              <el-input-number
                v-model="form.retrievalTopN"
                :min="1"
                :max="20"
                :disabled="!canEdit || !form.retrievalEnabled"
                class="field-control narrow"
              />
              <div class="field-note micro muted">条。条数越多，噪声进上下文的概率越高。</div>
            </div>

            <div class="field field-wide">
              <label class="field-label">知识库范围</label>
              <el-select
                v-model="form.retrievalKbIds"
                multiple
                clearable
                collapse-tags
                collapse-tags-tooltip
                placeholder="留空 = 我可见的全部知识库"
                :disabled="!canEdit || !form.retrievalEnabled"
                class="field-control"
              >
                <el-option v-for="kb in kbOptions" :key="kb.id" :label="kb.name" :value="kb.id" />
              </el-select>
              <div class="field-note micro muted">
                ★ 留空时是「全部可见库」—— 这也是之前答非所问的放大器：语料库里什么都搜得到。
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ── ③ 流式与超时 ─────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">流式与超时</div>
            <div class="micro muted">
              决定「回答怎么送到界面」和「多久算超时」 · 顺序：心跳 × 3 ≤ 空闲超时
            </div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">流式输出</label>
            <div class="switch-row">
              <el-switch v-model="form.streamEnabled" :disabled="!canEdit" />
              <span class="micro muted">关掉 = 一次性返回全文</span>
            </div>
          </div>

          <div class="field">
            <label class="field-label">多轮历史</label>
            <div class="input-suffix">
              <el-input-number
                v-model="form.historyMaxMessages"
                :min="0"
                :max="200"
                :step="2"
                :disabled="!canEdit"
              />
              <span class="micro muted">
                条 · 约 {{ Math.floor(form.historyMaxMessages / 2) }} 轮
              </span>
            </div>
            <div class="field-note micro muted">
              同一个会话里往前带多少条消息（user/assistant 各算一条）。
              <b>0 = 不带历史</b>（等价单轮）；带得越多越容易串话题、也越慢。
            </div>
          </div>

          <div class="field">
            <label class="field-label">空闲超时</label>
            <div class="input-suffix">
              <el-input-number
                v-model="idleSec"
                :min="1"
                :max="3600"
                :step="10"
                :disabled="!canEdit"
              />
              <span class="micro muted">秒</span>
            </div>
            <div class="field-note micro muted">
              多少秒没有输出就判「生成超时」。★ 这是 SSE 的<b>唯一</b>超时权威（Spring 的 async 上限已关闭）。
            </div>
          </div>

          <div class="field">
            <label class="field-label">心跳间隔</label>
            <div class="input-suffix">
              <el-input-number
                v-model="heartbeatSec"
                :min="1"
                :max="600"
                :step="5"
                :disabled="!canEdit"
              />
              <span class="micro muted">秒</span>
            </div>
            <div class="field-note micro muted">
              无输出时按它发 keep-alive。必须 ≤ 空闲超时 ÷ 3（连续丢 2 个心跳不该被误判）。
            </div>
          </div>

          <div class="field">
            <label class="field-label">首字延迟预算</label>
            <div class="input-suffix">
              <el-input-number
                v-model="firstTokenSec"
                :min="1"
                :max="600"
                :step="1"
                :disabled="!canEdit"
              />
              <span class="micro muted">秒</span>
            </div>
            <div class="field-note micro muted">超过它按降级处置（ROADMAP B4 要求 ≤ 10 秒）。</div>
          </div>

          <div class="field">
            <label class="field-label">单轮最大输出</label>
            <div class="input-suffix">
              <el-input-number
                v-model="form.maxOutputTokens"
                :min="1"
                :max="32000"
                :step="256"
                :disabled="!canEdit"
              />
              <span class="micro muted">tokens</span>
              <el-button link size="small" @click="form.maxOutputTokens = null">不限制</el-button>
            </div>
            <div class="field-note micro muted">留空 = 交给模型自身（可能被模型上下文截断）。</div>
          </div>
        </div>

        <div v-if="heartbeatViolation" class="notice warn">
          ⚠ 心跳 {{ heartbeatSec }}s × 3 = {{ heartbeatSec * 3 }}s，<b>超过</b>空闲超时
          {{ idleSec }}s。 请把心跳调到 ≤ {{ Math.floor(idleSec / 3) }}s，或把空闲超时调到 ≥
          {{ heartbeatSec * 3 }}s。
          <div class="micro">否则心跳还没发出去就会被判吊死，而日志里只有一句「生成超时」。</div>
        </div>
      </section>

      <!-- ── ④ 推荐追问 ───────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">
              推荐追问
              <span class="badge" :class="form.suggestionEnabled ? 'badge-ok' : 'badge-off'">
                {{ form.suggestionEnabled ? `${form.suggestionCount} 条` : '已关闭' }}
              </span>
            </div>
            <div class="micro muted">
              回答完成后，在下面列出几条「猜你想问」，点一下就发出去
            </div>
          </div>
          <el-switch v-model="form.suggestionEnabled" :disabled="!canEdit" />
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">条数</label>
            <el-input-number
              v-model="form.suggestionCount"
              :min="1"
              :max="10"
              :disabled="!canEdit || !form.suggestionEnabled"
              class="field-control narrow"
            />
            <div class="field-note micro muted">
              默认 3，上限 10。条数越多，生成越慢（1.5B 每条约 +1~2 秒）。
              <b>想关掉请用上面的开关</b> —— 条数填 0 不再是"关闭"的写法。
            </div>
          </div>
          <div class="field">
            <label class="field-label">生成时机</label>
            <div class="field-note micro muted">
              在你读到回答<b>之后</b>才生成（<code>done</code> 之后另发一次请求）——
              不占用回答本身的等待时间。生成不出来就只是不显示，不会报错。
            </div>
          </div>
        </div>
      </section>

      <!-- ── 保存栏 ───────────────────────────────────────────────── -->
      <div class="save-bar panel">
        <div class="save-state">
          <span class="dot" :class="dirty ? 'warn' : 'ok'" />
          <span class="micro">{{ dirty ? '有未保存的更改' : '已保存' }}</span>
        </div>
        <div class="save-actions">
          <el-button :disabled="!dirty || saving" @click="load">放弃更改</el-button>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="!canEdit || !!heartbeatViolation || !dirty"
            @click="save"
          >
            保存
          </el-button>
        </div>
      </div>

      <div v-if="!canEdit" class="notice info">
        <b>只读。</b>修改对话设置需要「配置对话设置」权限（<code>conversation:settings</code>）——
        它在角色管理里是一个<b>独立可勾选项</b>，默认只有管理员拥有。
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { getChatSettings, saveChatSettings, type ChatSettings } from '@/api/chatSettings'
import { listKnowledgeBases } from '@/api/knowledge'
import { listModels } from '@/api/models'
import { ApiError } from '@/api/types'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
/**
 * ★ 用专用权限点 `conversation:settings`（默认只有 ADMIN 拥有），不再用 `system:manage`。
 *
 * 早先的写法让「改一次召回阈值」必须交出整个系统管理权；单开一个权限点后，
 * 它可以单独授给某个角色 —— 这也是用户这句话的落点：
 * 「对话设置这个只有管理员能配置，设定权限」。
 */
const canEdit = computed(() => session.can(PERM.chatSettings))

const loading = ref(false)
const saving = ref(false)
const loadError = ref<{ reason: string; hint?: string } | null>(null)
const updatedAtText = ref('')
const chatModels = ref<Array<{ id: string; name: string; baseModel: string }>>([])
const kbOptions = ref<Array<{ id: string; name: string }>>([])

const form = reactive<ChatSettings>({
  defaultModelId: null,
  streamEnabled: true,
  idleTimeoutMs: 60000,
  heartbeatIntervalMs: 20000,
  firstTokenBudgetMs: 10000,
  systemPrompt: null,
  retrievalEnabled: false,
  retrievalKbIds: null,
  retrievalThreshold: 0.3,
  retrievalTopN: 5,
  historyMaxMessages: 20,
  suggestionEnabled: true,
  suggestionCount: 3,
  maxOutputTokens: null,
  updatedAt: null
})

/** 保存时的快照 —— 用来判断「有没有未保存的更改」 */
const snapshot = ref('')

const idleSec = computed({
  get: () => Math.round(form.idleTimeoutMs / 1000),
  set: (v: number) => (form.idleTimeoutMs = Math.round(v * 1000))
})
const heartbeatSec = computed({
  get: () => Math.round(form.heartbeatIntervalMs / 1000),
  set: (v: number) => (form.heartbeatIntervalMs = Math.round(v * 1000))
})
const firstTokenSec = computed({
  get: () => Math.round(form.firstTokenBudgetMs / 1000),
  set: (v: number) => (form.firstTokenBudgetMs = Math.round(v * 1000))
})

const heartbeatViolation = computed(() => heartbeatSec.value * 3 > idleSec.value)

/** 提交体（与 form 同形，去掉 updatedAt；空白提示词归一成 null） */
function payload(): Omit<ChatSettings, 'updatedAt'> {
  return {
    defaultModelId: form.defaultModelId,
    streamEnabled: form.streamEnabled,
    idleTimeoutMs: form.idleTimeoutMs,
    heartbeatIntervalMs: form.heartbeatIntervalMs,
    firstTokenBudgetMs: form.firstTokenBudgetMs,
    systemPrompt: form.systemPrompt?.trim() ? form.systemPrompt.trim() : null,
    retrievalEnabled: form.retrievalEnabled,
    retrievalKbIds: form.retrievalKbIds?.length ? form.retrievalKbIds : null,
    retrievalThreshold: form.retrievalThreshold,
    retrievalTopN: form.retrievalTopN,
    historyMaxMessages: form.historyMaxMessages,
    suggestionEnabled: form.suggestionEnabled,
    suggestionCount: form.suggestionCount,
    maxOutputTokens: form.maxOutputTokens
  }
}

const dirty = computed(() => JSON.stringify(payload()) !== snapshot.value)

const currentModelName = computed(() => {
  if (!form.defaultModelId) return ''
  return chatModels.value.find((m) => m.id === form.defaultModelId)?.name ?? '（已选模型）'
})

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const data = await getChatSettings()
    Object.assign(form, data)
    snapshot.value = JSON.stringify({ ...payload() })
    updatedAtText.value = data.updatedAt ? new Date(data.updatedAt).toLocaleString() : ''
  } catch (e) {
    const err = e as ApiError
    // ★★ 403 的提示必须把「重新登录」放在第一条。
    //
    //   实测症状：管理员打开这个页面看到「读取失败：无权限 / 请联系管理员为你的角色勾选该权限点」——
    //   而管理员**本来就有**这个权限点（数据库里有、新签发的 JWT 里也有）。
    //   真相是：**JWT 里的 authorities 是签发那一刻固化的**，
    //   权限点是在他上次登录之后才加的 ⇒ 旧 token 里没有 ⇒ 403。
    //
    //   ⇒ 提示里先给能立刻执行的动作（退出重登），再说"确实没勾"那种情形 ——
    //     反过来写会让管理员去查一个根本没问题的配置。
    const isForbidden = err.code === 403 || /无权限|forbidden|403/i.test(err.message || '')
    loadError.value = isForbidden
      ? {
          reason: '当前登录态里没有「配置对话设置」权限',
          hint: '若你确认自己的角色已有该权限，请退出后重新登录（权限在登录时生效）；否则请管理员在「角色管理」里勾选它。'
        }
      : { reason: err.message || '未知错误', hint: err.hint }
  } finally {
    loading.value = false
  }
}

async function loadModels() {
  try {
    const page = await listModels({ kind: 'CHAT', size: 100 })
    chatModels.value = page.list.map((m) => ({ id: m.id, name: m.name, baseModel: m.baseModel }))
  } catch {
    // ★ 取不到模型列表不影响「看/改其它设置」—— 静默降级成空下拉
    chatModels.value = []
  }
}

async function loadKnowledgeBases() {
  try {
    const page = await listKnowledgeBases({ size: 100 })
    kbOptions.value = page.list.map((kb) => ({ id: kb.id, name: kb.name }))
  } catch {
    kbOptions.value = []
  }
}

async function save() {
  if (heartbeatViolation.value) {
    ElMessage.warning('心跳间隔 × 3 不能超过空闲超时')
    return
  }
  saving.value = true
  try {
    const saved = await saveChatSettings(payload())
    Object.assign(form, saved)
    snapshot.value = JSON.stringify({ ...payload() })
    updatedAtText.value = saved.updatedAt ? new Date(saved.updatedAt).toLocaleString() : ''
    ElMessage.success('已保存（对新会话生效）')
  } catch (e) {
    const err = e as ApiError
    ElMessage.error(err.hint ? `${err.message}（${err.hint}）` : err.message)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  load()
  loadModels()
  loadKnowledgeBases()
})
</script>

<style scoped>
/*
 * ★ 全宽 —— 不限宽、不居中。
 *
 * `design.md` 的 Macrostructure family 把应用页定为 **Workbench**：
 * 「标题小而功能性，正文由**发丝线**分区，**数据/界面本身是内容**」。
 * 本仓的知识库 / 应用管理 / 基础数据页都不设 `max-width`，这一页跟着走。
 *
 * ★ 曾经写过 `max-width: 1080px` 但没居中 ⇒ 整页贴左，1920 屏上右边空出一大片
 *   （用户报：「为什么整体偏左？」）。随后又试过「限宽 + 居中」——
 *   那是把 Web 文档的阅读宽度套到后台表单页上，与本页的 Workbench 形态不符，已撤。
 *
 * ★ 表单字段不会因此被拉成长条：`.form-grid` 用 `minmax(260px, 1fr)`，宽屏自动分多列。
 */
.settings-page {
  width: 100%;
}

/* 状态方点：与全局 .notice 的口径一致（语气靠文字，不靠大色块） */
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dot.ok {
  background: var(--color-ok);
}

.dot.off {
  background: var(--color-muted);
}

.dot.warn {
  background: var(--color-warn);
}

.meta-line {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: baseline;
}

.meta-line b {
  font-weight: 600;
  color: var(--color-ink);
}

.meta-line .muted {
  color: var(--color-muted);
}

/* ── 卡片标题 ─────────────────────────────────────────────────────── */
.card-title {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-ink);
}

.badge {
  padding: 0 var(--space-2xs);
  border-radius: var(--radius-sm);
  font-size: var(--text-2xs);
  font-weight: 400;
  line-height: 18px;
}

.badge-ok {
  color: var(--color-ok);
  background: color-mix(in srgb, var(--color-ok) 12%, transparent);
}

.badge-off {
  color: var(--color-muted);
  background: var(--color-sunken);
}

/* ── 表单栅格 ─────────────────────────────────────────────────────── */
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: var(--space-md);
}

.field-wide {
  grid-column: 1 / -1;
}

.field-label {
  display: block;
  margin-bottom: var(--space-2xs);
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--color-ink);
}

.field-control {
  width: 100%;
}

.field-control.narrow {
  width: 160px;
}

.field-note {
  margin-top: var(--space-2xs);
}

.range-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.range {
  flex: 1;
}

.range-value {
  min-width: 42px;
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  color: var(--color-ink);
  text-align: right;
}

.switch-row,
.input-suffix {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

/* 未启用召回时把下面的控件压暗（保持可读，避免「消失了」的错觉） */
.inert {
  opacity: 0.55;
}

/* ── 保存栏 ───────────────────────────────────────────────────────── */
.save-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-sm) var(--space-md);
  background: var(--color-surface-2);
  z-index: var(--z-sticky);
}

.save-state {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

.save-actions {
  display: flex;
  gap: var(--space-2xs);
}
</style>
