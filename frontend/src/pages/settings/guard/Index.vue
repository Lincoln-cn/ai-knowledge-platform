<template>
  <div class="page settings-page">
    <PageHeader title="安全围栏" desc="回答前的输入检查：按分类维护的敏感词与敏感问题集。">
      <template #actions>
        <span class="meta-line micro">
          <span>检查 <b>{{ form.inputEnabled ? '开' : '关' }}</b></span>
          <span>分类 <b>{{ categories.length }}</b></span>
          <span>规则 <b>{{ rules.length }}</b></span>
          <span v-if="updatedAtText" class="muted">上次修改 {{ updatedAtText }}</span>
        </span>
      </template>
    </PageHeader>

    <div class="notice info">
      <div>
        这一版只做<b>输入侧</b>：用户这句话在送给模型之前被检查一次。输出侧的内容检查、输入审计与输出审计
        在 <b>#43</b> 里另立 —— 本页不会假装它们已经存在。
      </div>
    </div>

    <div v-if="loadError" class="notice err">
      <div><b>读取失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" class="mt8" @click="load">重试</el-button>
    </div>

    <template v-else>
      <!-- ── ① 输入检查 ───────────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">
              输入检查
              <span class="tag-state tag-save">需保存</span>
            </div>
            <div class="micro muted">命中即拒答，不再送给模型 —— 拒答话术由这里决定</div>
          </div>
          <!-- 保存动作放在它管的那一块头上：四格都在这一块里，改完就地能存，
               不必先滚到页底再回头看改了什么。 -->
          <div class="panel-tools">
            <span v-if="!canEdit" class="micro muted">只读</span>
            <span v-else-if="dirty" class="unsaved"><b>{{ dirtyCount }}</b> 项未保存</span>
            <span v-else class="micro muted">没有未保存的改动</span>
            <el-button :disabled="!canEdit || !dirty || saving" @click="reset">撤销</el-button>
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
        <div class="panel-body form-grid">
          <div class="field">
            <label class="field-label">启用输入检查</label>
            <div class="switch-row">
              <el-switch v-model="form.inputEnabled" :disabled="!canEdit" />
              <span class="micro muted">{{ form.inputEnabled ? '命中即拒答' : '不检查，直接问模型' }}</span>
            </div>
            <div class="field-note micro muted">
              关掉之后全平台所有链路的问答都在<b>无检查状态</b>下运行（通用问答、挂应用的问答、公开问答）。
              这一格会被单独记一条审计，因为它是"最该被追查"的一次改动。
            </div>
          </div>

          <div class="field">
            <label class="field-label">接入对话链路</label>
            <div class="switch-row">
              <el-switch v-model="form.advisorsEnabled" :disabled="!canEdit" />
              <span class="micro" :class="form.advisorsEnabled ? 'muted' : 'warn-text'">
                {{ form.advisorsEnabled ? '围栏已挂上' : '未挂上 —— 上面开着也不检查' }}
              </span>
            </div>
            <div class="field-note micro muted">
              这是<b>总闸</b>：它决定对话链路里要不要挂上这个围栏。关掉之后上面那一格开得再对，
              也不会有任何检查 —— 两格是「有没有装」与「装了什么」的关系，不是重复。
            </div>
          </div>

          <div class="field field-wide">
            <label class="field-label">统一拒答话术</label>
            <el-input
              v-model="form.defaultRefusalText"
              :maxlength="200"
              show-word-limit
              :disabled="!canEdit"
            />
            <div class="field-note micro muted">
              只写"不能回答"，<b>不要复述命中的词</b> —— 复述等于把词表变成可以逐条试出来的探测器。
            </div>
          </div>

          <div class="field">
            <label class="field-label">按分类给不同话术</label>
            <div class="switch-row">
              <el-switch
                v-model="form.categoryRefusalEnabled"
                :disabled="!canEdit || !form.advisorsEnabled"
              />
              <span class="micro muted">
                {{ form.categoryRefusalEnabled ? '按分类分别回话' : '统一回同一句' }}
              </span>
            </div>
            <div class="field-note micro muted">
              默认关。打开后不同分类回不同的话，"这句属于哪一类"就变成可探测的信息；
              对外服务的部署建议保持关闭。
            </div>
          </div>
        </div>
      </section>

      <!-- ── ② 分类 ───────────────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">
              分类
              <span class="tag-state tag-live">即时生效</span>
            </div>
            <div class="micro muted">内置四类（政治 / 色情 / 暴力 / 未分类）可停用，也可以加自己的分类</div>
          </div>
          <div class="panel-tools">
            <el-button size="small" :disabled="!canEdit" @click="openCategoryDialog(null)">
              新建分类
            </el-button>
          </div>
        </div>
        <div class="panel-body">
          <el-table :data="categories" size="small" row-key="id" :show-overflow-tooltip="false">
            <el-table-column prop="name" label="名称" min-width="220" />
            <el-table-column prop="code" label="编码" width="150" />
            <el-table-column label="规则数" width="90">
              <template #default="{ row }">
                <span class="micro">{{ ruleCountOf(row.id) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="sortOrder" label="排序" width="80" />
            <el-table-column label="来源" width="90">
              <template #default="{ row }">
                <span class="micro muted">{{ row.builtin ? '内置' : '自建' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '已停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="210">
              <template #default="{ row }">
                <el-button link size="small" @click="filterByCategory(row.id)">
                  看规则
                </el-button>
                <el-button
                  link
                  size="small"
                  :disabled="!canEdit"
                  @click="openCategoryDialog(row)"
                >
                  编辑
                </el-button>
                <el-button
                  link
                  size="small"
                  :disabled="!canEdit || !row.enabled"
                  @click="disableCategory(row)"
                >
                  停用
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <!-- ── ③ 规则 ───────────────────────────────────────────────────── -->
      <section ref="ruleSection" class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">
              规则
              <span class="tag-state tag-live">即时生效</span>
            </div>
            <div class="micro muted">
              关键词按"包含"匹配；敏感问题集会先去掉标点与空白再比，所以换个标点也照样命中
            </div>
          </div>
          <div class="panel-tools">
            <el-select
              v-model="filterCategoryId"
              size="small"
              clearable
              placeholder="全部分类"
              class="filter-select"
            >
              <el-option
                v-for="c in categories"
                :key="c.id"
                :label="c.name"
                :value="c.id"
                :disabled="!c.enabled"
              />
            </el-select>
            <el-button size="small" :disabled="!canEdit" @click="openRuleDialog(null)">
              新建规则
            </el-button>
          </div>
        </div>
        <div class="panel-body">
          <el-table :data="filteredRules" size="small" row-key="id">
            <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                {{ row.kind === 'QUESTION' ? '敏感问题' : '关键词' }}
              </template>
            </el-table-column>
            <el-table-column prop="categoryName" label="分类" width="110" />
            <el-table-column prop="note" label="备注" min-width="140" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '已停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-button link size="small" :disabled="!canEdit" @click="openRuleDialog(row)">
                  编辑
                </el-button>
                <el-button link size="small" :disabled="!canEdit" @click="removeRule(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="micro muted">
                这个分类下还没有规则。空规则表等于<b>不拦任何输入</b> —— 界面上不放"示例词"，
                因为"该拦什么"是运营口径，不是产品默认值。
              </div>
            </template>
          </el-table>
        </div>
      </section>

      <!-- ── ④ 最近拦截 ───────────────────────────────────────────────── -->
      <section class="panel">
        <div class="panel-head">
          <div>
            <div class="card-title">最近 7 天拦截</div>
            <div class="micro muted">
              按分类计数。这里只给次数，不列原话 —— 需要看是谁问了什么时走审计记录
            </div>
          </div>
        </div>
        <div class="panel-body">
          <div v-if="hits.length === 0" class="micro muted">最近 7 天没有拦截记录。</div>
          <div v-else class="hit-row">
            <div v-for="h in hits" :key="h.categoryCode" class="hit-cell">
              <div class="hit-label micro muted">{{ categoryNameOf(h.categoryCode) }}</div>
              <div class="hit-value">{{ h.hits }}</div>
            </div>
          </div>
        </div>
      </section>

    </template>

    <!-- 新建 / 编辑分类 -->
    <el-dialog v-model="categoryDialog" :title="categoryForm.id ? '编辑分类' : '新建分类'" width="440px">
      <el-form label-width="76px">
        <el-form-item label="编码">
          <el-input
            v-model="categoryForm.code"
            :disabled="Boolean(categoryForm.id)"
            placeholder="大写字母开头，如 RELIGION"
            maxlength="32"
          />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="categoryForm.name" maxlength="32" placeholder="如 宗教" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="categoryForm.sortOrder" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态">
          <div class="switch-row">
            <el-switch v-model="categoryForm.enabled" />
            <span class="micro muted">
              {{ categoryForm.enabled ? '参与检查' : '停用：这一类下的规则还在，但不参与匹配' }}
            </span>
          </div>
        </el-form-item>
        <el-form-item label="专属话术">
          <el-input
            v-model="categoryForm.refusalText"
            :maxlength="200"
            placeholder="留空则用统一话术"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingCategory" @click="submitCategory">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新建 / 编辑规则 -->
    <el-dialog v-model="ruleDialog" :title="ruleForm.id ? '编辑规则' : '新建规则'" width="520px">
      <el-form label-width="76px">
        <el-form-item label="分类">
          <el-select v-model="ruleForm.categoryId" placeholder="选择分类" class="filter-select">
            <el-option
              v-for="c in categories"
              :key="c.id"
              :label="c.name"
              :value="c.id"
              :disabled="!c.enabled"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="ruleForm.kind">
            <el-radio value="KEYWORD">关键词</el-radio>
            <el-radio value="QUESTION">敏感问题</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="ruleForm.content"
            type="textarea"
            :rows="3"
            :maxlength="200"
            show-word-limit
            :placeholder="
              ruleForm.kind === 'QUESTION'
                ? '一句完整的问题；标点与空格会被忽略后再比对'
                : '一个词或词组；按包含匹配'
            "
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ruleForm.note" maxlength="200" placeholder="为什么加这条（可选）" />
        </el-form-item>
        <el-form-item label="状态">
          <div class="switch-row">
            <el-switch v-model="ruleForm.enabled" />
            <span class="micro muted">
              {{ ruleForm.enabled ? '参与检查' : '停用：留在表里，但不再拦' }}
            </span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingRule" @click="submitRule">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import {
  createGuardCategory,
  createGuardRule,
  deleteGuardRule,
  disableGuardCategory,
  getGuardSettings,
  listGuardCategories,
  listGuardHits,
  listGuardRules,
  updateGuardCategory,
  updateGuardSettings,
  updateGuardRule,
  type GuardCategory,
  type GuardHitCount,
  type GuardRule,
  type GuardRuleInput,
  type GuardRuleKind,
  type GuardSettingsUpdate
} from '@/api/guard'
import { ApiError } from '@/api/types'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'

/**
 * 安全围栏（`/settings/guard`，ROADMAP §11.53）。
 *
 * ★ 这一页的四块对应四件事：**要不要查**（输入检查）、**查哪几类**（分类）、
 *   **查什么**（规则）、**查到了多少**（拦截计数）。四块必须同页 ——
 *   把它们拆到两个菜单里，"把某一类关掉"与"它最近挡了多少"就再也对不上账。
 *
 * ★ 分类与规则**不是**表单的一部分，它们有各自的增删改接口。整行提交的只有最上面那四格，
 *   因为那四格互相牵连（关检查之后分类话术就无意义）。
 */
const session = useSessionStore()
const canEdit = computed(() => session.can(PERM.guardManage))

const EMPTY: GuardSettingsUpdate = {
  inputEnabled: true,
  defaultRefusalText: '抱歉，这个问题不在可回答范围内。',
  advisorsEnabled: true,
  categoryRefusalEnabled: false
}

const form = reactive<GuardSettingsUpdate>({ ...EMPTY })
const baseline = ref<string>(JSON.stringify(EMPTY))
const saving = ref(false)
const updatedAt = ref<string | null>(null)
const loadError = ref<{ reason: string; hint?: string | null } | null>(null)

const categories = ref<GuardCategory[]>([])
const rules = ref<GuardRule[]>([])
const hits = ref<GuardHitCount[]>([])
const filterCategoryId = ref<string | null>(null)
const ruleSection = ref<HTMLElement | null>(null)

/** 每个分类配了几条规则 —— 「哪一类还没配」比「分类名」更值得一眼看到。 */
function ruleCountOf(categoryId: string) {
  return rules.value.filter((r) => r.categoryId === categoryId).length
}

const dirty = computed(() => JSON.stringify(form) !== baseline.value)

/**
 * 未保存的**项数**（不是"有没有改"）。
 * ★ 保存栏写「几项未保存」而不是「有改动」：四格里只动了一个开关时，
 *   「有改动」没法让人确认自己是不是还漏了一格。
 */
const dirtyCount = computed(() => {
  const base = JSON.parse(baseline.value) as GuardSettingsUpdate
  const now = toPayload()
  let count = 0
  if (now.inputEnabled !== base.inputEnabled) count += 1
  if (now.advisorsEnabled !== base.advisorsEnabled) count += 1
  if (now.defaultRefusalText !== base.defaultRefusalText) count += 1
  if (now.categoryRefusalEnabled !== base.categoryRefusalEnabled) count += 1
  return count
})
const updatedAtText = computed(() =>
  updatedAt.value ? new Date(updatedAt.value).toLocaleString() : ''
)
const filteredRules = computed(() =>
  filterCategoryId.value
    ? rules.value.filter((r) => r.categoryId === filterCategoryId.value)
    : rules.value
)

function reasonOf(e: unknown): string {
  if (e instanceof ApiError) return e.message
  return e instanceof Error ? e.message : ''
}

function categoryNameOf(code: string): string {
  return categories.value.find((c) => c.code === code)?.name ?? code
}

function adopt(data: GuardSettingsUpdate & { updatedAt?: string | null }) {
  form.inputEnabled = data.inputEnabled
  form.defaultRefusalText = data.defaultRefusalText
  form.advisorsEnabled = data.advisorsEnabled
  form.categoryRefusalEnabled = data.categoryRefusalEnabled
  updatedAt.value = data.updatedAt ?? null
  baseline.value = JSON.stringify(toPayload())
}

function toPayload(): GuardSettingsUpdate {
  return {
    inputEnabled: form.inputEnabled,
    defaultRefusalText: form.defaultRefusalText,
    advisorsEnabled: form.advisorsEnabled,
    categoryRefusalEnabled: form.categoryRefusalEnabled
  }
}

async function load() {
  loadError.value = null
  try {
    adopt(await getGuardSettings())
  } catch (e) {
    loadError.value = {
      reason: reasonOf(e) || '读取安全围栏设置失败',
      hint: e instanceof ApiError ? e.hint : null
    }
    return
  }
  await Promise.all([loadCategories(), loadRules(), loadHits()])
}

async function loadCategories() {
  try {
    categories.value = await listGuardCategories()
  } catch (e) {
    ElMessage.error(reasonOf(e) || '读取分类失败')
  }
}

async function loadRules() {
  try {
    rules.value = await listGuardRules()
  } catch (e) {
    ElMessage.error(reasonOf(e) || '读取规则失败')
  }
}

async function loadHits() {
  try {
    hits.value = await listGuardHits(7)
  } catch {
    // 计数读不到不影响配置：不弹错，保持空列表（页面上那行"没有拦截记录"会照常显示）
    hits.value = []
  }
}

function reset() {
  adopt(JSON.parse(baseline.value) as GuardSettingsUpdate)
}

async function save() {
  saving.value = true
  try {
    adopt(await updateGuardSettings(toPayload()))
    ElMessage.success('已保存，下一次提问就按新规则检查')
  } catch (e) {
    const hint = e instanceof ApiError ? e.hint : null
    ElMessage.error(hint ? `${reasonOf(e)}：${hint}` : reasonOf(e) || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

/* ── 分类 ─────────────────────────────────────────────────────────── */

const categoryDialog = ref(false)
const savingCategory = ref(false)
// ★ 表单里可空字段一律用空串表示"没填"，不用 null —— 否则每次取值都要先判空，
//   而"空串"与"null"在提交时都要变成 null（后端把 null 当"没有专属话术"）。
const categoryForm = reactive<{
  id: string | null
  code: string
  name: string
  sortOrder: number
  enabled: boolean
  refusalText: string
}>({
  id: null,
  code: '',
  name: '',
  sortOrder: 100,
  enabled: true,
  refusalText: ''
})

function openCategoryDialog(row: GuardCategory | null) {
  categoryForm.id = row?.id ?? null
  categoryForm.code = row?.code ?? ''
  categoryForm.name = row?.name ?? ''
  categoryForm.sortOrder = row?.sortOrder ?? 100
  categoryForm.enabled = row?.enabled ?? true
  // ★ 回填：保存是整行覆盖，不回填就等于「改个名字顺便把专属话术清掉」
  categoryForm.refusalText = row?.refusalText ?? ''
  categoryDialog.value = true
}

async function submitCategory() {
  if (!categoryForm.code.trim() || !categoryForm.name.trim()) {
    ElMessage.warning('编码与名称都要填')
    return
  }
  const body = {
    code: categoryForm.code.trim(),
    name: categoryForm.name.trim(),
    sortOrder: categoryForm.sortOrder,
    enabled: categoryForm.enabled,
    refusalText: categoryForm.refusalText.trim() || null
  }
  const editing = Boolean(categoryForm.id)
  savingCategory.value = true
  try {
    if (categoryForm.id) {
      await updateGuardCategory(categoryForm.id, body)
    } else {
      await createGuardCategory(body)
    }
    categoryDialog.value = false
    ElMessage.success(editing ? '已更新分类' : '已新建分类')
    await loadCategories()
  } catch (e) {
    ElMessage.error(reasonOf(e) || (editing ? '更新分类失败' : '新建分类失败'))
  } finally {
    savingCategory.value = false
  }
}

async function disableCategory(row: GuardCategory) {
  try {
    await ElMessageBox.confirm(
      `停用「${row.name}」后，这一类下的规则不再参与检查（规则本身不删）。`,
      '停用分类',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await disableGuardCategory(row.id)
    ElMessage.success('已停用')
    await loadCategories()
  } catch (e) {
    ElMessage.error(reasonOf(e) || '停用失败')
  }
}

function filterByCategory(id: string) {
  filterCategoryId.value = id
  // 只筛不滚的话，规则区在视口外时点「看规则」看起来像没反应 —— 把人带到结果那里。
  nextTick(() => ruleSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

/* ── 规则 ─────────────────────────────────────────────────────────── */

const ruleDialog = ref(false)
const savingRule = ref(false)
const ruleForm = reactive<{
  id: string | null
  categoryId: string
  kind: GuardRuleKind
  content: string
  enabled: boolean
  note: string
}>({
  id: null,
  categoryId: '',
  kind: 'KEYWORD',
  content: '',
  enabled: true,
  note: ''
})

function openRuleDialog(row: GuardRule | null) {
  ruleForm.id = row?.id ?? null
  ruleForm.categoryId = row?.categoryId ?? (filterCategoryId.value || categories.value[0]?.id || '')
  ruleForm.kind = row?.kind ?? 'KEYWORD'
  ruleForm.content = row?.content ?? ''
  ruleForm.enabled = row?.enabled ?? true
  ruleForm.note = row?.note ?? ''
  ruleDialog.value = true
}

async function submitRule() {
  if (!ruleForm.categoryId) {
    ElMessage.warning('先选一个分类')
    return
  }
  if (!ruleForm.content.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  const body: GuardRuleInput = {
    categoryId: ruleForm.categoryId,
    kind: ruleForm.kind as GuardRuleKind,
    content: ruleForm.content.trim(),
    enabled: ruleForm.enabled,
    note: ruleForm.note.trim() || null
  }
  savingRule.value = true
  try {
    if (ruleForm.id) {
      await updateGuardRule(ruleForm.id, body)
    } else {
      await createGuardRule(body)
    }
    ruleDialog.value = false
    ElMessage.success(ruleForm.id ? '已更新' : '已新增')
    await loadRules()
  } catch (e) {
    ElMessage.error(reasonOf(e) || '保存规则失败')
  } finally {
    savingRule.value = false
  }
}

async function removeRule(row: GuardRule) {
  try {
    await ElMessageBox.confirm('删除后这条规则不再生效，历史审计不受影响。', '删除规则', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteGuardRule(row.id)
    ElMessage.success('已删除')
    await loadRules()
  } catch (e) {
    ElMessage.error(reasonOf(e) || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
/*
 * ★ 本页**不**定义表单栅格与保存栏。
 *
 * `.form-grid` / `.field-label` / `.field-note` / `.card-title` / `.meta-line` /
 * `.save-bar` / `.save-state` / `.save-actions` 全部在 `src/styles/index.css`。
 * 它们曾经只写在 `pages/settings/chat/Index.vue` 的 scoped 里 —— scoped 不外泄，
 * 于是本页拿到的是**空类**：三个字段垂直堆成一列、标签和控件贴在一起、
 * 说明文字紧贴下一个字段的标签（用户原话：「太拥挤，都看不清」）。
 * ⇒ 页面里不要再复制这些类。
 */
.filter-select {
  width: 150px;
}

/* 开关与它右边的状态短语：短语必须在同一行，否则读者要自己把两行连起来 */
.switch-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.warn-text {
  color: var(--color-warn);
}

/* 区块语义徽标：这一块改完要不要点保存 */
.tag-state {
  padding: 0 6px;
  border-radius: var(--radius-sm);
  font-size: var(--text-2xs);
  font-weight: 400;
  line-height: 18px;
}

.tag-save {
  color: var(--color-warn);
  background: color-mix(in srgb, var(--color-warn) 12%, transparent);
}

.tag-live {
  color: var(--color-muted);
  background: var(--color-sunken);
}

.unsaved {
  color: var(--color-warn);
}

.unsaved b {
  font-weight: 600;
}

.hit-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-md);
}

.hit-cell {
  min-width: 96px;
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.hit-label {
  margin-bottom: 2px;
}

.hit-value {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
</style>
