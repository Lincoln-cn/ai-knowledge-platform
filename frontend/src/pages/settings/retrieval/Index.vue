<template>
  <div class="page settings-page">
    <PageHeader
      title="检索引擎"
      desc="这台机器怎么算检索：查询理解、重排窗口与批大小"
    >
      <template #actions>
        <span class="meta-line micro">
          <span>改写 <b>{{ form.queryUnderstandingEnabled ? '开' : '关' }}</b></span>
          <span>重排 <b>{{ form.rerankEnabled ? `${form.rerankTopK} 条` : '关' }}</b></span>
          <span>listwise <b>{{ form.listwiseEnabled ? `${form.listwiseTopK} 条` : '关' }}</b></span>
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
      <!-- ── ① 查询理解 ─────────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">查询理解（改写与扩展）</div>
            <div class="micro muted">把一句口语化的问题拆成几条可检索的查询 —— 这一段走对话模型</div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">启用查询理解</label>
            <el-switch v-model="form.queryUnderstandingEnabled" :disabled="!canEdit" />
            <div class="field-note micro muted">
              关掉之后检索只用原问题这一路。实测它和重排一起贡献了 <b>+13.9 点</b> B3
              （裸检索 57.4% → 生产口径 71.3%），所以关掉是"省时间换质量"的取舍。
            </div>
          </div>

          <div class="field">
            <label class="field-label">解析失败后重试一次</label>
            <el-switch
              v-model="form.queryUnderstandingRetry"
              :disabled="!canEdit || !form.queryUnderstandingEnabled"
            />
            <div class="field-note micro muted">
              实测：关掉每条省 <b>1.4s</b>，代价是 B3 <b>−1.1 点</b>、B1 −2.0 点。只在低配机器上关。
              <span v-if="!form.queryUnderstandingEnabled" class="warn-text">
                查询理解关掉时这一项无意义（后端会拒绝这种组合）。
              </span>
            </div>
          </div>
        </div>
      </section>

      <!-- ── ② 跨编码器重排 ─────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">跨编码器重排</div>
            <div class="micro muted">对候选段逐条打"这段像不像在回答这个问题"的分 —— 本机 ONNX 推理</div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">启用重排</label>
            <el-switch v-model="form.rerankEnabled" :disabled="!canEdit" />
            <div class="field-note micro muted">
              实测单条约 <b>4.74s</b>（窗口 10）；关掉省这 4.74s，B3 掉约 5 点。
            </div>
          </div>

          <div class="field">
            <label class="field-label">重排窗口</label>
            <el-input-number
              v-model="form.rerankTopK"
              :min="1"
              :max="50"
              :disabled="!canEdit || !form.rerankEnabled"
              controls-position="right"
              class="field-control"
            />
            <div class="field-note micro muted">
              参与重排的候选条数（1–50）。★ 实测窗口 10→20 只把 B1 拉高 5 点、<b>B3 一动不动</b>，
              但重排耗时从 4.74s 涨到约 9.5s —— 它是"召回那条线"的旋钮，不是"排第一对不对"的旋钮。
            </div>
          </div>

          <div class="field">
            <label class="field-label">单次前向条数</label>
            <el-input-number
              v-model="form.rerankForwardBatch"
              :min="0"
              :max="100"
              :disabled="!canEdit || !form.rerankEnabled"
              controls-position="right"
              class="field-control"
            />
            <div class="field-note micro muted">
              <b>0 = 整个窗口一次算完</b>。默认 5（与历史上"窗口 10 分两批"一致）。批内按最长那条补齐，
              所以快慢取决于长度分布 —— 写死任何值都是猜，实测四种组合的差在噪声内。
            </div>
          </div>
        </div>
      </section>

      <!-- ── ③ listwise ─────────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">LLM listwise 重排</div>
            <div class="micro muted">在跨编码器的结果上再问一次模型"这几段哪段最能回答" —— 默认关</div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">启用 listwise</label>
            <el-switch v-model="form.listwiseEnabled" :disabled="!canEdit" />
            <div class="field-note micro muted">
              实测 +5.00 点 B3，但<b>单条 3.12s</b>。它是"质量换延迟"里最贵的一档。
            </div>
          </div>

          <div class="field">
            <label class="field-label">参与条数</label>
            <el-input-number
              v-model="form.listwiseTopK"
              :min="1"
              :max="20"
              :disabled="!canEdit || !form.listwiseEnabled"
              controls-position="right"
              class="field-control"
            />
            <div class="field-note micro muted">
              默认 5（1–20）。条数越多，模型越容易"看不完"，耗时也线性上涨。
            </div>
          </div>
        </div>
      </section>

      <div class="page-foot">
        <el-button type="primary" :loading="saving" :disabled="!canEdit || !dirty" @click="save">
          保存
        </el-button>
        <el-button :disabled="!dirty || saving" @click="reset">撤销</el-button>
        <span v-if="!canEdit" class="micro muted">只读：需要「检索引擎调参」权限（platform:retrieval:manage）</span>
        <span v-else-if="dirty" class="micro muted">有未保存的改动</span>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import {
  getEngineSettings,
  updateEngineSettings,
  type RetrievalEngineSettingsUpdate
} from '@/api/retrieval'
import { ApiError } from '@/api/types'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'

