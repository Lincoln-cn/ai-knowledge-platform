<template>
  <div class="page">
    <PageHeader title="合规设置" desc="合规要件的平台登记项。改这里的值会同步到「投诉举报」页的公示内容。" />

    <!-- 面板 1（能力 1）：投诉举报反馈时限（第十五条「公布反馈时限」的数字来源） -->
    <div class="panel">
      <div class="panel-title">投诉举报</div>
      <p class="desc">
        反馈时限：投诉举报受理后 <b class="num">{{ form.complaintFeedbackDays }}</b> 天内反馈处理结果。
        这个数字会公示在「投诉举报」页，并写进每个工单的「反馈时限」列。
      </p>
      <div class="row">
        <el-input-number
          v-model="form.complaintFeedbackDays"
          :min="1"
          :max="90"
          size="small"
          controls-position="right"
        />
        <span class="micro muted">天（1-90）</span>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
      <p class="hint micro muted">
        缺省 15 天是运营口径，不是法规数字；对投诉举报有更严格承诺的组织可以在本页收紧。
      </p>
    </div>

    <!-- 面板 2（能力 3）：算法备案与安全评估登记位（第十七条「留缝」——登记可查，不建申报/变更/注销流程） -->
    <div class="panel">
      <div class="panel-title">算法备案与安全评估</div>
      <p class="desc">
        登记本平台的算法备案号与安全评估结论，「关于」对话框会引用这两项（《生成式人工智能服务管理暂行办法》第十七条）。
        留空表示未登记，「关于」里会如实显示「未登记」。
      </p>
      <div class="field">
        <span class="micro muted">算法备案号</span>
        <el-input
          v-model="form.algorithmFilingNo"
          size="small"
          maxlength="128"
          placeholder="例：网信算备 xxxxx 号（留空 = 未登记）"
        />
      </div>
      <div class="field">
        <span class="micro muted">安全评估结论</span>
        <el-input
          v-model="form.securityAssessmentConclusion"
          size="small"
          maxlength="512"
          placeholder="安全评估的结论摘要（留空 = 未登记）"
        />
      </div>
      <div class="row">
        <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
      <p class="hint micro muted">
        ★ 这是登记位不是申报流程：申报、变更、注销属于面向主管部门的线下流程，平台侧的流程化支持在「面向境内公众提供服务」之前做实（触发条件已写死）。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { complianceApi } from '@/api'
import { ApiError } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'

const saving = ref(false)
const form = reactive({
  complaintFeedbackDays: 15,
  algorithmFilingNo: '',
  securityAssessmentConclusion: '',
})

async function load() {
  try {
    const settings = await complianceApi.getComplianceSettings()
    form.complaintFeedbackDays = settings.complaintFeedbackDays
    form.algorithmFilingNo = settings.algorithmFilingNo ?? ''
    form.securityAssessmentConclusion = settings.securityAssessmentConclusion ?? ''
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '读取合规设置失败')
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
    load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败，请重试')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
}

.panel {
  padding: var(--space-sm) var(--space-md);
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
}

.panel-title {
  font-family: var(--font-display);
  font-size: var(--text-lg);
  line-height: var(--leading-display);
  color: var(--color-ink);
}

.desc {
  margin: var(--space-xs) 0;
  color: var(--color-ink-2);
  line-height: var(--leading-body);
  font-size: var(--text-xs);
}

.row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
  margin: var(--space-xs) 0;
}

.hint {
  margin-top: var(--space-3xs);
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.micro {
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.muted {
  color: var(--color-muted);
}

.num {
  font-family: var(--font-display);
}
</style>
