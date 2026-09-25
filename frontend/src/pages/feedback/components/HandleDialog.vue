<template>
  <el-dialog
    v-model="visible"
    :title="readonly ? '反馈详情' : '办理反馈'"
    width="720px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <div v-if="item" class="body">
      <section class="ctx panel">
        <div class="ctx-head">
          <el-tag size="small" effect="plain" :type="item.type === 'up' ? 'success' : 'danger'">
            {{ item.type === 'up' ? '赞' : '踩' }}
          </el-tag>
          <span class="micro muted">{{ item.appName }}</span>
          <span class="grow" />
          <el-tag size="small" effect="plain" :type="STATUS_TAG[item.status]">{{ item.status }}</el-tag>
        </div>
        <dl class="ctx-facts">
          <div class="fact">
            <dt>用户问题</dt>
            <dd>{{ item.question }}</dd>
          </div>
          <div class="fact">
            <dt>系统回答</dt>
            <dd>{{ item.answer }}</dd>
          </div>
          <div class="fact">
            <dt>用户反馈</dt>
            <dd :class="{ empty: !item.comment }">{{ item.comment || '（未填写文字意见）' }}</dd>
          </div>
          <div class="fact">
            <dt>关联会话</dt>
            <dd>
              <template v-if="item.conversationId">
                <button type="button" class="link" @click="goConversation">查看对话日志</button>
                <span class="micro muted">（{{ item.conversationTitle || item.conversationId }}）</span>
              </template>
              <span v-else class="muted">—（公开链接产生，无登录会话）</span>
            </dd>
          </div>
          <div class="fact">
            <dt>提交时间</dt>
            <dd>{{ item.createdAt }}</dd>
          </div>
          <div v-if="item.promotedToKbName" class="fact">
            <dt>已沉淀</dt>
            <dd>
              <el-tag size="small" effect="plain" type="warning">{{ item.promotedToKbName }}</el-tag>
              <span class="micro muted">问答对已写入该知识库问题列表</span>
            </dd>
          </div>
        </dl>
      </section>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
        class="form"
        :disabled="readonly"
      >
        <el-form-item label="目标状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio-button v-for="s in statusOptions" :key="s" :value="s">{{ s }}</el-radio-button>
          </el-radio-group>
          <div class="hint micro">{{ statusHint }}</div>
        </el-form-item>

        <el-form-item label="处理人" prop="assignee">
          <el-select
            v-model="form.assignee"
            placeholder="指派给自己或他人"
            clearable
            style="width: 260px"
            :loading="loadingUsers"
          >
            <el-option v-for="u in users" :key="u.id" :label="u.displayName" :value="u.displayName" />
          </el-select>
          <div class="hint micro">指派后进入「处理中」。处理人存显示名，与用户目录展示一致</div>
        </el-form-item>

        <el-form-item label="处理说明" prop="resolution">
          <el-input
            v-model="form.resolution"
            type="textarea"
            :rows="4"
            resize="none"
            maxlength="500"
            show-word-limit
            placeholder="定位结论与采取的动作。流转到「已处理」时必填"
          />
        </el-form-item>

        <el-form-item v-if="form.status === '已处理'" label="沉淀问答对">
          <el-checkbox v-model="form.promote" :disabled="readonly || !!item.promotedToKbId">
            沉淀为知识库问题（优化答案 · FR-03-12）
          </el-checkbox>
          <div v-if="form.promote" class="promote-row">
            <el-select
              v-model="form.promoteToKbId"
              placeholder="选择目标知识库"
              style="width: 260px"
              :loading="loadingKbs"
            >
              <el-option v-for="k in kbOptions" :key="k.id" :label="k.name" :value="k.id" />
            </el-select>
            <div class="hint micro">
              把「用户问题」写入目标知识库的问题列表，下次同类提问可命中人工沉淀的答案。
              <template v-if="kbSource === '演示'">
                <el-tag size="small" effect="plain" type="warning" class="ml6">演示目录</el-tag>
                接口未返回时退回内置演示目录。
              </template>
            </div>
          </div>
          <div v-else-if="item.promotedToKbName" class="hint micro">
            该反馈已沉淀至「{{ item.promotedToKbName }}」，不重复沉淀
          </div>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button size="small" @click="visible = false">{{ readonly ? '关闭' : '取消' }}</el-button>
      <el-button v-if="!readonly" size="small" type="primary" :loading="saving" @click="submit">
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { feedbackApi, identityApi, knowledgeApi } from '@/api'
import { ApiError } from '@/api/types'
import type { FeedbackItem, FeedbackStatus } from '@/api/feedback'
import type { User } from '@/api/identity'
import type { KnowledgeBase } from '@/api/knowledge'

const STATUS_TAG: Record<FeedbackStatus, 'info' | 'warning' | 'success' | 'danger'> = {
  待处理: 'warning',
  处理中: 'info',
  已处理: 'success',
  已忽略: 'danger'
}

/** 状态机允许的目标（与 mock / 契约注释一致） */
const ALLOWED: Record<FeedbackStatus, FeedbackStatus[]> = {
  待处理: ['处理中', '已处理', '已忽略'],
  处理中: ['处理中', '已处理', '已忽略'],
  已处理: ['已处理'],
  已忽略: ['已忽略']
}

const props = defineProps<{ item: FeedbackItem | null }>()
const emit = defineEmits<{ saved: [] }>()

