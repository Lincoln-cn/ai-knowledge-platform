<script setup lang="ts">
/**
 * 长期记忆（0.9.0）—— 这一页只管**规则**：记不记、召回多少、什么时候忘。
 *
 * <p>★ 版式对齐「检索引擎」页（2026-11-16 定为配置页标准）：
 * 顶部 meta-line 报当前生效值 → 按功能区各一张 panel（标题 + 一句"这一段在干什么" +
 * form-grid 里的 field：标签 / 控件 / 说明）→ 末尾一条 .page-foot 放保存动作。
 *
 * <p>★ 2026-11-16 拆过一次：原来这一页还有「分层概览」与「记忆清单」两块，
 * 而那是**已经记下来的数据**。配置页里放数据表，读者会分不清自己在改配置还是在看数据；
 * 数据侧搬到「对话与知识 → 记忆管理」，两页在各自的说明里互相指路。
 *
 * <p>★ "设定"的保存权限与对话设置同源（`conversation:settings`）：它改的是全机行为 ——
 * 召回条数与做梦时间一改，所有人下一轮对话的上下文都变了。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import { useSessionStore } from '@/stores/session';
import { PERM } from '@/constants/permissions';
import { ApiError } from '@/api/types';
import { validateCron } from '@/lib/cron';
import {
  getMemorySettings,
  updateMemorySettings,
  type MemorySettings,
} from '@/api/memories';

const session = useSessionStore();
const canEdit = computed(() => session.can(PERM.chatSettings));

const settings = ref<MemorySettings | null>(null);
const draft = ref<MemorySettings | null>(null);
const saving = ref(false);
const loadError = ref<{ reason: string; hint: string } | null>(null);

const dirty = computed(() => {
  if (!settings.value || !draft.value) return false;
  const keys: (keyof MemorySettings)[] = [
    'enabled',
    'minImportance',
    'recallLimit',
    'injectProfile',
    'dreamCron',
    'forgetAfterDays',
  ];
  return keys.some((key) => settings.value?.[key] !== draft.value?.[key]);
});

const updatedAtText = computed(() => {
  const iso = settings.value?.updatedAt;
  if (!iso) return '';
  return new Date(iso).toLocaleString('zh-CN', { hour12: false });
});

/**
 * 「每天固定时刻」这一种 cron 的形状：`0 分 时 * * *`。
 *
 * <p>★ 为什么默认给时间选择器而不是直接摊一个六段输入框：做梦是"每天几点跑"，绝大多数部署只会填这一种。
 * 摊一个 `0 40 3 * * *` 让人填，等于要求他先学会 cron 才知道该写什么 —— 而写错了要到保存时才被后端拒绝。
 * 需要别的周期（每 6 小时、只工作日）时再展开手填，那条路保留着。
 */
const DAILY_CRON = /^0\s+(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+\*$/;

const dreamAtDaily = computed(() => DAILY_CRON.test((draft.value?.dreamCron ?? '').trim()));

/** 手动填 cron 的模式；当前值不是「每天固定时刻」时自动进来 */
const advancedCron = ref(false);

/**
 * 六段 cron 的格式检查：合法为 null，否则是给人看的中文（规则见 lib/cron.ts 头条注释）。
 *
 * <p>★ 只在展开手填时算：用时间选择器时那句话必然是 `0 m h * * *`，不用再校验一遍；
 * 而手填时它是**唯一防线** —— 后端 `MemorySettingsView.checked()` 只查非空，不解析 cron。
 */
const cronError = computed(() => (advancedCron.value ? validateCron(draft.value?.dreamCron ?? '', 6) : null));

const dreamTime = computed<string | null>({
  get() {
    const matched = (draft.value?.dreamCron ?? '').trim().match(DAILY_CRON);
    if (!matched) return null;
    const hour = String(Number(matched[2])).padStart(2, '0');
    const minute = String(Number(matched[1])).padStart(2, '0');
    return `${hour}:${minute}`;
  },
  set(value) {
    if (!draft.value || !value) return;
    const [hour, minute] = value.split(':');
    draft.value.dreamCron = `0 ${Number(minute)} ${Number(hour)} * * *`;
    advancedCron.value = false;
  },
});

/** 从 cron 切回时间选择：当前值不是那种形状时，先落成默认的 03:40，避免选择器空着 */
function useTimePicker() {
  if (!draft.value) return;
  if (!dreamAtDaily.value) draft.value.dreamCron = '0 40 3 * * *';
  advancedCron.value = false;
}

watch(
  () => draft.value?.dreamCron,
  (cron) => {
    if (cron && !DAILY_CRON.test(cron.trim())) advancedCron.value = true;
  },
  { immediate: true }
);

function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.hint ? `${error.message}：${error.hint}` : error.message;
  return error instanceof Error ? error.message : '';
}

