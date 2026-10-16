<template>
  <div class="login">
    <aside class="slate">
      <!-- 底光：一层**重模糊、完全静止**的冷色场。它只负责"这里有光"，
           不再承担任何动效 —— 使用者对"会动的云"的判词是「只是一个云 太丑了」。 -->
      <span class="slate-halo" aria-hidden="true"></span>

      <!-- 自绘拓扑（★ 第三轮第四次改）：GSAP 的 DrawSVG 让这张图画出来 ——
           线生长 → 节点在"第一条边画到"时亮起 → 钴蓝信号沿边扫过、终点打点 →
           溶解 → **重新洗牌**再来一轮（所以永不重复）。
           ★ 为什么换成它：素材不再是位图而是**线条本身**，"知识图谱"这个隐喻
             与"工程图纸 + 发丝线"这套视觉语言同时对得上。
           见 src/pages/login/useSlateTopology.ts。装饰件，不进无障碍树。 -->
      <svg
        ref="topologyRef"
        class="slate-topology"
        viewBox="0 0 672 900"
        preserveAspectRatio="xMidYMid slice"
        aria-hidden="true"
      >
        <!-- ★ 这一层只为"整组色相漂移"而存在：一次 `hue-rotate` 作用在全组上，
             比给 127 个元素逐个补间颜色便宜得多（见 useSlateTopology.ts）。 -->
        <g class="topology-field">
          <g>
            <path v-for="(d, i) in topologyEdges" :key="`w${i}`" class="wire" :d="d" />
          </g>
          <g>
            <path v-for="(d, i) in topologyEdges" :key="`p${i}`" class="pulse" :d="d" />
          </g>
          <g>
            <g v-for="(node, i) in topologyNodes" :key="`n${i}`" class="node">
              <rect :x="node[0] - 3" :y="node[1] - 3" width="7" height="7" />
            </g>
          </g>
        </g>
      </svg>
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
      <!-- ★ 第三轮：登录页的入场从"整列一起淡入"（`.rise`）改成**错峰入场**（`.enter`）——
           顺序按人读页面的顺序来：标题 → 表单 → 按钮 → 说明。
           预算：本页用了两个原语（错峰入场 + 按下），浮层/读数/导航都不适用。
           ⚠️ 深度上不叠 `.rise`：两个入场动画叠在同一个容器上会互相打架。 -->
      <div class="form-col enter">
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
            />
          </el-form-item>
          <el-form-item v-if="captcha.required" label="验证码" prop="captchaCode">
            <div class="captcha-row">
              <el-input
                v-model="form.captchaCode"
                placeholder="请输入图中字符"
                autocomplete="off"
                maxlength="6"
                @keydown.enter="submit"
              />
              <button
                type="button"
                class="captcha-pic"
                :disabled="captcha.loading"
                :title="captcha.loading ? '正在获取' : '看不清？点击换一张'"
                @click="refreshCaptcha"
              >
                <img v-if="captcha.image" :src="captcha.image" alt="验证码，点击可换一张" />
                <span v-else class="captcha-pic-fallback">加载中</span>
              </button>
            </div>
          </el-form-item>
          <!-- ★ 开关关掉时不再渲染一个「看着在要、实际不认」的框 —— 那种状态会把
               「关掉验证码」伪装成产品 bug。改成一句轻量说明，让「关掉」本身可见。 -->
          <p v-else class="micro muted captcha-off-note">当前未启用验证码校验（自动化/压测模式）</p>
        </el-form>

        <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon />

        <el-button type="primary" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>

        <div class="notice info">
          接口已固定、{{ dataModeText }}。可用账号：<code>admin / admin</code>、
          <code>zhangyun / 123456</code>（知识运营）、<code>liwei / 123456</code>（应用配置）、
          <code>chenjun / 123456</code>（普通用户）。后两个角色权限不同，可观察按钮是否渲染。
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import BrandMark from '@/components/BrandMark.vue'
import { ApiError, identityApi } from '@/api'
import { APP_TITLE, USE_MOCK } from '@/config/env'
import { useSessionStore } from '@/stores/session'
import {
  TOPOLOGY_EDGES,
  TOPOLOGY_NODES,
  edgePath,
  useSlateTopology
} from './useSlateTopology'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

