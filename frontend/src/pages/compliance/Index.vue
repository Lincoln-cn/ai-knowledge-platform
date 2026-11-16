<template>
  <div class="page settings-page">
    <PageHeader title="合规备案" desc="对外登记项：投诉举报的反馈时限、算法备案号与安全评估结论">
      <template #actions>
        <span class="meta-line micro">
          <span>反馈时限 <b>{{ form.complaintFeedbackDays }} 天</b></span>
          <span>备案号 <b>{{ form.algorithmFilingNo.trim() ? '已登记' : '未登记' }}</b></span>
          <span>评估结论 <b>{{ form.securityAssessmentConclusion.trim() ? '已登记' : '未登记' }}</b></span>
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
      <!-- ── ① 投诉举报（第十五条「公布反馈时限」的数字来源）──────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">投诉举报</div>
            <div class="micro muted">受理后多久给出处理结果 —— 这个数字会被公示出去</div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">反馈时限</label>
            <el-input-number
              v-model="form.complaintFeedbackDays"
              :min="1"
              :max="90"
              controls-position="right"
              class="field-control"
            />
            <div class="field-note micro muted">
              会公示在「投诉举报」页，并写进每个工单的「反馈时限」列（1–90 天）。
              缺省 <b>15 天是运营口径，不是法规数字</b> —— 有更严格承诺的组织可以收紧。
            </div>
          </div>
        </div>
      </section>

      <!-- ── ② 算法备案与安全评估（第十七条的登记位，不建申报流程）──────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">算法备案与安全评估</div>
            <div class="micro muted">「关于」对话框会引用这两项；留空就如实显示「未登记」</div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">算法备案号</label>
            <el-input
              v-model="form.algorithmFilingNo"
              maxlength="128"
              show-word-limit
              class="field-control"
              placeholder="例：网信算备 xxxxx 号（留空 = 未登记）"
            />
            <div class="field-note micro muted">
              《生成式人工智能服务管理暂行办法》第十七条。这里只是登记位 ——
              申报、变更、注销走主管部门的线下流程。
            </div>
          </div>

          <div class="field">
            <label class="field-label">安全评估结论</label>
            <el-input
              v-model="form.securityAssessmentConclusion"
              type="textarea"
              :rows="3"
              maxlength="512"
              show-word-limit
              class="field-control"
              placeholder="安全评估的结论摘要（留空 = 未登记）"
            />
            <div class="field-note micro muted">
              ★ 这是登记位不是申报流程：平台侧的流程化支持在「面向境内公众提供服务」之前做实，
              触发条件已写死。
            </div>
          </div>
        </div>
      </section>

      <div class="page-foot">
        <span v-if="loadError" class="micro muted">读取失败，先重试</span>
        <span v-else-if="dirty" class="micro muted">有未保存的改动</span>
        <span v-else class="micro muted">已保存</span>
        <div class="foot-actions">
          <el-button :disabled="!dirty || saving" @click="load">撤销</el-button>
          <el-button type="primary" :loading="saving" :disabled="!dirty || !!loadError" @click="save">
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
import { complianceApi } from '@/api'
import { ApiError } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'

const saving = ref(false)
const loadError = ref<{ reason: string; hint: string } | null>(null)
const updatedAt = ref<string | null>(null)

const form = reactive({
  complaintFeedbackDays: 15,
  algorithmFilingNo: '',
  securityAssessmentConclusion: '',
})

/** 上次从服务端读回/保存成功时的样子；只用来判断"有没有未保存的改动" */
const baseline = ref('')

const snapshot = () =>
  JSON.stringify([form.complaintFeedbackDays, form.algorithmFilingNo, form.securityAssessmentConclusion])

const dirty = computed(() => baseline.value !== '' && snapshot() !== baseline.value)

const updatedAtText = computed(() => {
  if (!updatedAt.value) return ''
  return new Date(updatedAt.value).toLocaleString('zh-CN', { hour12: false })
})

function reasonOf(error: unknown): { reason: string; hint: string } {
  if (error instanceof ApiError) return { reason: error.message, hint: error.hint ?? '' }
  return { reason: error instanceof Error ? error.message : '读取合规备案失败', hint: '' }
}

async function load() {
  loadError.value = null
  try {
    const settings = await complianceApi.getComplianceSettings()
    form.complaintFeedbackDays = settings.complaintFeedbackDays
    form.algorithmFilingNo = settings.algorithmFilingNo ?? ''
    form.securityAssessmentConclusion = settings.securityAssessmentConclusion ?? ''
    updatedAt.value = settings.updatedAt ?? null
    baseline.value = snapshot()
  } catch (e) {
    loadError.value = reasonOf(e)
  }
}

async function save() {
  saving.value = true
  try {
    await complianceApi.updateComplianceSettings({
      complaintFeedbackDays: form.complaintFeedbackDays,
      algorithmFilingNo: form.algorithmFilingNo,
      securityAssessmentConclusion: form.securityAssessmentConclusion,
    })
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败，请重试')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 版式全部来自全局骨架（styles/index.css 的 .settings-page / .form-grid / .field / .page-foot）。 */
</style>
