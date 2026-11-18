<template>
  <div class="page settings-page">
    <PageHeader
      title="检索引擎"
      desc="这台机器上怎么算检索：查询理解、重排窗口与批大小"
    >
      <template #actions>
        <span class="meta-line micro">
          <span>查询理解 <b>{{ form.queryUnderstandingEnabled ? '开' : '关' }}</b></span>
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
              先把口语化的问题拆成几条可检索的查询，再分别去库里找。
              它会多花一次模型调用，换来更准的召回 —— 低配机器上可以关。
              <span class="muted" title="实测：与重排合计提升引用准确率 13.9 个百分点（裸检索 57.4% → 生产口径 71.3%）">
                关掉就是「省时间换质量」。
              </span>
            </div>
          </div>

          <div class="field">
            <label class="field-label">解析失败后重试一次</label>
            <el-switch
              v-model="form.queryUnderstandingRetry"
              :disabled="!canEdit || !form.queryUnderstandingEnabled"
            />
            <div class="field-note micro muted">
              万一没拆开，就再试一次。
              <span class="muted" title="实测：关掉每条省 1.4 秒，引用准确率降 1.1 个点、命中率降 2.0 个点">
                只在低配机器上关。
              </span>
              <span v-if="!form.queryUnderstandingEnabled" class="warn-text">
                查询理解关掉时这一项不起作用
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
              把召回的候选段按相关性重新排一遍，最相关的那几段才会进上下文。
              <span class="muted" title="实测：单条约 4.74 秒（窗口 10）；关掉省下这些时间，引用准确率降约 5 个点">
                会明显变慢，但引用更准。
              </span>
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
              有多少候选段参与重排（1–50）。调大能提高"找到"的概率，但耗时几乎线性上涨
              —— <span class="muted" title="实测：窗口 10→20，命中率 +5 个点、引用准确率不变，单条耗时 4.74 秒 → 约 9.5 秒">10 条约 4.7 秒，20 条约 9.5 秒</span>。
              它决定"能不能捞到"，不决定"排第一的是不是对的"。
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
              <b>0 = 整个窗口一次算完</b>，默认 5。一次送进去的段越多越快，但显存占用也越高；
              这一项与上面的「窗口」是两件事：窗口决定看多少条，这里决定一次算几条。
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
              让模型再读一遍候选段，挑出最能回答的那几条。
              <span class="muted" title="实测：引用准确率 +5 个点，但单条多 3.12 秒">是「质量换延迟」里最贵的一档</span>。
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
        <span v-if="!canEdit" class="micro muted">
          只读：需要「检索引擎调参」权限（platform:retrieval:manage）
        </span>
        <span v-else-if="dirty" class="micro muted">有未保存的改动</span>
        <span v-else class="micro muted">已保存</span>
        <div class="foot-actions">
          <el-button :disabled="!dirty || saving" @click="reset">撤销</el-button>
          <el-button type="primary" :loading="saving" :disabled="!canEdit || !dirty" @click="save">
            保存
          </el-button>
        </div>
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
/* ★ 表单栅格（`.form-grid` / `.field-label` / `.field-note`）、`.card-title`、
   `.meta-line`、`.page-foot` 都在 `src/styles/index.css` —— 本页不再自带副本。
   曾经把这些类写进 chat 页的 scoped 里，结果本页拿到的是空类：字段挤成一列。 */
.warn-text {
  color: var(--color-warn);
}
</style>
