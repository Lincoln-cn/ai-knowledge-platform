<template>
  <div class="page">
    <PageHeader
      title="应用管理"
      desc="应用把模型、知识库与提示词组装成可发布的问答服务。简单应用看模型 / 知识库数，编排应用看节点数 / 版本；另有发布态 / 用量 / 满意度"
    >
      <template #actions>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        <el-button
          v-if="can(PERM.assistantEdit)"
          size="small"
          type="primary"
          @click="openCreate"
        >新建应用</el-button>
      </template>
    </PageHeader>

    <div class="notice info">
      操作说明：应用分<b>简单</b>（提示词约束）与<b>编排</b>（画布式流程）两档，<b>创建后类型不可改</b>。
      简单应用点<b>配置</b>打开左表单 + 右调试；编排应用点<b>编排</b>进入流程画布（对编排应用而言配置就是画布，所以只有一个入口）；
      <b>概览</b>看用量、公开凭证、API Key 与导入导出；<b>演示</b>进入问答试效果；<b>发布</b>先跑发布前校验。
      内置应用不可改对话设定。
    </div>

    <div class="filters panel">
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索应用名称或描述"
        style="width: 230px"
        clearable
        @keyup.enter="fetchList"
        @clear="fetchList"
      />
      <el-select v-model="query.published" size="small" placeholder="全部状态" style="width: 130px" @change="fetchList">
        <el-option label="全部状态" value="" />
        <el-option label="已发布" value="true" />
        <el-option label="草稿" value="false" />
      </el-select>
      <el-select v-model="query.type" size="small" placeholder="全部类型" style="width: 130px" @change="fetchList">
        <el-option label="全部类型" value="" />
        <el-option label="简单" value="simple" />
        <el-option label="编排" value="workflow" />
      </el-select>
      <span class="micro muted">共 {{ total }} 个</span>
    </div>

    <div v-loading="loading" class="grid">
      <el-card v-for="a in list" :key="a.id" shadow="never" class="app-card">
        <div class="ac-head">
          <span class="ac-icon">{{ a.icon }}</span>
          <div class="ac-title">
            <div class="ac-name-row">
              <span class="h3 ac-name">{{ a.name }}</span>
              <span class="ac-tags">
                <el-tag size="small" effect="plain" class="ac-type-tag">
                  {{ a.type === 'workflow' ? '编排' : '简单' }}
                </el-tag>
                <el-tag v-if="a.builtin" size="small" effect="plain" type="info">内置</el-tag>
              </span>
            </div>
            <div class="micro muted ellipsis-2 ac-desc" :title="a.description">{{ a.description }}</div>
          </div>
          <el-tag size="small" effect="plain" :type="a.published ? 'success' : 'info'">
            {{ a.published ? '已发布' : '草稿' }}
          </el-tag>
        </div>

        <dl class="ac-facts">
          <!--
            编排应用**没有「模型」这一项**（模型在各 AI 节点里，一条流程可以多个），
            所以它的卡片数据与简单应用是两套：
              简单 → 模型 / 知识库
              编排 → 节点 / 版本
          -->
          <div class="fact-group">
            <div class="fact">
              <dt class="label">{{ a.type === 'workflow' ? '节点' : '模型' }}</dt>
              <dd :title="a.type === 'workflow' ? '' : a.modelName">
                <template v-if="a.type === 'workflow'">
                  <span class="num">{{ a.workflow?.nodeCount ?? 0 }}</span> 个
                </template>
                <template v-else>{{ a.modelName || '—' }}</template>
              </dd>
            </div>
            <div class="fact">
              <dt class="label">{{ a.type === 'workflow' ? '版本' : '知识库' }}</dt>
              <dd>
                <template v-if="a.type === 'workflow'">
                  <span v-if="a.workflow?.publishedVersion" class="num">v{{ a.workflow.publishedVersion }}</span>
                  <span v-else class="faint">未发布</span>
                </template>
                <template v-else>
                  <span class="num">{{ a.kbIds.length }}</span> 个
                </template>
              </dd>
            </div>
          </div>
          <div class="fact-group">
            <div class="fact">
              <dt class="label">用量</dt>
              <dd>
                <span class="num">{{ a.callCount.toLocaleString() }}</span> 次 ·
                <span class="num">{{ a.tokenCount.toLocaleString() }}</span> token
              </dd>
            </div>
            <div class="fact">
              <dt class="label">满意度</dt>
              <dd>
                <template v-if="a.satisfaction != null">
                  <span class="num">{{ a.satisfaction }}</span>%
                </template>
                <template v-else><span class="faint">—</span></template>
              </dd>
            </div>
          </div>
        </dl>

        <div class="ac-actions">
          <!--
            一个入口，不是两个。
            对编排应用来说「配置」与「编排」本来就是同一件事 —— 画布就是它的配置面，
            放两个按钮只会让人以为点进去会看到不同的东西。
            主操作（配置/编排）是全卡唯一的 accent 落点，用 primary link。
          -->
          <el-button
            v-if="can(PERM.assistantEdit)"
            link size="small"
            type="primary"
            @click="openConfig(a)"
          >{{ a.type === 'workflow' ? '编排' : '配置' }}</el-button>
          <el-button link size="small" @click="openOverview(a)">概览</el-button>
          <el-button link size="small" @click="demo(a)">演示</el-button>
          <el-button
            v-if="can(PERM.assistantPublish)"
            link size="small"
            :type="a.published ? 'warning' : 'success'"
            @click="togglePublish(a)"
          >{{ a.published ? '下架' : '发布' }}</el-button>
          <span class="grow" />
          <el-button
            v-if="can(PERM.assistantEdit) && !a.builtin"
            link size="small" type="danger"
            @click="remove(a)"
          >删除</el-button>
        </div>
      </el-card>

      <div v-if="!loading && !list.length" class="empty panel">
        <div class="empty-title">{{ hasFilter ? '当前筛选下没有应用' : '还没有创建任何应用' }}</div>
        <p class="micro muted">{{ hasFilter ? '换个关键词或清空筛选试试' : '创建第一个应用，开始组装问答服务' }}</p>
        <el-button v-if="can(PERM.assistantEdit)" size="small" type="primary" @click="openCreate">新建应用</el-button>
      </div>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="fetchList"
      />
    </div>

    <AssistantCreateDialog ref="createRef" @created="fetchList" @saved="fetchList" />
    <AssistantConfigDrawer ref="configRef" :assistant="current" @saved="fetchList" @published="fetchList" />
    <AssistantOverviewDrawer ref="overviewRef" :assistant="current" @saved="fetchList" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { assistantsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Assistant } from '@/api/assistants'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import PageHeader from '@/components/PageHeader.vue'
