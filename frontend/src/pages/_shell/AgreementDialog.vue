<template>
  <el-dialog
    v-model="visible"
    title="服务协议与适用人群"
    width="560px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
  >
    <p class="agree-intro">
      欢迎使用本平台。使用前请阅读并同意以下服务协议与适用人群说明（协议版本 {{ AGREEMENT_VERSION }}）。
    </p>
    <div class="agree-scroll">
      <div v-for="section in AGREEMENT_SECTIONS" :key="section.title" class="agree-block">
        <p class="agree-block-title">{{ section.title }}</p>
        <p v-for="(paragraph, index) in section.paragraphs" :key="index" class="agree-paragraph">
          {{ paragraph }}
        </p>
      </div>
    </div>
    <template #footer>
      <el-button @click="decline">不同意并退出</el-button>
      <el-button type="primary" :loading="submitting" @click="accept">同意并继续</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
// ★ #83 第九条/第十条：首次使用可见的服务协议与适用人群确认。
//   同意留痕（版本号+时刻）落库（platform_agreement_consent）—— 弹窗本身没法回填历史，
//   「用户见过哪一版协议」要能举证就得落库。★ 同意留痕是实现选择，非法条规定的留痕形式。
//   协议文本更新 ⇒ bump AGREEMENT_VERSION ⇒ 未对新版本同意过的用户会被重新要求确认。
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { complianceApi } from '@/api'
import { AGREEMENT_SECTIONS, AGREEMENT_VERSION } from '@/constants/agreement'
import { useSessionStore } from '@/stores/session'

const visible = ref(false)
const submitting = ref(false)
const router = useRouter()
const session = useSessionStore()

const open = () => {
  visible.value = true
}

const accept = async () => {
  submitting.value = true
  try {
    await complianceApi.consentAgreement({ version: AGREEMENT_VERSION })
    visible.value = false
    ElMessage.success('已记录协议同意')
  } finally {
    submitting.value = false
  }
}

const decline = async () => {
  await session.logout()
  ElMessage.info('未同意服务协议，已退出登录')
  router.push('/login')
}

defineExpose({ open })
</script>

<style scoped>
.agree-intro {
  margin: 0 0 var(--space-sm);
  font-size: var(--text-sm);
  line-height: var(--leading-ui);
  color: var(--color-ink-2);
}

.agree-scroll {
  max-height: 320px;
  overflow-y: auto;
  padding-right: var(--space-xs);
}

.agree-block {
  margin-bottom: var(--space-sm);
}

.agree-block-title {
  margin: 0 0 2px;
  font-family: var(--font-display);
  font-size: var(--text-sm);
  font-weight: 600;
  letter-spacing: 0;
  color: var(--color-ink);
}

.agree-paragraph {
  margin: 0 0 var(--space-xs);
  font-size: var(--text-sm);
  line-height: var(--leading-ui);
  color: var(--color-ink-2);
}
</style>