/* ── 左栏的自绘拓扑（★ 第三轮第四次改）────────────────────────────────
   动效交给 GSAP（DrawSVGPlugin），编排与"为什么这么设计"都在 useSlateTopology.ts 里。
   节点与边是**数据**，路径 `d` 由 edgePath() 算出来 —— 模板里不写死坐标。

   ★ 这里刻意**没有**任何 CSS 动画：GSAP 走 rAF + 内联样式，**不受 CSS 的
     `prefers-reduced-motion` 约束**，所以降动效必须在 JS 里处理（motionAllowed()）——
     两边都写就成了"两个主人"，其中一边会静默失效（本轮踩过同类坑）。 */
const topologyNodes = TOPOLOGY_NODES
const topologyEdges = TOPOLOGY_EDGES.map(([a, b]) => edgePath(TOPOLOGY_NODES[a], TOPOLOGY_NODES[b]))
const topologyRef = ref<SVGSVGElement | null>(null)
useSlateTopology(topologyRef)

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorText = ref('')

const form = reactive({ username: 'admin', password: 'admin', captchaCode: '' })

/**
 * 底部说明里的「当前走哪条链路」必须**跟着环境变**。
 *
 * ★ 之前写死「当前走 mock」：`VITE_USE_MOCK=false` 真联调时界面在说谎 ——
 *   排查「怎么还在走 mock」会白白绕一圈。文案与 USE_MOCK 同源，
 *   切 .env 即换口径，不留第二处会漂移的说法。
 */
const dataModeText = USE_MOCK ? '当前走 mock' : '当前连的是真后端'

/**
 * 验证码（等保要求）。
 *
 * ★ 它是**一次性**的：服务端无论校验成功还是失败都会把它作废，
 * 所以任何一次登录失败之后都必须换一张 —— 否则用户重试时提交的是一张已作废的验证码，
 * 界面会一直说"验证码不正确"，而用户明明照着图输对了。这是最容易漏掉的一处。
 *
 * ★ `required` 默认 true：契约里是可选字段，旧后端/mock 缺字段时不能让验证码凭空消失。
 *   后端下发 `required: false`（`app.security.captcha.enabled=false`）时才收起输入框 ——
 *   否则会出现「界面在要验证码、后端不认」的误导形态（本次 bug 的真因形态）。
 */
const captcha = reactive({ id: '', image: '', loading: false, required: true })

const refreshCaptcha = async () => {
  captcha.loading = true
  form.captchaCode = ''
  try {
    const fresh = await identityApi.getCaptcha()
    captcha.id = fresh.id
    captcha.image = fresh.image
    captcha.required = fresh.required !== false
  } catch {
    // 取不到图不该拦住登录动作本身（后端可能把验证码关掉了），
    // 让它降级成"没有验证码"的形态，而不是在页面上报一个取图错误
    captcha.id = ''
    captcha.image = ''
  } finally {
    captcha.loading = false
  }
}

onMounted(refreshCaptcha)

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
  // ★ 验证码**不做前端必填校验**：是否强制由后端的开关决定（等保环境开启）。
  //   把它写死成必填，就等于把这个开关废掉了
}