const router = useRouter()
const visible = ref(false)
const readonly = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const users = ref<User[]>([])
const loadingUsers = ref(false)
const kbOptions = ref<Array<{ id: string; name: string }>>([])
const loadingKbs = ref(false)
const kbSource = ref<'契约' | '演示'>('演示')

/**
 * 接口不可用时退回内置演示目录，保证「优化答案」这条路径在界面上走得通；
 * 接口可达时自动改走契约（`kbSource` 变 '契约'），页面代码不再改。
 */
const DEMO_KBS = [
  { id: 'kb1', name: '合同管理制度' },
  { id: 'kb2', name: '采购与供应商' },
  { id: 'kb3', name: '人事制度' }
]

const form = reactive({
  status: '处理中' as FeedbackStatus,
  assignee: '',
  resolution: '',
  promote: false,
  promoteToKbId: ''
})

const rules: FormRules = {
  status: [{ required: true, message: '请选择目标状态', trigger: 'change' }],
  resolution: [
    {
      validator: (_rule, value, callback) => {
        if (form.status === '已处理' && !String(value ?? '').trim()) {
          callback(new Error('流转到「已处理」必须填写处理说明'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

const statusOptions = computed<FeedbackStatus[]>(() => {
  if (!props.item) return []
  return ALLOWED[props.item.status]
})

const statusHint = computed(() => {
  if (readonly.value) return '已办结的反馈如需继续跟进，请先「重新打开」'
  switch (form.status) {
    case '处理中':
      return '指派处理人并记录进展。可从待处理直接进入'
    case '已处理':
      return '必须填写处理说明；确认是知识缺失时可一并沉淀为问答对'
    case '已忽略':
      return '判定无需处理。已忽略仍可重新打开'
    default:
      return ''
  }
})

const loadUsers = async () => {
  loadingUsers.value = true
  try {
    const result = await identityApi.listUsers({ page: 1, size: 100 })
    users.value = result.list
  } catch {
    // 用户目录拿不到时不阻断办理 —— 处理人可留空
    users.value = []
  } finally {
    loadingUsers.value = false
  }
}

const loadKbs = async () => {
  loadingKbs.value = true
  try {
    const result = await knowledgeApi.listKnowledgeBases({ page: 1, size: 50 })
    kbOptions.value = (result.list as KnowledgeBase[]).map((k) => ({ id: k.id, name: k.name }))
    if (kbOptions.value.length) kbSource.value = '契约'
  } catch {
    kbOptions.value = DEMO_KBS
    kbSource.value = '演示'
  } finally {
    loadingKbs.value = false
  }
}

const goConversation = () => {
  if (!props.item?.conversationId) return
  // 用 query 传会话 id；conversationId 作为跳转契约，会话页自行读取
  router.push({ path: '/conversation', query: { conversationId: props.item.conversationId } })
  visible.value = false
}

const open = (options: { readonly?: boolean } = {}) => {
  visible.value = true
  readonly.value = options.readonly ?? false
  loadUsers()
  loadKbs()

  const item = props.item
  if (!item) return
  form.status = item.status === '待处理' ? '处理中' : item.status
  form.assignee = item.assignee ?? ''
  form.resolution = item.resolution ?? ''
  form.promote = false
  form.promoteToKbId = ''
}

const reset = () => {
  formRef.value?.clearValidate()
  form.status = '处理中'
  form.assignee = ''
  form.resolution = ''
  form.promote = false
  form.promoteToKbId = ''
}

const submit = async () => {
  if (!props.item) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  if (form.promote && !form.promoteToKbId) {
    ElMessage.warning('请选择沉淀目标知识库')
    return
  }

  saving.value = true
  try {
    await feedbackApi.handleFeedback(props.item.id, {
      status: form.status,
      assignee: form.assignee || null,
      resolution: form.resolution,
      promoteToKbId: form.promote ? form.promoteToKbId : undefined
    })
    ElMessage.success(form.status === '已处理' ? '已办结' : '已保存')
    visible.value = false
    emit('saved')
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '保存失败，请稍后重试'
    const hint = error instanceof ApiError && error.hint ? ` ${error.hint}` : ''
    ElMessage.error(`${message}${hint}`)
  } finally {
    saving.value = false
  }
}

// 切换办理对象时重新装载，避免上一条的处理说明带过来
watch(
  () => props.item?.id,
  () => {
    if (visible.value && props.item) open({ readonly: readonly.value })
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
}

.ctx-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-xs);
}

.ctx-facts {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.fact {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  font-size: var(--text-xs);
  line-height: var(--leading-body);
}

.fact dt {
  width: 72px;
  flex-shrink: 0;
  color: var(--color-muted);
  font-size: var(--text-2xs);
}

.fact dd {
  margin: 0;
  min-width: 0;
  color: var(--color-ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.fact dd.empty {
  color: var(--color-muted);
}

.hint {
  color: var(--color-muted);
  line-height: var(--leading-body);
  width: 100%;
}

.ml6 {
  margin-left: var(--space-2xs);
}

.promote-row {
  width: 100%;
  margin-top: var(--space-xs);
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.link {
  border: 0;
  background: none;
  padding: 0;
  color: var(--color-accent);
  cursor: pointer;
  font: inherit;
}

.link:hover {
  color: var(--color-accent-hover);
  text-decoration: underline;
}
</style>