import AssistantCreateDialog from './components/AssistantCreateDialog.vue'
import AssistantConfigDrawer from './components/AssistantConfigDrawer.vue'
import AssistantOverviewDrawer from './components/AssistantOverviewDrawer.vue'

const router = useRouter()
const session = useSessionStore()
const can = (code: string) => session.can(code)

const list = ref<Assistant[]>([])
const total = ref(0)
const loading = ref(false)
const pageSize = 12
const current = ref<Assistant | null>(null)

const query = reactive({
  keyword: '',
  published: '' as '' | 'true' | 'false',
  type: '' as '' | 'simple' | 'workflow',
  page: 1
})

const hasFilter = computed(() => Boolean(query.keyword || query.published || query.type))

const createRef = ref<InstanceType<typeof AssistantCreateDialog>>()
const configRef = ref<InstanceType<typeof AssistantConfigDrawer>>()
const overviewRef = ref<InstanceType<typeof AssistantOverviewDrawer>>()

const fetchList = async () => {
  loading.value = true
  try {
    const result = await assistantsApi.listAssistants({
      keyword: query.keyword || undefined,
      published: query.published === '' ? undefined : query.published === 'true',
      type: query.type || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

const openCreate = () => createRef.value?.open()

const openWorkflow = (a: Assistant) => {
  // 路由由编排任务声明，这里只放链接
  router.push(`/assistants/${a.id}/workflow`)
}

const openConfig = (a: Assistant) => {
  // 编排应用没有提示词/检索配置面，配置即画布
  if (a.type === 'workflow') {
    openWorkflow(a)
    return
  }
  current.value = a
  configRef.value?.open()
}

const openOverview = (a: Assistant) => {
  current.value = a
  overviewRef.value?.open()
}

const demo = (a: Assistant) => {
  router.push({ path: '/conversation', query: { appId: a.id } })
}

const togglePublish = async (a: Assistant) => {
  if (a.published) {
    await ElMessageBox.confirm(
      `下架后公开链接将失效。确认下架「${a.name}」？`,
      '确认下架',
      { type: 'warning', confirmButtonText: '下架' }
    )
    await assistantsApi.setAssistantPublished(a.id, false)
    ElMessage.success('已下架')
    await fetchList()
    return
  }

  // 发布：先校验，不通过则列出原因
  try {
    const check = await assistantsApi.checkAssistantBeforePublish(a.id)
    if (!check.passed) {
      const failed = check.items.filter((i) => !i.passed)
      await ElMessageBox.alert(
        failed.map((i) => `· ${i.label}${i.detail ? '：' + i.detail : ''}`).join('\n'),
        '发布前校验未通过',
        { type: 'warning', confirmButtonText: '去配置' }
      )
      // 失败后直达对应配置面：简单开抽屉，编排进画布
      openConfig(a)
      return
    }
    await assistantsApi.setAssistantPublished(a.id, true)
    ElMessage.success('已发布')
    await fetchList()
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.warning({ message: `${error.message}${error.hint ? '。' + error.hint : ''}`, duration: 6000 })
    } else {
      ElMessage.error('发布失败')
    }
  }
}

const remove = async (a: Assistant) => {
  await ElMessageBox.confirm(
    `删除后公开链接与 API Key 一并作废，且不可恢复。确认删除「${a.name}」？`,
    '确认删除应用',
    { type: 'warning', confirmButtonText: '删除' }
  )
  await assistantsApi.deleteAssistant(a.id)
  ElMessage.success('已删除')
  await fetchList()
}

onMounted(fetchList)
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  padding: var(--space-sm) var(--space-md);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--space-sm);
  min-height: var(--space-2xl);
}

.app-card {
  border-radius: var(--radius-lg);
  display: flex;
  flex-direction: column;
}

.app-card :deep(.el-card__body) {
  padding: var(--space-md) var(--space-lg);
  height: 100%;
  display: flex;
  flex-direction: column;
}

.ac-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-xs);
}

