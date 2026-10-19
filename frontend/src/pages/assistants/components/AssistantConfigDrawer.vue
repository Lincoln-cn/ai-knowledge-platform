<template>
  <el-drawer
    v-model="visible"
    :title="assistant ? `配置 · ${assistant.name}` : '配置应用'"
    size="min(1080px, 94vw)"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <div class="top-bar">
      <span class="grow micro muted">
        {{ assistant?.builtin ? '内置应用 · 对话设定只读，仅供对话' : '顶部操作：保存草稿 / 发布' }}
      </span>
      <el-button
        size="small"
        :disabled="!assistant || assistant.builtin || saving"
        :loading="saving"
        @click="saveDraft"
      >保存草稿</el-button>
      <el-button
        size="small"
        type="primary"
        :disabled="!assistant || saving || publishing"
        :loading="publishing"
        @click="publish"
      >发布</el-button>
    </div>

    <div v-loading="loading" class="body">
      <!-- 左：配置表单 -->
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
        class="form"
        :disabled="assistant?.builtin"
      >
        <el-form-item label="对话模型" prop="modelId">
          <el-select v-model="form.modelId" placeholder="仅列出已加载的对话模型" style="width: 100%">
            <el-option
              v-for="m in chatModels"
              :key="m.id"
              :label="`${m.name}（已加载）`"
              :value="m.id"
            />
          </el-select>
          <div class="hint micro">{{ thinkingModeHint }}</div>
        </el-form-item>

        <!-- 思考模式：★ 问答链路尚未接入上游思考参数（#185）⇒ 不再展示一个不生效的开关。
             模型有能力声明时给一句可读说明，而不是让用户以为"这个模型没有思考模式"。 -->
        <el-form-item v-if="thinkingMode !== 'NONE'" label="思考模式">
          <span class="hint micro">
            该模型声明了「{{ THINKING_MODE_LABEL[thinkingMode] }}」思考能力，但当前问答链路尚未接入上游思考参数（#185），
            因此暂不提供开关；已保存的配置原样保留，不会因本页操作被改写。
          </span>
        </el-form-item>

        <el-form-item label="角色设定" prop="rolePrompt">
          <el-input
            v-model="form.rolePrompt"
            type="textarea"
            :rows="4"
            resize="none"
            maxlength="2000"
            show-word-limit
            placeholder="定义助手的身份、语气与边界"
          />
          <div class="row gap-8" style="width: 100%; margin-top: var(--space-2xs)">
            <!-- ★ `#184`：/prompt-configs 与 /prompt-types 后端未实现（真栈 404）⇒ 不再保留一个
                 "弹窗打开但永远空"的入口。显式禁用 + 写明原因，等提示词域落地后再恢复。 -->
            <el-button size="small" disabled title="提示词模板尚未接入（/prompt-configs 未实现）">
              从提示词选用
            </el-button>
            <el-button size="small" @click="form.rolePrompt = ''">清空</el-button>
          </div>
          <div class="hint micro">提示词模板管理尚未接入（后端 /prompt-configs 未实现），当前请直接编辑角色设定</div>
        </el-form-item>

        <el-form-item label="提示词">
          <div class="prompt-tags">
            <el-tag
              v-for="p in pickedPrompts"
              :key="p.id"
              closable
              size="small"
              @close="removePrompt(p.id)"
            >{{ p.icon }} {{ p.roleName }}</el-tag>
            <span v-if="!pickedPrompts.length" class="micro muted">未选用提示词模板</span>
          </div>
          <div class="hint micro">选用后写入「角色设定」，可再手工微调</div>
        </el-form-item>

        <el-form-item label="知识库">
          <div class="kb-tags">
            <el-tag
              v-for="kb in pickedKbs"
              :key="kb.id"
              closable
              size="small"
              :type="kb.enabled ? '' : 'danger'"
              @close="removeKb(kb.id)"
            >{{ kb.name }}{{ kb.enabled ? '' : '（已停用）' }}</el-tag>
            <span v-if="!pickedKbs.length" class="micro muted">未绑定知识库 —— 答案将无引用</span>
          </div>
          <el-select
            v-model="kbDraft"
            size="small"
            placeholder="添加知识库"
            style="width: 220px; margin-top: var(--space-2xs)"
            @change="addKb"
          >
            <el-option
              v-for="kb in availableKbs"
              :key="kb.id"
              :label="kb.name"
              :value="kb.id"
              :disabled="!kb.enabled"
            />
          </el-select>
        </el-form-item>

        <!-- 检索模式：★ 问答链路当前只实现全文一路（#185 拆分）。
             保留一个不生效的三选一，就是在告诉用户"选了向量就会按向量检索"——那是错误陈述。
             向量 / 混合模式接入后把这一项恢复，并同时补"模式真的进了链路"的判据。 -->
        <el-form-item label="检索模式">
          <span class="hint micro">
            当前问答链路只做<b>全文检索</b>：向量 / 混合模式尚未接入，因此不提供模式选择。
            阈值与 TopN 仍然按下面两项生效。
          </span>
        </el-form-item>

        <el-form-item label="相似度阈值">
          <el-slider
            v-model="form.retrieval.threshold"
            :min="0"
            :max="1"
            :step="0.05"
            style="width: 240px"
            show-input
            :show-input-controls="false"
          />
          <span class="micro muted">先过滤后取 TopN —— 顺序反了会把噪声带进上下文</span>
        </el-form-item>

        <el-form-item label="TopN">
          <el-input-number v-model="form.retrieval.topN" :min="1" :max="20" />
          <span class="micro muted" style="margin-left: var(--space-xs)">默认 5</span>
        </el-form-item>

        <el-form-item label="无引用策略">
          <el-radio-group v-model="form.retrieval.noRefStrategy">
            <!-- ★ 同上：机器码（契约 NoRefStrategy） -->
            <el-radio-button value="REFUSE">拒答</el-radio-button>
            <el-radio-button value="FREE_ANSWER">自由回答</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="开场白">
          <el-input
            v-model="form.prologue"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="300"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="多轮上下文">
          <el-switch v-model="form.multiTurn" />
          <div class="hint micro">开启后提问会携带当前会话最近的上下文</div>
        </el-form-item>

        <el-form-item label="推荐问题">
          <div v-for="(item, idx) in form.quickQuestions" :key="idx + '-' + item" class="qq-row">
            <el-input v-model="form.quickQuestions[idx]" size="small" maxlength="50" />
            <el-button link size="small" type="danger" @click="form.quickQuestions.splice(idx, 1)">删除</el-button>
          </div>
          <el-button size="small" style="margin-top: 6px" @click="form.quickQuestions.push('')">添加</el-button>
        </el-form-item>
      </el-form>

      <!-- 右：调试区 -->
      <div class="debug-col panel">
        <DebugPanel
          ref="debugRef"
          :app-id="assistant?.id ?? null"
          :published="assistant?.published ?? false"
          :warnings="publishWarnings"
        />
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { assistantsApi, modelsApi, knowledgeApi } from '@/api'
import { ApiError, ErrorCode } from '@/api/types'
import type { Assistant, PublishCheckItem } from '@/api/assistants'
import type { RetrievalMode, RetrievalParams } from '@/api/retrieval'
import type { ModelInfo, ThinkingMode } from '@/api/models'
import type { KnowledgeBase } from '@/api/knowledge'
import type { PromptConfig } from '@/api/prompts'
import DebugPanel from './DebugPanel.vue'

