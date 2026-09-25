<template>
  <div class="login">
    <aside class="slate">
      <div class="slate-brand">
        <span class="mark">知</span>
        <span class="mark-rule" aria-hidden="true"></span>
        <span class="mark-name">{{ APP_TITLE }}</span>
      </div>

      <div class="slate-body">
        <h1 class="slate-title">{{ APP_TITLE }}</h1>
        <div class="slate-rule" aria-hidden="true"></div>
        <p class="slate-lead">知识检索 · 应用编排 · 权限可控</p>
      </div>

      <div class="slate-foot">
        <span class="label">Knowledge Assistant</span>
      </div>
    </aside>

    <main class="pane">
      <div class="form-col rise">
        <div class="form-head">
          <h2 class="h2">登录</h2>
          <p class="micro muted">账号密码登录，进入知识助理平台</p>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
          <el-form-item label="账号" prop="username">
            <el-input v-model="form.username" placeholder="请输入账号" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
              @keydown.enter="submit"
            />
          </el-form-item>
        </el-form>

        <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon />

        <el-button type="primary" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>

        <div class="notice info">
          接口已固定、当前走 mock。可用账号：<code>admin / admin</code>、
          <code>zhangyun / 123456</code>（知识运营）、<code>liwei / 123456</code>（应用配置）、
          <code>chenjun / 123456</code>（普通用户）。后两个角色权限不同，可观察按钮是否渲染。
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '@/api'
import { APP_TITLE } from '@/config/env'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorText = ref('')

const form = reactive({ username: 'admin', password: 'admin' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const submit = async () => {
  errorText.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const result = await session.login(form.username, form.password)
    if (result.mustChangePassword) {
      ElMessage.warning('首次登录需修改密码')
    }
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (error) {
    // 失败原因直接来自接口，界面不自己编话术
    errorText.value =
      error instanceof ApiError ? error.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 沉浸页：左深色石墨拍子 + 右表单。一页只用这一次拍子。 */
.login {
  height: 100%;
  display: flex;
  background: var(--color-paper);
}

.slate {
  flex: 0 0 42%;
  min-width: 280px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: var(--space-xl) var(--space-xl) var(--space-lg);
  background: var(--color-graphite);
  color: var(--color-graphite-ink);
}

.slate-brand {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.mark {
  font-family: var(--font-display);
  font-size: var(--text-xl);
  font-weight: 600;
  line-height: 1;
  letter-spacing: var(--tracking-body);
  color: var(--color-graphite-ink);
}

.mark-rule {
  width: 1px;
  height: var(--space-lg);
  background: var(--color-graphite-rule);
}

.mark-name {
  font-family: var(--font-body);
  font-size: var(--text-sm);
  font-weight: 600;
  letter-spacing: var(--tracking-body);
  color: var(--color-graphite-ink);
}

.slate-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

/* 仅登录页允许 --text-3xl */
.slate-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-3xl);
  font-weight: 600;
  line-height: var(--leading-display);
  letter-spacing: var(--tracking-body);
  color: var(--color-graphite-ink);
}

.slate-rule {
  width: var(--space-xl);
  height: 1px;
  background: var(--color-graphite-rule);
}

.slate-lead {
  margin: 0;
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-graphite-muted);
}

.slate-foot .label {
  color: var(--color-graphite-muted);
}

/* 右栏：表单直接落在纸面上，不套居中圆角卡片。
   ⚠️ 这里用 center 而不是 flex-start —— 试过 flex-start，实测右栏 941px 而表单只有
   380px，硬靠左会在右侧留下 ~490px 死区，整个构图重心偏到左半、看着像没做完。
   这是**截图才看得出来**的缺陷：几何断言只算"元素有没有越界"，不算"负空间均不均"。
   注意：把"一个表单列"在自己面板里居中，与"整个封面居中排版"是两回事 ——
   后者才是被点名的模板感。 */
.pane {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-xl) var(--space-2xl);
  overflow-y: auto;
}

.form-col {
  width: 100%;
  max-width: 380px;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.form-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
  margin-bottom: var(--space-2xs);
}

.form-head .h2 {
  margin: 0;
}

.submit {
  width: 100%;
}

code {
  background: var(--color-surface);
  padding: 0 var(--space-2xs);
  border-radius: var(--radius-xs);
}

@media (max-width: 860px) {
  .login {
    flex-direction: column;
  }

  .slate {
    flex: 0 0 auto;
    min-width: 0;
    padding: var(--space-lg);
    gap: var(--space-lg);
  }

  .slate-title {
    font-size: var(--text-2xl);
  }

  .pane {
    padding: var(--space-lg);
  }
}
</style>
