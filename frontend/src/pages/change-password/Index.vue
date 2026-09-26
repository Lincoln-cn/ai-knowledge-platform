<template>
  <div class="page">
    <PageHeader
      title="修改密码"
      desc="定期更换密码有助于账号安全。管理员新建账号的初始密码为 123456，首次登录后请立即修改"
    />

    <div v-if="forced" class="notice warn">
      你的账号仍在使用初始密码，必须先修改密码才能继续使用平台功能。
    </div>

    <div class="form-wrap panel">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
        class="pwd-form"
        @submit.prevent
      >
        <el-form-item label="原密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            placeholder="请输入当前密码"
            autocomplete="current-password"
          />
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            show-password
            placeholder="至少 8 位"
            autocomplete="new-password"
          />
        </el-form-item>

        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            placeholder="再次输入新密码"
            autocomplete="new-password"
            @keydown.enter="submit"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submit">确认修改</el-button>
          <el-button v-if="!forced" @click="router.back()">返回</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { identityApi } from '@/api'
import { ApiError } from '@/api/types'
import { useSessionStore } from '@/stores/session'

const router = useRouter()
const session = useSessionStore()

const formRef = ref<FormInstance>()
const saving = ref(false)

/** 首次登录强制改密（LoginResult.mustChangePassword）—— 此时不给「返回」出口 */
const forced = computed(() => session.mustChangePassword)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '新密码至少 8 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await identityApi.changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword
    })
    session.clearMustChangePassword()
    ElMessage.success('密码已修改')
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    formRef.value?.clearValidate()
    // 强制改密完成后才放行进业务页
    router.push('/')
  } catch (error) {
    // 失败原因直接来自接口（如「原密码不正确」），界面不自己编话术
    ElMessage.error(error instanceof ApiError ? error.message : '修改失败，请稍后重试')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-wrap {
  max-width: 520px;
  padding: var(--space-lg);
}

.pwd-form {
  line-height: var(--leading-ui);
}
</style>