const props = defineProps<{ assistant: Assistant | null }>()
const emit = defineEmits<{ saved: []; published: [] }>()

const visible = ref(false)
const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const formRef = ref<FormInstance>()
const debugRef = ref<InstanceType<typeof DebugPanel>>()

const chatModels = ref<ModelInfo[]>([])
const allKbs = ref<KnowledgeBase[]>([])
const pickedPrompts = ref<PromptConfig[]>([])
const kbDraft = ref('')
const publishWarnings = ref<string[]>([])

const form = reactive({
  modelId: '',
  rolePrompt: '',
  promptIds: [] as string[],
  kbIds: [] as string[],
  retrieval: {
    mode: 'HYBRID' as RetrievalMode,
    threshold: 0.6,
    topN: 5,
    noRefStrategy: 'REFUSE' as RetrievalParams['noRefStrategy']
  },
  prologue: '',
  multiTurn: true,
  thinkingEnabled: false,
  quickQuestions: [] as string[]
})

const rules: FormRules = {
  modelId: [{ required: true, message: '请选择对话模型', trigger: 'change' }],
  rolePrompt: [{ required: true, message: '请填写角色设定', trigger: 'blur' }]
}

const thinkingMode = computed<ThinkingMode>(() => {
  const model = chatModels.value.find((m) => m.id === form.modelId)
  return model?.thinking ?? 'NONE'
})

