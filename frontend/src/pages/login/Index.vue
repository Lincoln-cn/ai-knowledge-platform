<template>
  <div class="login">
    <aside class="slate">
      <!-- 叠层英雄构图：一片有机色块衬在文字后。装饰件，不进无障碍树。 -->
      <span class="slate-art" aria-hidden="true"></span>
      <span class="slate-grain" aria-hidden="true"></span>

      <div class="slate-brand">
        <!-- 同一个字标，深色石墨面上给浅色框（design.md § Brand） -->
        <BrandMark class="mark" :size="26" />
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
import BrandMark from '@/components/BrandMark.vue'
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
  /* 色块的定位上下文 + 合成边界：screen 只与本栏石墨底算，
     并且溢出的部分被裁在本栏内，不许越进表单那侧的纸面。 */
  position: relative;
  isolation: isolate;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: var(--space-xl) var(--space-xl) var(--space-lg);
  background: var(--color-graphite);
  color: var(--color-graphite-ink);
}

/* ── 叠层英雄构图（imagery-kit 的 masterclass move）────────────────────
   一片有机色块衬在文字后：**大**、**偏右下**、z-index 0，文字在 1 档。

   ★ 偏右下、**不居中** —— "居中衬在文字后"是 imagery-kit.md:145 与
     design.md § Art layer 同时点名的 AI 默认，这一条不许打折。
   ★ 深色石墨底上只能用 screen。本轮**实测**：同一片色块换 multiply 后
     石墨底（oklch 22%）与色块相乘 ≈ 全黑，肉眼完全看不见 ——
     imagery-kit.md:146「multiply over a dark canvas inverts」说的就是它。
   ★ 不透明度取硬上限 --art-opacity-screen（0.35），不再往上一格。

   width 用 clamp(60%, 80vh, 1200px)：占比下限跟随本栏、上限跟随视口高度，
   所以宽视口下色块的**相对**构图基本稳定（实测 1440×900 为 1.19 栏宽、
   1920×1080 与 2560×1440 为 1.07、1280×1024 为 1.52）。
   四个窄宽度（320/375/414/768）走下面那条 media query 的落点。 */
.slate-art {
  position: absolute;
  right: -34%;
  bottom: -18%;
  width: clamp(60%, 80vh, 1200px);
  aspect-ratio: 1 / 1;
  z-index: 0;
  pointer-events: none;
  background-image: var(--art-blob);
  background-repeat: no-repeat;
  background-position: center;
  background-size: contain;
  mix-blend-mode: screen;
  opacity: var(--art-opacity-screen);
}

/* 本栏的颗粒，一片，硬上限 --art-opacity-grain（0.15）。
   深底上同样走 screen：素材本身是冷炭色，multiply 在石墨上留不下任何东西。 */
.slate-grain {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-image: var(--art-grain);
  background-repeat: repeat;
  background-size: 128px 128px;
  mix-blend-mode: screen;
  opacity: var(--art-opacity-grain);
}

/* 文字一律压在色块之上 */
.slate-brand,
.slate-body,
.slate-foot {
  position: relative;
  z-index: 1;
}

.slate-brand {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

/* 字标是 BrandMark 组件：这里只负责给它一个浅色 currentColor。
   不要再写字族/字号 —— 那是上一版「知」字占位符的残留。 */
.mark {
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

  /* 窄屏下左栏变成顶部一条 band，原来的"右下角"就是文字区 ——
     色块缩到 ≈ 0.78 视口宽，核心挪到 band **右上角**的空区
     （品牌字在左、标题与标语在下，右上角没有内容）。 */
  .slate-art {
    right: -26%;
    top: -50%;
    bottom: auto;
    width: min(78vw, 460px);
  }

  .slate-title {
    font-size: var(--text-2xl);
  }

  .pane {
    padding: var(--space-lg);
  }
}
</style>