function reasonOf(error: unknown): { reason: string; hint: string } {
  if (error instanceof ApiError) return { reason: error.message, hint: error.hint ?? '' };
  return { reason: error instanceof Error ? error.message : '读取记忆设定失败', hint: '' };
}

async function load() {
  loadError.value = null;
  try {
    const current = await getMemorySettings();
    settings.value = current;
    draft.value = { ...current };
  } catch (error) {
    loadError.value = reasonOf(error);
  }
}

async function save() {
  if (!draft.value) return;
  // ★ 后端 checked() 只查非空、不解析 cron，所以这里必须拦住 —— 否则一个看不懂的表达式会静默存进库
  const bad = validateCron(draft.value.dreamCron ?? '', 6);
  if (bad) {
    advancedCron.value = true;
    ElMessage.error(`做梦时间：${bad}`);
    return;
  }
  saving.value = true;
  try {
    const next = await updateMemorySettings({
      enabled: draft.value.enabled,
      minImportance: draft.value.minImportance,
      recallLimit: draft.value.recallLimit,
      injectProfile: draft.value.injectProfile,
      dreamCron: draft.value.dreamCron,
      forgetAfterDays: draft.value.forgetAfterDays,
    });
    settings.value = next;
    draft.value = { ...next };
    ElMessage.success('已保存，下一次对话就按新设定走');
  } catch (error) {
    ElMessage.error(errorText(error) || '保存失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}

function reset() {
  if (settings.value) draft.value = { ...settings.value };
}

onMounted(load);
</script>

<template>
  <div class="page settings-page">
    <PageHeader title="长期记忆" desc="记忆规则：记不记、召回多少、什么时候忘，逐条内容另在记忆管理页">
      <template #actions>
        <span class="meta-line micro">
          <span>记忆 <b>{{ draft?.enabled ? '开' : '关' }}</b></span>
          <span>召回 <b>{{ draft?.recallLimit ?? '—' }} 条</b></span>
          <span>做梦 <b>{{ draft?.dreamCron ?? '—' }}</b></span>
          <span>遗忘 <b>{{ draft?.forgetAfterDays ?? '—' }} 天</b></span>
          <span v-if="updatedAtText" class="muted">上次修改 {{ updatedAtText }}</span>
        </span>
      </template>
    </PageHeader>

    <div v-if="loadError" class="notice err">
      <div><b>读取失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" class="mt8" @click="load">重试</el-button>
    </div>

    <template v-else-if="draft">
      <!-- ── ① 开关与召回 ────────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">开关与召回</div>
            <div class="micro muted">记不记、带多少进对话 —— 已经记下的内容在「记忆管理」页</div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">启用长期记忆</label>
            <el-switch v-model="draft.enabled" :disabled="!canEdit" />
            <div class="field-note micro muted">
              关掉之后<b>不再写也不再召回</b>，已有记忆保留不删 —— 想清掉具体某条去「记忆管理」。
            </div>
          </div>

          <div class="field">
            <label class="field-label">召回下限</label>
            <el-input-number
              v-model="draft.minImportance"
              :min="1"
              :max="5"
              :disabled="!canEdit"
              controls-position="right"
              class="field-control"
            />
            <div class="field-note micro muted">
              重要度低于这个值的记忆不带进对话（1–5，默认 3）。调高 = 只带最有把握的那几条。
            </div>
          </div>

          <div class="field">
            <label class="field-label">召回条数</label>
            <el-input-number
              v-model="draft.recallLimit"
              :min="1"
              :max="20"
              :disabled="!canEdit"
              controls-position="right"
              class="field-control"
            />
            <div class="field-note micro muted">
              四层合计的上限（1–20，默认 5）。条数越多，每轮对话的上下文越长、越慢。
            </div>
          </div>

          <div class="field">
            <label class="field-label">注入画像</label>
            <el-switch v-model="draft.injectProfile" :disabled="!canEdit" />
            <div class="field-note micro muted">
              把人格画像<b>整段</b>作为背景，而不是按相似度挑几条 —— 稳但会占掉召回条数的一部分。
            </div>
          </div>
        </div>
      </section>

      <!-- ── ② 做梦与遗忘 ────────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">做梦与遗忘</div>
            <div class="micro muted">夜间维护：去重合并 · 重算画像 · 把久未命中的低价值记忆软删</div>
          </div>
        </div>
        <div class="panel-body form-grid">
          <div class="field">
            <div class="label-row">
              <label class="field-label">做梦时间</label>
              <el-button
                link
                size="small"
                :disabled="!canEdit"
                @click="advancedCron ? useTimePicker() : (advancedCron = true)"
              >
                {{ advancedCron ? '改用时间选择' : '改用 cron 表达式' }}
              </el-button>
            </div>
            <el-time-picker
              v-if="!advancedCron"
              v-model="dreamTime"
              format="HH:mm"
              value-format="HH:mm"
              :clearable="false"
              :disabled="!canEdit"
              placeholder="每天几点跑"
              class="field-control"
            />
            <el-input
              v-else
              v-model="draft.dreamCron"
              :disabled="!canEdit"
              class="field-control"
              placeholder="0 40 3 * * *"
            />
            <div class="field-note micro muted">
              每天几点跑夜间维护（去重合并 · 重算画像 · 软删低价值记忆）。默认 <b>03:40</b>。
              <span class="warn-text">
                这一格现在只记设定：实际执行时刻仍取部署配置 app.memory.dream-cron，改这里不会立刻改调度。
              </span>
              <template v-if="advancedCron">
                现在填的是六段 cron（秒 分 时 日 月 周）。
                <span v-if="cronError" class="warn-text">{{ cronError }}</span>
              </template>
            </div>
          </div>

          <div class="field">
            <label class="field-label">遗忘天数</label>
            <el-input-number
              v-model="draft.forgetAfterDays"
              :min="7"
              :max="3650"
              :disabled="!canEdit"
              controls-position="right"
              class="field-control"
            />
            <div class="field-note micro muted">
              重要度 ≤2 且<b>从未命中</b>的记忆，超过这么多天就软删（7–3650，默认 90）。
              命中过的不会被这条规则挑中。
            </div>
          </div>
        </div>
      </section>

      <div class="page-foot">
        <span v-if="!canEdit" class="micro muted">
          只读：需要「配置通用问答」权限（conversation:settings）
        </span>
        <span v-else-if="dirty" class="micro muted">有未保存的改动</span>
        <span v-else class="micro muted">已保存</span>
        <div class="foot-actions">
          <el-button :disabled="!dirty || saving" @click="reset">撤销</el-button>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="!canEdit || !dirty"
            @click="save"
          >
            保存
          </el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* 版式全部来自全局骨架（styles/index.css 的 .settings-page / .form-grid / .field / .page-foot），
   这里只多一行「标签 + 右侧小按钮」的排布。 */
.label-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
}
</style>