const submit = async () => {
  errorText.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const result = await session.login(
      form.username,
      form.password,
      captcha.id,
      form.captchaCode
    )
    if (result.mustChangePassword) {
      // 安全闭环：初始密码必须先改掉，不放行进业务页（路由守卫也会拦）
      ElMessage.warning('首次登录需修改密码')
      router.push('/change-password')
      return
    }
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (error) {
    // 失败原因直接来自接口，界面不自己编话术
    errorText.value =
      error instanceof ApiError ? error.message : '登录失败，请稍后重试'
    await refreshCaptcha()
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
/* 底光：一层重模糊、**完全静止**的冷色场。它只负责"这里有光"，不再承担动效。
   ★ 静止是刻意的：使用者对"会动的云"的判词是「只是一个云 太丑了」——
     动的东西全部交给下面的拓扑线，屏幕上只有**一处**在动。 */
.slate-halo {
  position: absolute;
  /* ★ 铺满整栏（使用者：「动画范围可以是那个墨蓝色背景的全部吧，现在范围太小」）：
     光场放大到覆盖整块墨蓝底，最亮仍在右下 —— 否则"整栏在动、光只在一角"会脱节。 */
  right: -20%;
  bottom: -30%;
  width: clamp(90%, 122vh, 1700px);
  aspect-ratio: 1 / 1;
  z-index: 0;
  pointer-events: none;
  background-image: var(--art-blob);
  background-repeat: no-repeat;
  background-position: center;
  background-size: contain;
  mix-blend-mode: screen;
  filter: blur(34px);
  opacity: calc(var(--art-opacity-screen) * 0.8);
}

/* ── 自绘拓扑（线索） ───────────────────────────────────────────────────
   发丝线走 `--color-graphite-rule`（石墨底上的细线），信号走 accent（钴蓝）。
   ★ `vector-effect: non-scaling-stroke`：让 1px 在任何 viewBox 缩放下都是 1px。
     发丝线一旦被缩放成 0.7px 或 1.4px，"利"的感觉就没了 —— 这是这套语言的命门。
   ★ accent 用量：信号只是 1.4px 宽的一小段线，占视口面积远低于 5% 的上限。
   ★ 这里**没有任何 CSS 动画**：生长/扫过/溶解全部由 GSAP 驱动（见 useSlateTopology.ts），
     因为 drawSVG 动的是 stroke-dashoffset，是 JS 侧的编排而不是 CSS 关键帧。 */
.slate-topology {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.wire {
  fill: none;
  /* ★ 颜色：由 JS 每轮从 `--art-wire-1..5`（蓝族五档，定义在 tokens.css）**随机分配**，
     并随整组做缓慢色相漂移。这里的 `var(--art-wire-3)` 只是 JS 未跑那一瞬的兜底。
     为什么不给"看得见/不抢戏"再手调一个灰：使用者要求"不要纯白、要动态颜色" ——
     有颜色的线本来就不需要靠灰度去压存在感，色相与明度共同承担了这件事。 */
  stroke: var(--wire-tone, var(--art-wire-3));
  stroke-width: 1;
  vector-effect: non-scaling-stroke;
}

.pulse {
  fill: none;
  stroke: var(--color-accent);
  stroke-width: 1.4;
  stroke-linecap: round;
  opacity: 0;
  vector-effect: non-scaling-stroke;
}

.node rect {
  /* ★ 改成**实心**方点，颜色每轮随机取（枢纽节点固定取 accent）。
     两个理由：① 本系统"状态方点"的语言本来就是实心 5×5 方块，空心框读起来像复选框；
     ② 空心框加彩色描边在 1px 下几乎看不出颜色，实心才真的"有颜色"。 */
  fill: var(--node-tone, var(--art-wire-2));
  stroke: none;
}


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

/* 验证码：输入框 + 可点的图。图是 120×40，与后端渲染出的比例一致。
   不设 border，只用纸面底色 —— 外观交给主题变量，避免引入一个本页没出现过的颜色变量 */
.captcha-row {
  display: flex;
  align-items: stretch;
  gap: var(--space-xs);
  width: 100%;
}

.captcha-pic {
  flex: 0 0 auto;
  width: 120px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: var(--radius-xs);
  background: var(--color-surface);
  cursor: pointer;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.captcha-pic:disabled {
  cursor: progress;
}

.captcha-pic img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
}

.captcha-pic-fallback {
  font-size: var(--text-sm);
}

/* 验证码关闭时的轻量说明：不抢视觉，只让「关掉」这件事看得见 */
.captcha-off-note {
  margin: 0;
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
  /* 窄屏下左栏变成顶部一条 band：底光挪到右上角的空区，
     拓扑整体压淡（它会被 band 的高度裁掉一部分，所以只当纹理用，不抢品牌字与标题）。 */
  .slate-halo {
    right: -18%;
    top: -46%;
    bottom: auto;
    width: min(86vw, 500px);
  }

  .slate-topology {
    opacity: 0.55;
  }

  .slate-title {
    font-size: var(--text-2xl);
  }

  .pane {
    padding: var(--space-lg);
  }
}
</style>