/** 只用于"模型声明了什么"的说明文案（开关本身尚未接入，见模板）。 */
const THINKING_MODE_LABEL: Record<ThinkingMode, string> = {
  NONE: '无',
  TOGGLEABLE: '可开关',
  ALWAYS_ON: '强制开启'
}

const thinkingModeHint = computed(() => {
  const model = chatModels.value.find((m) => m.id === form.modelId)
  if (!model) return '选择模型后按其思考能力呈现说明'
  if (model.thinking === 'NONE') return '该模型未声明思考能力'
  return `该模型声明思考模式为「${THINKING_MODE_LABEL[model.thinking]}」；问答链路尚未接入该参数（#185）`
})

const pickedKbs = computed(() =>
  form.kbIds
    .map((id) => allKbs.value.find((k) => k.id === id))
    .filter((k): k is KnowledgeBase => Boolean(k))
)

const availableKbs = computed(() => allKbs.value.filter((k) => !form.kbIds.includes(k.id)))

const removePrompt = (id: string) => {
  form.promptIds = form.promptIds.filter((x) => x !== id)
  pickedPrompts.value = pickedPrompts.value.filter((p) => p.id !== id)
}

const addKb = (id: string) => {
  if (id && !form.kbIds.includes(id)) form.kbIds.push(id)
  kbDraft.value = ''
}

const removeKb = (id: string) => {
  form.kbIds = form.kbIds.filter((x) => x !== id)
}

const loadOptions = async () => {
  const [modelResult, kbResult] = await Promise.all([
    modelsApi.listModels({ kind: 'CHAT', status: 'LOADED', size: 50 }),
    knowledgeApi.listKnowledgeBases({ size: 50 }).catch(() => ({ list: [], total: 0, page: 1, size: 50 }))
  ])
  chatModels.value = modelResult.list
  allKbs.value = kbResult.list
}

const fillForm = (a: Assistant) => {
  // 这个抽屉只服务**简单应用**（编排应用走画布），所以 modelId 一定有；
  // 契约上它是可选的（编排没有），这里给空串兜底，交给下面的 onModelChange 走「未选」分支
  form.modelId = a.modelId ?? ''
  form.rolePrompt = a.rolePrompt
  form.promptIds = [...a.promptIds]
  form.kbIds = [...a.kbIds]
  form.retrieval = { ...a.retrieval }
  form.prologue = a.prologue
  form.multiTurn = a.multiTurn
  form.thinkingEnabled = a.thinkingEnabled
  form.quickQuestions = [...a.quickQuestions]
}

const loadPublishWarnings = async () => {
  if (!props.assistant?.id) {
    publishWarnings.value = []
    return
  }
  try {
    const check = await assistantsApi.checkAssistantBeforePublish(props.assistant.id)
    publishWarnings.value = check.items
      .filter((i: PublishCheckItem) => !i.passed)
      .map((i: PublishCheckItem) => `${i.label}${i.detail ? '：' + i.detail : ''}`)
  } catch {
    publishWarnings.value = []
  }
}