.ac-icon {
  font-size: var(--text-xl);
  line-height: 1;
  flex-shrink: 0;
}

.ac-title {
  flex: 1;
  min-width: 0;
}

.ac-name-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

/* 应用名不截断 —— 介绍有两行 clamp，名字必须完整 */
.ac-name {
  min-width: 0;
}

/*
  介绍两行截断：用宿主全局的 .ellipsis-2，不在本地重定义 line-clamp。
  前一轮曾因编排样式表懒加载导致 .ellipsis-2 失效而在本地手写过 ——
  现在宿主 index.css 已经提供，直接用。
*/
.ac-desc {
  margin-top: var(--space-3xs);
  line-height: var(--leading-tight);
  word-break: break-word;
}

/* 类型标签是分类信息不是状态 —— 中性 tag，不上彩色 */
.ac-type-tag {
  color: var(--color-muted);
  border-color: var(--color-rule);
  background: var(--color-sunken);
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
}

/* 类型标签与「内置」并列，但拉开距离，避免混在一块看不清 */
.ac-tags {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  flex-shrink: 0;
}

.ac-facts {
  margin: var(--space-sm) 0 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
  flex: 1;
}

/* 两组：身份（模型/知识库 或 节点/版本）与计量（用量/满意度）之间留出组间距 */
.fact-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.fact-group + .fact-group {
  margin-top: var(--space-2xs);
  padding-top: var(--space-2xs);
  border-top: 1px solid var(--color-rule-2);
}

.fact {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  font-size: var(--text-xs);
  line-height: var(--leading-tight);
}

.fact dt {
  width: 4.5em;
  flex-shrink: 0;
  color: var(--color-muted);
}

.fact dd {
  margin: 0;
  min-width: 0;
  color: var(--color-ink-2);
}

.ac-actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
  padding-top: var(--space-xs);
  border-top: 1px solid var(--color-rule-2);
}

.empty {
  grid-column: 1 / -1;
}

.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
