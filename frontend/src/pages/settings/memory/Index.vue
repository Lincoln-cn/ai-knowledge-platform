<script setup lang="ts">
/**
 * 长期记忆（0.9.0）。
 *
 * <p>三块：分层概览（记忆长在哪一层 + 当前画像）、记忆清单（可增删改）、设定（什么时候记、记多少、什么时候忘）。
 *
 * <p>★ "设定"的保存权限与对话设置同源（`conversation:settings`）：它改的是全机行为 ——
 * 召回条数与做梦时间一改，所有人下一轮对话的上下文都变了。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import { useSessionStore } from '@/stores/session';
import { PERM } from '@/constants/permissions';
import { ApiError } from '@/api/types';
import {
  createMemory,
  deleteMemory,
  getMemoryProfile,
  getMemorySettings,
  listMemories,
  memoryCounts,
  updateMemory,
  updateMemorySettings,
  type MemoryItem,
  type MemoryKind,
  type MemoryLevel,
  type MemoryLevelCount,
  type MemoryProfile,
  type MemorySettings,
} from '@/api/memories';

const session = useSessionStore();
const canEdit = computed(() => session.can(PERM.chatSettings));

const LEVELS: { value: MemoryLevel; label: string; hint: string }[] = [
  { value: 'user', label: '用户', hint: '关于你本人的长期事实（在做的事、所在单位、角色）' },
  { value: 'conversation', label: '本次对话', hint: '只在本会话内有效，换会话就不带' },
  { value: 'persona', label: '人格画像', hint: '从偏好里提炼出的沟通口径，注入时作为背景' },
  { value: 'cross_session', label: '跨会话', hint: '你在不同会话里反复提到的目标与约束' },
];

const KINDS: { value: MemoryKind; label: string }[] = [
  { value: 'fact', label: '事实' },
  { value: 'preference', label: '偏好' },
  { value: 'constraint', label: '约束' },
  { value: 'goal', label: '目标' },
  { value: 'event', label: '事件' },
  { value: 'summary', label: '摘要' },
];

const items = ref<MemoryItem[]>([]);
const counts = ref<MemoryLevelCount[]>([]);
const profile = ref<MemoryProfile>({});
const settings = ref<MemorySettings | null>(null);
const draft = ref<MemorySettings | null>(null);
const loading = ref(false);
const saving = ref(false);

const creating = ref(false);
const form = ref<{ level: MemoryLevel; kind: MemoryKind; content: string; importance: number }>({
  level: 'user',
  kind: 'fact',
  content: '',
  importance: 3,
});

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

const summaryLine = computed(() => {
  const current = settings.value;
  if (!current) return '加载中…';
  return `${current.enabled ? '记忆开' : '记忆关'} · 召回 ${current.recallLimit} 条 · ${current.dreamCron}`;
});

const levelLabel = (level: MemoryLevel) => LEVELS.find((x) => x.value === level)?.label ?? level;
const kindLabel = (kind: MemoryKind) => KINDS.find((x) => x.value === kind)?.label ?? kind;
const countOf = (level: MemoryLevel) => counts.value.find((x) => x.level === level)?.count ?? 0;

function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.hint ? `${error.message}：${error.hint}` : error.message;
  return error instanceof Error ? error.message : '';
}

async function load() {
  loading.value = true;
  try {
    const [list, levelCounts, currentProfile, currentSettings] = await Promise.all([
      listMemories(),
      memoryCounts(),
      getMemoryProfile(),
      getMemorySettings(),
    ]);
    items.value = list;
    counts.value = levelCounts;
    profile.value = currentProfile;
    settings.value = currentSettings;
    draft.value = { ...currentSettings };
  } catch (error) {
    ElMessage.error(errorText(error) || '加载记忆失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

async function submitCreate() {
  if (!form.value.content.trim()) {
    ElMessage.warning('写点内容再保存');
    return;
  }
  try {
    await createMemory({
      level: form.value.level,
      kind: form.value.kind,
      content: form.value.content.trim(),
      importance: form.value.importance,
    });
    creating.value = false;
    form.value = { level: 'user', kind: 'fact', content: '', importance: 3 };
    ElMessage.success('已记下');
    await load();
  } catch (error) {
    ElMessage.error(errorText(error) || '保存失败，请稍后重试');
  }
}

async function submitEdit(item: MemoryItem) {
  try {
    const { value } = await ElMessageBox.prompt('改完直接生效（下一轮对话就按新的内容注入）', '修改记忆', {
      inputValue: item.content,
      inputType: 'textarea',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    });
    if (value === null || !value.trim()) return;
    await updateMemory(item.id, { content: value.trim(), importance: item.importance });
    ElMessage.success('已更新');
    await load();
  } catch {
    // 取消不提示
  }
}

async function remove(item: MemoryItem) {
  try {
    await ElMessageBox.confirm(`删掉这条之后它不会再被带进对话：\n\n${item.content}`, '删除记忆', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }
  try {
    await deleteMemory(item.id);
    ElMessage.success('已删除');
    await load();
  } catch (error) {
    ElMessage.error(errorText(error) || '删除失败，请稍后重试');
  }
}

async function save() {
  if (!draft.value) return;
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
  <div class="memory-page">
    <PageHeader title="长期记忆" desc="它记住的东西与记忆规则；可逐条查看、改掉或清除">
      <template #actions>
        <span class="meta-line micro">{{ summaryLine }}</span>
      </template>
    </PageHeader>

    <section class="panel">
      <h3 class="panel-title">分层</h3>
      <p class="panel-hint">
        记忆分四层：越靠上越"关于你这个人"，越靠下越"关于这次对话"。召回时四层一起看，
        但会话层只取当前会话 —— 别的会话里发生的事不会混进来。
      </p>
      <div class="level-grid">
        <div v-for="level in LEVELS" :key="level.value" class="level-cell">
          <div class="level-head">
            <span class="level-name">{{ level.label }}</span>
            <span class="level-count">{{ countOf(level.value) }} 条</span>
          </div>
          <p class="level-hint">{{ level.hint }}</p>
        </div>
      </div>
      <div class="profile-row">
        <span class="profile-label">当前画像</span>
        <span v-if="profile.summary" class="profile-text">{{ profile.summary }}</span>
        <span v-else class="profile-text muted">还没有形成画像（攒够偏好之后由夜间任务重算）</span>
      </div>
      <div v-if="settings?.lastDreamAt" class="profile-row">
        <span class="profile-label">上次做梦</span>
        <span class="profile-text muted">
          {{ new Date(settings.lastDreamAt).toLocaleString() }}
          <template v-if="settings.lastDreamNote">· {{ settings.lastDreamNote }}</template>
        </span>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h3 class="panel-title">记忆清单</h3>
        <el-button v-if="canEdit" size="small" @click="creating = !creating">
          {{ creating ? '取消' : '新增一条' }}
        </el-button>
      </div>

      <div v-if="creating" class="create-box">
        <div class="create-row">
          <el-select v-model="form.level" size="small" style="width: 130px">
            <el-option v-for="level in LEVELS" :key="level.value" :label="level.label" :value="level.value" />
          </el-select>
          <el-select v-model="form.kind" size="small" style="width: 120px">
            <el-option v-for="kind in KINDS" :key="kind.value" :label="kind.label" :value="kind.value" />
          </el-select>
          <el-input-number v-model="form.importance" :min="1" :max="5" size="small" />
          <span class="create-hint">重要度 1–5，低于设定里的下限不会被召回</span>
        </div>
        <el-input v-model="form.content" type="textarea" :rows="3" placeholder="例如：我在做合同风险评估，回复时先给结论再给依据。" />
        <div class="create-actions">
          <el-button type="primary" size="small" @click="submitCreate">保存</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="items" size="small" empty-text="还没有记忆">
        <el-table-column label="层级" width="96">
          <template #default="{ row }">{{ levelLabel(row.level) }}</template>
        </el-table-column>
        <el-table-column label="种类" width="82">
          <template #default="{ row }">{{ kindLabel(row.kind) }}</template>
        </el-table-column>
        <el-table-column label="内容" min-width="320">
          <template #default="{ row }">{{ row.content }}</template>
        </el-table-column>
        <el-table-column label="重要度" width="80">
          <template #default="{ row }">{{ row.importance }}</template>
        </el-table-column>
        <el-table-column label="命中" width="72">
          <template #default="{ row }">{{ row.hitCount }}</template>
        </el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="118">
          <template #default="{ row }">
            <el-button link size="small" @click="submitEdit(row)">改</el-button>
            <el-button link size="small" type="danger" @click="remove(row)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section v-if="canEdit && draft" class="panel">
      <h3 class="panel-title">设定</h3>
      <p class="panel-hint">这一节改的是全机行为：召回条数与做梦时间一改，所有人下一轮对话都受影响。</p>

      <div class="setting-row">
        <span class="setting-label">启用长期记忆</span>
        <el-switch v-model="draft.enabled" />
        <span class="setting-hint">关掉之后不再写也不再召回，已有记忆保留</span>
      </div>
      <div class="setting-row">
        <span class="setting-label">召回下限</span>
        <el-input-number v-model="draft.minImportance" :min="1" :max="5" size="small" />
        <span class="setting-hint">重要度低于这个值的记忆不带进对话</span>
      </div>
      <div class="setting-row">
        <span class="setting-label">召回条数</span>
        <el-input-number v-model="draft.recallLimit" :min="1" :max="20" size="small" />
        <span class="setting-hint">四层合计的上限（1–20）；越多越慢</span>
      </div>
      <div class="setting-row">
        <span class="setting-label">注入画像</span>
        <el-switch v-model="draft.injectProfile" />
        <span class="setting-hint">把人格画像整段作为背景，而不是按相似度挑几条</span>
      </div>
      <div class="setting-row">
        <span class="setting-label">做梦时间</span>
        <el-input v-model="draft.dreamCron" size="small" style="width: 180px" placeholder="0 40 3 * * *" />
        <span class="setting-hint">6 段 cron；做梦 = 去重合并 · 遗忘 · 重算画像</span>
      </div>
      <div class="setting-row">
        <span class="setting-label">遗忘天数</span>
        <el-input-number v-model="draft.forgetAfterDays" :min="7" :max="3650" size="small" />
        <span class="setting-hint">重要度 ≤2 且从未命中的记忆，超过这么多天就软删</span>
      </div>

      <div class="setting-actions">
        <el-button type="primary" size="small" :loading="saving" :disabled="!dirty" @click="save">保存</el-button>
        <el-button size="small" :disabled="!dirty" @click="reset">撤销</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.memory-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
  padding: var(--space-lg);
}

.panel {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
  padding: var(--space-lg);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-sm);
}

.panel-title {
  margin: 0 0 var(--space-sm);
  font-size: var(--text-md);
  color: var(--color-ink);
}

.panel-head .panel-title {
  margin: 0;
}

.panel-hint {
  margin: 0 0 var(--space-md);
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--color-muted);
}

.level-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-md);
}

.level-cell {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  padding: var(--space-md);
  background: var(--color-sunken);
}

.level-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.level-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-ink);
}

.level-count {
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.level-hint {
  margin: var(--space-2xs) 0 0;
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--color-muted);
}

.profile-row {
  display: flex;
  gap: var(--space-sm);
  margin-top: var(--space-md);
  font-size: var(--text-sm);
}

.profile-label {
  flex: none;
  color: var(--color-ink-2);
}

.profile-text {
  color: var(--color-ink);
  line-height: 1.6;
}

.profile-text.muted {
  color: var(--color-muted);
}

.create-box {
  border: 1px dashed var(--color-rule);
  border-radius: var(--radius);
  padding: var(--space-md);
  margin-bottom: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.create-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.create-hint,
.setting-hint {
  font-size: var(--text-xs);
  color: var(--color-muted);
  line-height: 1.6;
}

.create-actions,
.setting-actions {
  display: flex;
  gap: var(--space-sm);
}

.setting-row {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-xs) 0;
  font-size: var(--text-sm);
}

.setting-label {
  flex: none;
  width: 84px;
  color: var(--color-ink-2);
}

.setting-actions {
  margin-top: var(--space-md);
}
</style>