/**
 * 检索引擎运行参数（`/settings/retrieval`）。
 *
 * ★ 这一页的每一格都带**实测读数**，因为它们是同一件事的三面：关掉省多少、质量掉多少。
 * 只写"开关"而不写代价的配置页，等于把"该不该关"这个决定留给运气。
 *
 * ★ 保存是一次整表提交（不是逐格 PATCH）：这几格互相牵连，中间状态被别的请求读到就会出现
 * "按新窗口重排、按旧 listwise 收尾"这种缝里的状态。
 */
const session = useSessionStore()
const canEdit = computed(() => session.can(PERM.retrievalManage))

const EMPTY: RetrievalEngineSettingsUpdate = {
  queryUnderstandingEnabled: true,
  queryUnderstandingRetry: true,
  rerankEnabled: true,
  rerankTopK: 10,
  rerankForwardBatch: 5,
  listwiseEnabled: false,
  listwiseTopK: 5
}

const form = reactive<RetrievalEngineSettingsUpdate>({ ...EMPTY })
const baseline = ref<string>(JSON.stringify(EMPTY))
const saving = ref(false)
const updatedAt = ref<string | null>(null)
const loadError = ref<{ reason: string; hint?: string | null } | null>(null)

const dirty = computed(() => JSON.stringify(form) !== baseline.value)

const updatedAtText = computed(() =>
  updatedAt.value ? new Date(updatedAt.value).toLocaleString() : ''
)

/** 读/写失败的原因文案：优先取后端的 `message`，回落到 `Error.message`。 */
function reasonOf(e: unknown): string {
  if (e instanceof ApiError) return e.message
  return e instanceof Error ? e.message : ''
}

function adopt(data: RetrievalEngineSettingsUpdate & { updatedAt?: string | null }) {
  form.queryUnderstandingEnabled = data.queryUnderstandingEnabled
  form.queryUnderstandingRetry = data.queryUnderstandingRetry
  form.rerankEnabled = data.rerankEnabled
  form.rerankTopK = data.rerankTopK
  form.rerankForwardBatch = data.rerankForwardBatch
  form.listwiseEnabled = data.listwiseEnabled
  form.listwiseTopK = data.listwiseTopK
  updatedAt.value = data.updatedAt ?? null
  baseline.value = JSON.stringify(toPayload())
}

function toPayload(): RetrievalEngineSettingsUpdate {
  return {
    queryUnderstandingEnabled: form.queryUnderstandingEnabled,
    queryUnderstandingRetry: form.queryUnderstandingRetry,
    rerankEnabled: form.rerankEnabled,
    rerankTopK: form.rerankTopK,
    rerankForwardBatch: form.rerankForwardBatch,
    listwiseEnabled: form.listwiseEnabled,
    listwiseTopK: form.listwiseTopK
  }
}

async function load() {
  loadError.value = null
  try {
    adopt(await getEngineSettings())
  } catch (e) {
    loadError.value = {
      reason: reasonOf(e) || '读取检索引擎参数失败',
      hint: e instanceof ApiError ? e.hint : null
    }
  }
}

function reset() {
  adopt(JSON.parse(baseline.value))
}

async function save() {
  saving.value = true
  try {
    adopt(await updateEngineSettings(toPayload()))
    ElMessage.success('已保存，下一次检索就按新值跑')
  } catch (e) {
    const hint = e instanceof ApiError ? e.hint : null
    ElMessage.error(hint ? `${reasonOf(e)}：${hint}` : reasonOf(e) || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-foot {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md) 0;
}
.warn-text {
  color: var(--color-warn);
}
</style>
