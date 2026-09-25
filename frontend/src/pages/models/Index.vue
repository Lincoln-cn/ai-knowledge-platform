<template>
  <div class="page">
    <PageHeader title="模型管理" desc="模型是问答与向量化的底座。能力三态决定界面上开关怎么呈现，不是可选项">
      <template #actions>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        <el-button size="small" type="primary" @click="openCreate">添加模型</el-button>
      </template>
    </PageHeader>

    <div class="notice info">
      模型的<b>思考模式</b>与<b>工具调用</b>是三态而非开关：<code>无</code> 时整项不渲染、<code>可开关</code>
      时默认关闭并给说明、<code>强制开启</code> 时置开且禁用并标注原因。这直接决定应用配置页能配出什么。
    </div>

    <div class="filters panel">
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索模型名称或基础模型"
        style="width: 230px"
        clearable
        @keyup.enter="fetchList"
        @clear="fetchList"
      />
      <el-select v-model="query.kind" size="small" placeholder="全部类型" style="width: 130px" @change="fetchList">
        <el-option label="全部类型" value="" />
        <el-option label="对话模型" value="chat" />
        <el-option label="向量模型" value="embedding" />
      </el-select>
      <el-select v-model="query.status" size="small" placeholder="全部状态" style="width: 130px" @change="fetchList">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
      </el-select>
      <span class="micro muted">
        共 {{ total }} 个
        <template v-if="loadingCount">
          · <span class="live">{{ loadingCount }} 个加载中，自动刷新</span>
        </template>
      </span>
    </div>

    <div v-loading="loading" class="grid">
      <el-card v-for="m in list" :key="m.id" shadow="never" class="model-card" :class="{ failed: m.status === '加载失败' }">
        <div class="mc-head">
          <span class="mc-name ellipsis">{{ m.name }}</span>
          <el-tag v-if="m.builtin" size="small" effect="plain" type="info">内置</el-tag>
          <span class="grow" />
          <span class="status" :class="STATUS_CLASS[m.status]">
            <el-icon v-if="m.status === '加载中'" class="is-loading"><Loading /></el-icon>
            <i v-else class="dot" :class="STATUS_DOT[m.status]" />
            {{ m.status }}
          </span>
        </div>

        <dl class="mc-facts">
          <div class="fact">
            <dt class="label">类型</dt>
            <dd>{{ KIND_LABEL[m.kind] }} · {{ m.supplier }}</dd>
          </div>
          <div class="fact">
            <dt class="label">基础模型</dt>
            <dd class="ellipsis">{{ m.baseModel }}</dd>
          </div>
          <div class="fact">
            <dt class="label">{{ m.kind === 'embedding' ? '向量维度' : '上下文窗口' }}</dt>
            <dd>
              <template v-if="m.kind === 'embedding'">
                <span class="num">{{ m.dimension }}</span> 维
                <span class="micro muted">（与向量表绑定）</span>
              </template>
              <template v-else><span class="num">{{ m.contextWindow?.toLocaleString() }}</span> tokens</template>
            </dd>
          </div>
          <div v-if="m.kind === 'chat'" class="fact">
            <dt class="label">能力</dt>
            <dd class="caps">
              <span class="cap">
                <i class="dot" :class="CAP_DOT[m.thinking]" />
                思考：{{ THINKING_LABEL[m.thinking] }}
              </span>
              <span class="cap">
                <i class="dot" :class="CAP_DOT[m.tools]" />
                工具：{{ TOOLS_LABEL[m.tools] }}
              </span>
            </dd>
          </div>
        </dl>

        <p v-if="m.remark" class="mc-remark micro muted">{{ m.remark }}</p>

        <div v-if="m.status === '加载失败'" class="notice err mc-fail">
          <el-icon><WarningFilled /></el-icon>
          <span>点「重新加载」可重试。若持续失败，检查模型服务是否可用、显存是否足够。</span>
        </div>

        <div class="mc-actions">
          <el-button
            v-if="m.status !== '加载中'"
            link size="small" type="primary"
            @click="reload(m)"
          >{{ m.status === '已加载' ? '重新加载' : '加载' }}</el-button>
          <el-button v-else link size="small" disabled>加载中…</el-button>

          <el-button link size="small" type="primary" @click="openEdit(m)">编辑</el-button>

          <!-- 内置模型不可删 —— 按钮直接不渲染，而不是渲染后报错 -->
          <el-button v-if="!m.builtin" link size="small" type="danger" @click="remove(m)">删除</el-button>
          <span v-else class="micro muted">内置模型不可删除</span>
        </div>
      </el-card>

      <div v-if="!loading && !list.length" class="empty panel">
        <div class="empty-title">暂无模型</div>
        <hr class="rule-2" />
        <p class="micro muted">
          {{ query.keyword || query.kind || query.status ? '当前筛选下没有模型' : '还没有注册任何模型' }}
        </p>
        <el-button size="small" type="primary" @click="openCreate">添加模型</el-button>
      </div>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="fetchList"
      />
    </div>

    <ModelFormDialog
      ref="formRef"
      :model="editing"
      @saved="onSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { modelsApi } from '@/api'
import type { ModelInfo, ModelKind, ModelStatus } from '@/api/models'
import PageHeader from '@/components/PageHeader.vue'
import ModelFormDialog from './components/ModelFormDialog.vue'

