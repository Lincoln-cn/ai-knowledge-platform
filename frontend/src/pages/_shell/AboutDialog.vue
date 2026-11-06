<template>
  <el-dialog v-model="visible" title="关于本平台" width="560px" :close-on-click-modal="true">
    <div class="about">
      <h3 class="about-app">{{ APP_TITLE }}</h3>
      <p class="about-line">合规基线 {{ AGREEMENT_VERSION }}</p>

      <section class="about-section">
        <h4>服务协议与适用人群</h4>
        <div class="about-scroll">
          <div v-for="section in AGREEMENT_SECTIONS" :key="section.title" class="about-block">
            <p class="about-block-title">{{ section.title }}</p>
            <p v-for="(paragraph, index) in section.paragraphs" :key="index" class="about-paragraph">
              {{ paragraph }}
            </p>
          </div>
        </div>
      </section>

      <section class="about-section">
        <h4>投诉与举报</h4>
        <p class="about-paragraph">
          处理流程：受理 → 办理 → 反馈。反馈时限：{{ feedbackDays }} 天（以投诉举报页面公示为准）。
        </p>
        <el-button size="small" @click="goComplaints">前往投诉举报</el-button>
      </section>

      <section class="about-section">
        <h4>算法备案与安全评估</h4>
        <p class="about-paragraph">
          算法备案号：{{ settings?.algorithmFilingNo || '未登记' }}
        </p>
        <p class="about-paragraph">
          安全评估结论：{{ settings?.securityAssessmentConclusion || '未登记' }}
        </p>
        <p class="about-note">
          以上由组织管理员在「管理 → 合规设置」登记（《生成式人工智能服务管理暂行办法》第十七条）。
        </p>
      </section>

      <section class="about-section">
        <h4>生成内容标识</h4>
        <p class="about-paragraph">
          本版本不产生图片、视频等视觉生成内容，因此不存在需要标识的生成内容。将来如提供视觉内容生成功能，将按《互联网信息服务深度合成管理规定》对生成内容添加显著标识。
        </p>
      </section>
    </div>
    <template #footer>
      <el-button type="primary" @click="visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
// ★ #83 第九条/第十条/第十五条/第十七条的「关于」落点：
//   服务协议与适用人群 / 投诉举报入口与反馈时限 / 算法备案号引用 / 深度合成标识声明。
//   备案号与安全评估结论读合规设置（未登记时明示「未登记」，不留空白误导）。
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { APP_TITLE } from '@/config/env'
import { complianceApi } from '@/api'
import type { ComplianceSettings } from '@/api/compliance'
import { AGREEMENT_SECTIONS, AGREEMENT_VERSION } from '@/constants/agreement'

const visible = ref(false)
const settings = ref<ComplianceSettings | null>(null)
const router = useRouter()

const feedbackDays = computed(() => settings.value?.complaintFeedbackDays ?? 15)

const open = () => {
  visible.value = true
  complianceApi
    .getComplianceSettings()
    .then((result) => {
      settings.value = result
    })
    .catch(() => {
      settings.value = null
    })
}

const goComplaints = () => {
  visible.value = false
  router.push('/complaints')
}

defineExpose({ open })
</script>

<style scoped>
.about {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.about-app {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-lg);
  letter-spacing: 0;
  color: var(--color-text-1);
}

.about-line {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-3);
}

.about-section h4 {
  margin: 0 0 var(--space-xs);
  font-family: var(--font-display);
  font-size: var(--text-sm);
  letter-spacing: 0;
  color: var(--color-text-1);
}

.about-scroll {
  max-height: 220px;
  overflow-y: auto;
  padding-right: var(--space-xs);
}

.about-block {
  margin-bottom: var(--space-sm);
}

.about-block-title {
  margin: 0 0 2px;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-text-1);
}

.about-paragraph {
  margin: 0 0 var(--space-xs);
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
  color: var(--color-text-2);
}

.about-note {
  margin: 0;
  font-size: var(--text-xs);
  line-height: var(--leading-normal);
  color: var(--color-text-3);
}
</style>
