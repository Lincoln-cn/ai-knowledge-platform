<template>
  <el-dialog
    v-model="visible"
    title="优化答案 · 沉淀为知识库问题"
    width="640px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <div v-if="item" class="body">
      <div class="notice info">
        这是<b>反馈到质量的闭环</b>：确认是知识缺失后，把「用户会这样问」显式绑到知识库问题列表（FR-03-12 问答对），
        下次同类提问即可命中人工沉淀的答案。沉淀结果可在知识库的问题列表中查看。
      </div>

      <section class="ctx panel">
        <div class="fact">
          <span class="k">原问题</span>
          <span class="v">{{ item.question }}</span>
        </div>
        <div class="fact">
          <span class="k">原回答</span>
          <span class="v muted-text">{{ item.answer }}</span>
        </div>
        <div class="fact">
          <span class="k">用户意见</span>
          <span class="v">{{ item.comment || '（未填写文字意见）' }}</span>
        </div>
      </section>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="沉淀问题" prop="question">
          <el-input
            v-model="form.question"
            type="textarea"
            :rows="3"
            resize="none"
            maxlength="200"
            show-word-limit
            placeholder="写成用户会问的样子，可按反馈意见修正原问题"
          />
        </el-form-item>
        <el-form-item label="目标知识库" prop="kbId">
          <el-select v-model="form.kbId" placeholder="选择要写入的知识库" style="width: 260px" :loading="loadingKbs">
            <el-option v-for="k in kbOptions" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
          <div class="hint micro">
            <template v-if="kbSource === '演示'">
              <el-tag size="small" effect="plain" type="warning">演示目录</el-tag>
              知识库模块尚未迁移，此处为预演用固定目标；接上契约后自动走 <code>knowledgeApi</code>。
            </template>
            <template v-else>问题将写入该知识库的问题列表</template>
          </div>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input
            v-model="form.resolution"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="500"
            show-word-limit
            placeholder="可选。写清为何判定为知识缺失"
          />
        </el-form-item>
        <el-form-item label="一并办结">
          <el-checkbox v-model="form.close">沉淀后将状态流转为「已处理」</el-checkbox>
          <div class="hint micro">不勾选则保持当前状态，仅记录沉淀</div>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="submit">确认沉淀</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { feedbackApi, knowledgeApi } from '@/api'
import { ApiError } from '@/api/types'
import type { FeedbackItem } from '@/api/feedback'

const props = defineProps<{ item: FeedbackItem | null }>()
const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const kbOptions = ref<Array<{ id: string; name: string }>>([])
const loadingKbs = ref(false)
const kbSource = ref<'契约' | '演示'>('演示')

const DEMO_KBS = [
  { id: 'kb1', name: '合同管理制度' },
  { id: 'kb2', name: '采购与供应商' },
  { id: 'kb3', name: '人事制度' }
]

const form = reactive({
  question: '',
  kbId: '',
  resolution: '',
  close: false
})

const rules: FormRules = {
  question: [{ required: true, message: '请填写要沉淀的问题', trigger: 'blur' }],
  kbId: [{ required: true, message: '请选择目标知识库', trigger: 'change' }]
}

const loadKbs = async () => {
  loadingKbs.value = true
  try {
    const result = await knowledgeApi.listKnowledgeBases({ page: 1, size: 50 })
    kbOptions.value = result.list.map((k) => ({ id: k.id, name: k.name }))
    if (kbOptions.value.length) kbSource.value = '契约'
  } catch {
    kbOptions.value = DEMO_KBS
    kbSource.value = '演示'
  } finally {
    loadingKbs.value = false
  }
}

const open = () => {
  visible.value = true
  loadKbs()
  if (props.item) {
    form.question = props.item.question
    form.resolution = props.item.resolution || ''
    form.close = false
  }
}

const reset = () => {
  formRef.value?.clearValidate()
  form.question = ''
  form.kbId = ''
  form.resolution = ''
  form.close = false
}

const submit = async () => {
  if (!props.item) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const resolution = form.resolution || props.item.resolution
  if (form.close && !resolution.trim()) {
    ElMessage.warning('一并办结「已处理」时必须填写处理说明')
    return
  }

  saving.value = true
  try {
    // 契约把「沉淀」收在 handleFeedback.promoteToKbId 上：
    // 必须同时回传 status / assignee / resolution，这里保持原值不越权改状态
    await feedbackApi.handleFeedback(props.item.id, {
      status: form.close ? '已处理' : props.item.status,
      assignee: props.item.assignee,
      resolution,
      promoteToKbId: form.kbId
    })
    ElMessage.success('已沉淀为知识库问题，可在知识库的问题列表中查看')
    visible.value = false
    emit('saved')
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '沉淀失败，请稍后重试'
    const hint = error instanceof ApiError && error.hint ? ` ${error.hint}` : ''
    ElMessage.error(`${message}${hint}`)
  } finally {
    saving.value = false
  }
}

watch(
  () => props.item?.id,
  () => {
    if (visible.value && props.item) open()
  }
)

defineExpose({ open })
</script>

<style scoped>
.body {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.ctx {
  padding: var(--space-sm) var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.fact {
  display: flex;
  gap: var(--space-xs);
  font-size: var(--text-xs);
  line-height: var(--leading-body);
}

.fact .k {
  width: 64px;
  flex-shrink: 0;
  color: var(--color-muted);
  font-size: var(--text-2xs);
}

.fact .v {
  min-width: 0;
  color: var(--color-ink-2);
  word-break: break-word;
}

.muted-text {
  color: var(--color-muted);
}

.hint {
  color: var(--color-muted);
  line-height: var(--leading-body);
  width: 100%;
}
</style>