const toBody = () => ({
  name: props.assistant?.name ?? '',
  icon: props.assistant?.icon ?? '🤖',
  description: props.assistant?.description ?? '',
  modelId: form.modelId,
  kbIds: form.kbIds,
  rolePrompt: form.rolePrompt,
  promptIds: form.promptIds,
  retrieval: { ...form.retrieval },
  multiTurn: form.multiTurn,
  // ★ `#185`：思考开关尚未接入 ⇒ 原样回写已保存值，不在保存时改写它
  thinkingEnabled: form.thinkingEnabled,
  prologue: form.prologue,
  quickQuestions: form.quickQuestions.filter((q) => q.trim())
})

const saveDraft = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !props.assistant) return
  saving.value = true
  try {
    await assistantsApi.updateAssistant(props.assistant.id, toBody())
    ElMessage.success('草稿已保存')
    emit('saved')
    await loadPublishWarnings()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const publish = async () => {
  if (!props.assistant) return
  publishing.value = true
  try {
    // 先存草稿再校验，避免用旧配置校验
    await assistantsApi.updateAssistant(props.assistant.id, toBody())
    const check = await assistantsApi.checkAssistantBeforePublish(props.assistant.id)
    if (!check.passed) {
      publishWarnings.value = check.items
        .filter((i) => !i.passed)
        .map((i) => `${i.label}${i.detail ? '：' + i.detail : ''}`)
      const failed = check.items.filter((i) => !i.passed)
      await ElMessageBox.confirm(
        `发布前校验有 ${failed.length} 项未通过：\n${failed.map((i) => `· ${i.label}`).join('\n')}`,
        '无法发布',
        { type: 'warning', confirmButtonText: '去修改', showCancelButton: false }
      ).catch(() => undefined)
      return
    }
    await assistantsApi.setAssistantPublished(props.assistant.id, true)
    ElMessage.success('已发布')
    emit('published')
    emit('saved')
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === ErrorCode.PUBLISH_CHECK_FAILED) {
        ElMessage.warning({ message: `${error.message}${error.hint ? '。' + error.hint : ''}`, duration: 6000 })
        await loadPublishWarnings()
      } else {
        ElMessage.error(error.message)
      }
    } else {
      ElMessage.error('发布失败')
    }
  } finally {
    publishing.value = false
  }
}

const reset = () => {
  formRef.value?.clearValidate()
  debugRef.value?.reset()
  publishWarnings.value = []
  pickedPrompts.value = []
}

const open = async () => {
  visible.value = true
  loading.value = true
  try {
    await loadOptions()
    if (props.assistant) fillForm(props.assistant)
    await loadPublishWarnings()
  } finally {
    loading.value = false
  }
}

watch(() => props.assistant, () => {
  if (visible.value && props.assistant) fillForm(props.assistant)
})

defineExpose({ open })
</script>

<style scoped>
.top-bar {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-md);
  padding-bottom: var(--space-sm);
  border-bottom: 1px solid var(--color-rule-2);
}

.body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 340px);
  gap: var(--space-md);
  align-items: start;
}

.form {
  min-width: 0;
}

/* 表单密集：标签 12px、输入 14px、行间节奏统一到 --space-* */
.form :deep(.el-form-item__label) {
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  color: var(--color-muted);
}

.form :deep(.el-form-item) {
  margin-bottom: var(--space-md);
}

.form :deep(.el-form-item__content) {
  font-size: var(--text-sm);
  line-height: var(--leading-body);
}

.hint {
  width: 100%;
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.prompt-tags,
.kb-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2xs);
  width: 100%;
}

.qq-row {
  display: flex;
  gap: var(--space-2xs);
  width: 100%;
  margin-bottom: var(--space-2xs);
}

.debug-col {
  padding: var(--space-md);
  position: sticky;
  top: 0;
}
</style>
