<template>
  <div class="page">
    <PageHeader
      title="模板中心"
      desc="模板是「一个应用的配置快照」：存下来，下次从它建一个新应用，不用从头配。"
    >
      <template #actions>
        <el-button size="small" :icon="Plus" :disabled="!canCreate" @click="openCreate">
          从应用存成模板
        </el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </template>
    </PageHeader>

    <div class="notice info">
      模板里存的是<b>配置</b>，不是应用本身：知识库 / 提示词 / 模型都是<b>引用</b>。
      从模板建应用时，如果当前环境缺某个引用，会按你选的「跳过」或「报错」处理。
      <b>模板不可编辑</b> —— 要改就从一个新应用再存一份（模板是快照，改快照等于改历史）。
    </div>

    <el-empty v-if="!loading && rows.length === 0" description="还没有模板：在「应用管理」里挑一个应用，点「从应用存成模板」" />

    <div v-else class="tpl-grid" v-loading="loading">
      <el-card v-for="t in rows" :key="t.id" class="tpl-card" shadow="hover">
        <div class="tpl-head">
          <span class="tpl-name">{{ t.name }}</span>
          <el-tag v-if="t.builtin" size="small" type="info">内置</el-tag>
          <el-tag size="small">{{ t.category || '未分类' }}</el-tag>
        </div>
        <div class="tpl-desc">{{ t.description || '（没有说明）' }}</div>
        <div class="tpl-meta">
          <span v-if="t.sourceAppId">来源应用：{{ t.sourceAppId.slice(0, 8) }}…</span>
          <span v-else>内置模板</span>
          <span>{{ formatTime(t.createdAt) }}</span>
        </div>
        <div class="tpl-actions">
          <el-button size="small" :disabled="!canCreate" @click="openDetail(t)">详情</el-button>
          <el-button size="small" type="primary" :disabled="!canCreate" @click="openInstantiate(t)">
            从模板建应用
          </el-button>
          <el-button size="small" :disabled="t.builtin || !canCreate" @click="remove(t)">删除</el-button>
        </div>
      </el-card>
    </div>

    <!-- 模板详情（顺带确认"这份模板还在"） -->
    <el-dialog v-model="detailVisible" title="模板详情" width="560px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detailTarget" :column="1" border size="small">
          <el-descriptions-item label="名称">{{ detailTarget.name }}</el-descriptions-item>
          <el-descriptions-item label="分类">
            {{ detailTarget.category || '（未分类）' }}
          </el-descriptions-item>
          <el-descriptions-item label="说明">
            {{ detailTarget.description || '（没有说明）' }}
          </el-descriptions-item>
          <el-descriptions-item label="来源">
            {{ detailTarget.sourceAppId || '内置模板（不可删）' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatTime(detailTarget.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="内容">
            详情接口不带 payload（那是一份完整导出包，全量返回会让接口体积与模板数成正比）。
            要看内容，请「从模板建应用」之后看建出来的应用。
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 从应用存成模板 -->
    <el-dialog v-model="createVisible" title="从应用存成模板" width="560px">
      <el-form :model="createForm" label-width="96px">
        <el-form-item label="源应用" required>
          <el-select v-model="createForm.sourceAppId" filterable placeholder="选一个应用（它就是模板的内容）" style="width: 100%">
            <el-option v-for="a in apps" :key="a.id" :label="`${a.name}（${a.type}·${a.kbIds?.length ?? 0} 个知识库）`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板名称" required>
          <el-input v-model="createForm.name" maxlength="64" show-word-limit placeholder="例如：合同审查（法务通用）" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="createForm.category" maxlength="32" placeholder="例如：法务 / 人事 / IT" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="createForm.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">
        存在模板里的是<b>当前这一刻</b>的配置。之后你改源应用，模板<b>不会</b>跟着变。
      </div>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 从模板建应用 -->
    <el-dialog v-model="instVisible" title="从模板建应用" width="520px">
      <el-form label-width="112px">
        <el-form-item label="模板">
          <span>{{ instTarget?.name }}</span>
        </el-form-item>
        <el-form-item label="缺引用时">
          <el-radio-group v-model="skipMissing">
            <el-radio :value="true">跳过（缺的知识库/提示词不带进来）</el-radio>
            <el-radio :value="false">报错（先让我看清缺什么）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div class="dialog-hint">
        建出来的是一个<b>新应用</b>，与原模板无关联；之后改它不会影响模板。
      </div>
      <template #footer>
        <el-button @click="instVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doInstantiate">建应用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { templatesApi, assistantsApi } from '@/api'
import { ApiError } from '@/api/types'
import { useSessionStore } from '@/stores/session'

const router = useRouter()
// ★ 权限判据与路由守卫同源：都用 session.can()，不另写一份过滤逻辑
const session = useSessionStore()
// ★ 与后端一致：借应用权限（见 route.ts 里的说明）
const canCreate = computed(() => session.can('assistant:edit'))

const rows = ref<templatesApi.Template[]>([])
const apps = ref<Array<{ id: string; name: string; type: string; kbIds?: string[] }>>([])
const loading = ref(false)

const createVisible = ref(false)
const createForm = ref({ sourceAppId: '', name: '', category: '', description: '' })
const instVisible = ref(false)
const instTarget = ref<templatesApi.Template | null>(null)
const skipMissing = ref(true)
const saving = ref(false)

function formatTime(s: string) {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}

async function reload() {
  loading.value = true
  try {
    rows.value = (await templatesApi.listTemplates()) as unknown as templatesApi.Template[]
  } catch (e) {
    ElMessage.error(`读模板失败：${(e as Error).message}`)
  } finally {
    loading.value = false
  }
}

async function loadApps() {
  // ★ 只拉第一页 100 条就够：这个下拉是「挑一个应用存成模板」，不是应用管理页
  const res = await assistantsApi.listAssistants({ page: 1, size: 100 })
  apps.value = (res.list ?? []) as typeof apps.value
}

function openCreate() {
  createForm.value = { sourceAppId: '', name: '', category: '', description: '' }
  createVisible.value = true
  if (apps.value.length === 0) {
    loadApps().catch((e) => ElMessage.error(`读应用失败：${(e as Error).message}`))
  }
}

async function doCreate() {
  if (!createForm.value.sourceAppId) return ElMessage.warning('先选一个源应用')
  if (!createForm.value.name.trim()) return ElMessage.warning('模板名称不能为空')
  saving.value = true
  try {
    await templatesApi.createTemplate({ ...createForm.value })
    ElMessage.success('已存为模板')
    createVisible.value = false
    await reload()
  } catch (e) {
    ElMessage.error(`存模板失败：${(e as Error).message}`)
  } finally {
    saving.value = false
  }
}

function openInstantiate(t: templatesApi.Template) {
  instTarget.value = t
  skipMissing.value = true
  instVisible.value = true
}

/**
 * 模板详情：重新按 id 读一次。
 *
 * <p>★ 用途不是"多显示几个字段"，而是**建应用之前确认这份模板还在**：列表可能是几十秒前读的，
 * 这段时间里别人可能把它删了。点了「从模板建应用」再去 404，用户看到的会是"建应用失败"，
 * 而不是"这份模板已经不在了"。
 *
 * <p>★ 详情视图**不带 payload**（后端 `TemplateView` 明写：payload 就是一份完整导出包，
 * 全量返回会让接口体积与模板数成正比）。真要看内容，请「从模板建应用」之后看建出来的应用。
 */
const detailVisible = ref(false)
const detailTarget = ref<templatesApi.Template | null>(null)
const detailLoading = ref(false)

async function openDetail(t: templatesApi.Template) {
  detailVisible.value = true
  detailLoading.value = true
  detailTarget.value = null
  try {
    detailTarget.value = await templatesApi.getTemplate(t.id)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '读取模板详情失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function doInstantiate() {
  if (!instTarget.value) return
  saving.value = true
  try {
    const created = (await templatesApi.instantiateTemplate(instTarget.value.id, {
      skipMissingReferences: skipMissing.value
    })) as { id?: string }
    ElMessage.success('已建出新应用')
    instVisible.value = false
    // ★ 建完直接带去应用详情：让用户看到「建出来了」而不是只收到一条 toast
    if (created?.id) router.push(`/assistants/${created.id}`)
    else await reload()
  } catch (e) {
    ElMessage.error(`建应用失败：${(e as Error).message}`)
  } finally {
    saving.value = false
  }
}

async function remove(t: templatesApi.Template) {
  try {
    await ElMessageBox.confirm(`删除模板「${t.name}」？已用它建出来的应用不受影响。`, '删除模板', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await templatesApi.deleteTemplate(t.id)
    ElMessage.success('已删除')
    await reload()
  } catch (e) {
    ElMessage.error(`删除失败：${(e as Error).message}`)
  }
}

onMounted(reload)
</script>

<style scoped>
.tpl-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.tpl-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.tpl-name {
  font-weight: 600;
  font-size: 15px;
}
.tpl-desc {
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.6;
  min-height: 42px;
}
.tpl-meta {
  display: flex;
  justify-content: space-between;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 8px 0;
}
.tpl-actions {
  display: flex;
  gap: 8px;
}
.dialog-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  background: var(--el-fill-color-lighter);
  padding: 8px 10px;
  border-radius: 4px;
}
</style>
