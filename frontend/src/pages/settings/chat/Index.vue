<template>
  <div class="page">
    <PageHeader
      title="对话设置"
      desc="通用问答（不挂应用、不带知识库的那条链路）的默认模型与流式行为 —— 这里改完对新会话立即生效"
    />

    <div class="notice info">
      <b>三层继承：应用配置 → 本页 → 全局默认（yaml 兜底）。</b>
      <div class="micro">
        ★ 逐项覆盖，不是「整层二选一」：某个应用只改了空闲超时，心跳仍跟随本页。
        <br />
        ★ <b>空闲超时</b>是 SSE 的<b>唯一</b>超时权威（Spring 的 async 上限已关闭）——
        长回答被「生成超时」掐断时，先来调这个数。
      </div>
    </div>

    <div v-if="loadError" class="notice err">
      <div><b>读取失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" style="margin-top: var(--space-xs)" @click="load">重试</el-button>
    </div>

    <el-form v-else v-loading="loading" label-width="140px" class="settings-form">
      <el-form-item label="默认对话模型">
        <el-select v-model="form.defaultModelId" clearable placeholder="留空 = 自动挑一个可用的" style="width: 320px">
          <el-option v-for="m in chatModels" :key="m.id" :label="`${m.name}（${m.baseModel}）`" :value="m.id" />
        </el-select>
        <div class="micro muted">
          留空时沿用旧行为（<code>pickChatModel()</code> 挑第一个可用的）—— 想固定用哪个就在这里选。
        </div>
      </el-form-item>

      <el-form-item label="流式输出">
        <el-switch v-model="form.streamEnabled" :disabled="!canEdit" />
        <span class="micro muted" style="margin-left: var(--space-sm)">
          关掉 = 一次性返回全文（首字 = 全文，但不受空闲超时影响）
        </span>
      </el-form-item>

      <el-divider content-position="left"><span class="micro">超时与心跳（单位：秒）</span></el-divider>

      <el-form-item label="空闲超时">
        <el-input-number v-model="idleSec" :min="1" :max="3600" :step="10" :disabled="!canEdit" />
        <span class="micro muted" style="margin-left: var(--space-sm)">
          多少秒没有输出就判「生成超时」。★ CPU + 小模型建议 240
        </span>
      </el-form-item>

      <el-form-item label="心跳间隔">
        <el-input-number v-model="heartbeatSec" :min="1" :max="600" :step="5" :disabled="!canEdit" />
        <span class="micro muted" style="margin-left: var(--space-sm)">
          无输出时按它发 keep-alive。★ 必须 ≤ 空闲超时 ÷ 3（连续丢 2 个心跳不该被误判）
        </span>
      </el-form-item>

      <div v-if="heartbeatViolation" class="notice warn">
        ⚠ 心跳 {{ heartbeatSec }}s × 3 = {{ heartbeatSec * 3 }}s，<b>超过</b>空闲超时 {{ idleSec }}s。
        请把心跳调小（≤ {{ Math.floor(idleSec / 3) }}s）或把空闲超时调大（≥ {{ heartbeatSec * 3 }}s）。
        <div class="micro">否则心跳还没发出去就会被判吊死，而日志里只有一句「生成超时」。</div>
      </div>

      <el-form-item label="首字延迟预算">
        <el-input-number v-model="firstTokenSec" :min="1" :max="600" :step="1" :disabled="!canEdit" />
        <span class="micro muted" style="margin-left: var(--space-sm)">
          超过这个时间才有第一个字，按降级处置（ROADMAP B4 要求 ≤ 10 秒）
        </span>
      </el-form-item>

      <el-form-item label="单轮最大输出">
        <el-input-number v-model="form.maxOutputTokens" :min="1" :max="32000" :step="256" :disabled="!canEdit" />
        <el-button link size="small" style="margin-left: var(--space-sm)" @click="form.maxOutputTokens = null">
          设为不限制
        </el-button>
        <div class="micro muted">tokens。留空 = 交给模型自身（可能被模型上下文截断）</div>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" :disabled="!canEdit || !!heartbeatViolation" @click="save">
          保存
        </el-button>
        <el-button :disabled="saving" @click="load">重新读取</el-button>
        <span v-if="updatedAtText" class="micro muted" style="margin-left: var(--space-sm)">
          上次修改：{{ updatedAtText }}
        </span>
      </el-form-item>

      <el-alert
        v-if="!canEdit"
        type="info"
        :closable="false"
        show-icon
        title="只读"
        description="修改平台级对话设置需要「系统管理」权限（system:manage）—— 它能看当前生效值，但不该能改全局默认。"
      />
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { getChatSettings, saveChatSettings, type ChatSettings } from '@/api/chatSettings'
import { listModels } from '@/api/models'
import { ApiError } from '@/api/types'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const canEdit = computed(() => session.can(PERM.systemManage))

const loading = ref(false)
const saving = ref(false)
const loadError = ref<{ reason: string; hint?: string } | null>(null)
const updatedAtText = ref('')
const chatModels = ref<Array<{ id: string; name: string; baseModel: string }>>([])

const form = reactive<ChatSettings>({
  defaultModelId: null,
  streamEnabled: true,
  idleTimeoutMs: 60000,
  heartbeatIntervalMs: 20000,
  firstTokenBudgetMs: 10000,
  maxOutputTokens: null,
  updatedAt: null
})

/**
 * 界面上用**秒**，后端一律毫秒。
 * ★ 换算只在这三个 computed 里发生（回填用 getter、提交用 setter）——
 *   散落在模板里的 `* 1000` 迟早会漏一个，而差 1000 倍的超时**不会报错**。
 */
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

/** 与前/后端同一条不变量：心跳 × 3 ≤ 空闲超时。 */
const heartbeatViolation = computed(() => heartbeatSec.value * 3 > idleSec.value)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const data = await getChatSettings()
    Object.assign(form, data)
    updatedAtText.value = data.updatedAt ? new Date(data.updatedAt).toLocaleString() : ''
  } catch (e) {
    const err = e as ApiError
    loadError.value = { reason: err.message || '未知错误', hint: err.hint }
  } finally {
    loading.value = false
  }
}

async function loadModels() {
  try {
    const page = await listModels({ kind: 'CHAT', size: 100 })
    chatModels.value = page.list.map((m) => ({ id: m.id, name: m.name, baseModel: m.baseModel }))
  } catch {
    // ★ 模型列表取不到不影响"看/改流式参数"——静默降级成空下拉，不弹错
    chatModels.value = []
  }
}

async function save() {
  if (heartbeatViolation.value) {
    ElMessage.warning('心跳间隔 × 3 不能超过空闲超时')
    return
  }
  saving.value = true
  try {
    const saved = await saveChatSettings({
      defaultModelId: form.defaultModelId,
      streamEnabled: form.streamEnabled,
      idleTimeoutMs: form.idleTimeoutMs,
      heartbeatIntervalMs: form.heartbeatIntervalMs,
      firstTokenBudgetMs: form.firstTokenBudgetMs,
      maxOutputTokens: form.maxOutputTokens
    })
    Object.assign(form, saved)
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
})
</script>

<style scoped>
.settings-form {
  max-width: 760px;
}
</style>