const STATUSES: ModelStatus[] = ['未加载', '加载中', '已加载', '加载失败']

const KIND_LABEL: Record<ModelKind, string> = { chat: '对话', embedding: '向量' }
const THINKING_LABEL = {
  NONE: '无',
  TOGGLEABLE: '可开关',
  ALWAYS_ON: '强制开启'
} as const
const TOOLS_LABEL = {
  UNSUPPORTED: '不支持',
  SUPPORTED: '支持',
  PARALLEL: '支持并行'
} as const
const STATUS_CLASS: Record<ModelStatus, string> = {
  未加载: 'st-idle',
  加载中: 'st-loading',
  已加载: 'st-ok',
  加载失败: 'st-err'
}
const STATUS_DOT: Record<ModelStatus, string> = {
  未加载: '',
  加载中: 'warn',
  已加载: 'ok',
  加载失败: 'err'
}
/** 能力三态的点色：不支持=灰、可开关/支持=琥珀、强制/并行=绿 */
const CAP_DOT: Record<string, string> = {
  NONE: '',
  TOGGLEABLE: 'warn',
  ALWAYS_ON: 'ok',
  UNSUPPORTED: '',
  SUPPORTED: 'warn',
  PARALLEL: 'ok'
}

const list = ref<ModelInfo[]>([])
const total = ref(0)
const loading = ref(false)
const pageSize = 12

const query = reactive({
  keyword: '',
  kind: '' as ModelKind | '',
  status: '' as ModelStatus | '',
  page: 1
})

const loadingCount = computed(() => list.value.filter((m) => m.status === '加载中').length)

const fetchList = async () => {
  loading.value = true
  try {
    const result = await modelsApi.listModels({
      keyword: query.keyword || undefined,
      kind: query.kind || undefined,
      status: query.status || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

/* ── 只在有模型处于「加载中」时轮询；完成即停；页面不可见时暂停 ── */
let timer: ReturnType<typeof setTimeout> | null = null

const clearTimer = () => {
  if (timer) clearTimeout(timer)
  timer = null
}

const schedule = () => {
  clearTimer()
  if (!loadingCount.value || document.hidden) return
  timer = setTimeout(async () => {
    await fetchList()
    schedule()
  }, 1500)
}

const onVisibility = () => {
  if (document.hidden) {
    clearTimer()
    return
  }
  // 回到前台立即补一次，不等下一个周期
  fetchList().then(schedule)
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility)
  fetchList().then(schedule)
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  clearTimer()
})

/* ── 操作 ── */
const formRef = ref<InstanceType<typeof ModelFormDialog>>()
const editing = ref<ModelInfo | null>(null)

const openCreate = () => {
  editing.value = null
  formRef.value?.open()
}

const openEdit = (model: ModelInfo) => {
  editing.value = model
  formRef.value?.open()
}

const onSaved = () => {
  fetchList().then(schedule)
}

const reload = async (model: ModelInfo) => {
  await modelsApi.loadModel(model.id)
  ElMessage.success(`已提交加载「${model.name}」，加载需要一点时间`)
  await fetchList()
  schedule()
}

const remove = async (model: ModelInfo) => {
  await ElMessageBox.confirm(
    `删除后使用该模型的应用将无法提问。确认删除「${model.name}」？`,
    '确认删除模型',
    { type: 'warning', confirmButtonText: '删除' }
  )
  await modelsApi.deleteModel(model.id)
  ElMessage.success('已删除')
  await fetchList()
}
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  padding: var(--space-sm) var(--space-md);
}

.live {
  color: var(--color-accent);
  font-weight: 500;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--space-sm);
  min-height: 120px;
}

.model-card {
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
}

.model-card :deep(.el-card__body) {
  padding: var(--space-md);
  height: 100%;
  display: flex;
  flex-direction: column;
}

.model-card.failed {
  border-color: color-mix(in oklch, var(--color-danger) 30%, var(--color-rule));
}

.mc-head {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

.mc-name {
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  max-width: 62%;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-2xs);
}

.st-idle {
  color: var(--color-muted);
}

.st-loading {
  color: var(--color-accent);
}

.st-ok {
  color: var(--color-ok);
}

.st-err {
  color: var(--color-danger);
}

.mc-facts {
  margin: var(--space-sm) 0 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
  flex: 1;
}

.fact {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.fact dt {
  width: 72px;
  flex-shrink: 0;
  color: var(--color-muted);
}

.fact dd {
  margin: 0;
  min-width: 0;
  color: var(--color-ink-2);
  font-variant-numeric: tabular-nums;
}

.caps {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs) var(--space-sm);
}

.cap {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3xs);
  font-size: var(--text-2xs);
}

.mc-remark {
  margin: var(--space-xs) 0 0;
  line-height: var(--leading-body);
}

.mc-fail {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2xs);
  margin-top: var(--space-xs);
  font-size: var(--text-2xs);
}

.mc-actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
  padding-top: var(--space-xs);
  border-top: 1px solid var(--color-rule-2);
}

.empty {
  grid-column: 1 / -1;
  padding: var(--space-xl) var(--space-md);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);
}

.empty .rule-2 {
  width: 48px;
}

.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
