<template>
  <div class="page">
    <PageHeader
      :title="consumer ? '应用广场' : '应用管理'"
      :desc="consumer
        ? '这些应用可以直接用来对话。挑一个开始，引用与反馈都在对话页里'
        : '应用把模型、知识库与提示词组装成可发布的对话服务。简单应用看模型 / 知识库数，编排应用看节点数 / 版本；另有发布态 / 用量 / 满意度'"
    >
      <template #actions>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        <el-button
          v-if="!consumer && can(PERM.assistantEdit)"
          size="small"
          type="primary"
          @click="openCreate"
        >新建应用</el-button>
      </template>
    </PageHeader>

    <!--
      操作说明是**建设语汇**的密集地（简单/编排、配置/画布/发布/校验/内置不可改）——
      对消费者它整块不出现（07 §五 判据 1），而不是留一段读不懂的说明。
    -->
    <div v-if="!consumer" class="notice info">
      操作说明：应用分<b>简单</b>（提示词约束）与<b>编排</b>（画布式流程）两档，<b>创建后类型不可改</b>。
      简单应用点<b>配置</b>打开左表单 + 右调试；编排应用点<b>编排</b>进入流程画布（对编排应用而言配置就是画布，所以只有一个入口）；
      <b>概览</b>看用量、公开凭证、API Key 与导入导出；<b>演示</b>进入对话试效果；<b>发布</b>先跑发布前校验。
      内置应用不可改对话设定。
    </div>

    <div class="filters panel">
      <!--
        来源分段（§六 1）：全部 / 官方 / 共享给我 / 我的。
        与卡片徽标同一套判据 —— builtin 优先归「官方」，其余按 ownerId 分流。
        服务端在分页前过滤（`origin` 查询参数），否则 total 与翻页会错位。
      -->
      <el-radio-group v-model="query.origin" size="small" @change="onFilterChange">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="official">官方</el-radio-button>
        <el-radio-button value="shared">共享给我</el-radio-button>
        <el-radio-button value="mine">我的</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索应用名称或描述"
        style="width: 230px"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <!-- 状态（草稿/已发布）与类型（简单/编排）是建设面的分类，消费者不筛这两个 -->
      <el-select
        v-if="!consumer"
        v-model="query.published"
        size="small"
        placeholder="全部状态"
        style="width: 130px"
        @change="onFilterChange"
      >
        <el-option label="全部状态" value="" />
        <el-option label="已发布" value="true" />
        <el-option label="草稿" value="false" />
      </el-select>
      <el-select
        v-if="!consumer"
        v-model="query.type"
        size="small"
        placeholder="全部类型"
        style="width: 130px"
        @change="onFilterChange"
      >
        <el-option label="全部类型" value="" />
        <el-option label="简单" value="simple" />
        <el-option label="编排" value="workflow" />
      </el-select>
      <span class="micro muted">共 {{ total }} 个</span>
    </div>

    <!--
      失败与空数据必须能分清（本模块 P1：列表请求无 catch ⇒ 被渲染成「还没有应用」）。
      错误态就地给原因与重试，不落进空态文案。
    -->
    <div v-if="loadError" class="notice err load-error">
      <div><b>加载失败：</b>{{ loadError }}</div>
      <el-button size="small" @click="fetchList">重试</el-button>
    </div>

    <div v-else v-loading="loading" class="grid">
      <el-card
        v-for="a in list"
        :id="`app-card-${a.id}`"
        :key="a.id"
        shadow="never"
        class="app-card"
        :class="{ plaza: consumer, focused: highlightId === a.id }"
      >
        <div class="ac-head">
          <span class="ac-icon">{{ a.icon }}</span>
          <div class="ac-title">
            <div class="ac-name-row">
              <span class="h3 ac-name">{{ a.name }}</span>
              <span class="ac-tags">
                <el-tag v-if="!consumer" size="small" effect="plain" class="ac-type-tag">
                  {{ a.type === 'workflow' ? '编排' : '简单' }}
                </el-tag>
                <!--
                  来源徽标三态（§六 2 / #8）：官方 / 由 X 分享 / 我的。
                  对消费者同样显示 —— 广场要能回答「这应用是谁给的」。
                -->
                <el-tag v-if="originOf(a) === 'official'" size="small" effect="plain" type="info">官方</el-tag>
                <el-tag v-else-if="originOf(a) === 'mine'" size="small" effect="plain" type="success">我的</el-tag>
                <el-tag v-else size="small" effect="plain" type="warning">
                  由 {{ a.ownerName || '他人' }} 分享
                </el-tag>
              </span>
            </div>
            <div class="micro muted ellipsis-2 ac-desc" :title="a.description">{{ a.description }}</div>
          </div>
          <el-tag v-if="!consumer" size="small" effect="plain" :type="a.published ? 'success' : 'info'">
            {{ a.published ? '已发布' : '草稿' }}
          </el-tag>
        </div>

        <!--
          「模型 / 知识库数」是配置面，「用量 / 满意度」是运营口径 ——
          对消费者两者都不是他要看的东西（他要的是「哪个能用」）。
          所以消费者形态下整块读数不渲染，卡片只剩身份与一个动作。
        -->
        <dl v-if="!consumer" class="ac-facts">
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
            主操作按来源分化（§六 3）：
              官方 / 他人分享 → 只能「开始对话」
              我的            → 配置（编排）/ 分享 / 概览 / 发布 / 删除
            内置应用即使归属我也是「官方」—— 配置项只读，分享由平台统一维护。
            消费者形态下管理动作一律不出现，卡片只剩「开始对话」。
          -->
          <el-button
            v-if="showConfig(a)"
            link size="small"
            type="primary"
            @click="openConfig(a)"
          >{{ a.type === 'workflow' ? '编排' : '配置' }}</el-button>
          <el-button
            v-if="showShare(a)"
            link size="small"
            @click="openShare(a)"
          >分享</el-button>
          <el-button v-if="showOverview(a)" link size="small" @click="openOverview(a)">概览</el-button>
          <el-button
            link
            size="small"
            :type="originOf(a) === 'mine' && !consumer ? undefined : 'primary'"
            @click="demo(a)"
          >{{ originOf(a) === 'mine' && !consumer ? '演示' : '开始对话' }}</el-button>
          <el-button
            v-if="showPublish(a)"
            link size="small"
            :type="a.published ? 'warning' : 'success'"
            @click="togglePublish(a)"
          >{{ a.published ? '下架' : '发布' }}</el-button>
          <span class="grow" />
          <el-button
            v-if="showDelete(a)"
            link size="small" type="danger"
            @click="remove(a)"
          >删除</el-button>
        </div>
      </el-card>

      <div v-if="!loading && !list.length" class="empty panel">
        <div class="empty-title">
          {{ emptyTitle }}
        </div>
        <p class="micro muted">{{ emptyHint }}</p>
        <el-button
          v-if="!consumer && can(PERM.assistantEdit)"
          size="small" type="primary"
          @click="openCreate"
        >新建应用</el-button>
      </div>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="onPageChange"
      />
    </div>

    <AssistantCreateDialog ref="createRef" @created="fetchList" @saved="fetchList" />
    <AssistantConfigDrawer ref="configRef" :assistant="current" @saved="fetchList" @published="fetchList" />
    <AssistantOverviewDrawer ref="overviewRef" :assistant="current" @saved="fetchList" />
    <ShareDialog ref="shareRef" :assistant="current" @saved="fetchList" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { assistantsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Assistant, AssistantOrigin } from '@/api/assistants'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import PageHeader from '@/components/PageHeader.vue'
import AssistantCreateDialog from './components/AssistantCreateDialog.vue'
import AssistantConfigDrawer from './components/AssistantConfigDrawer.vue'
import AssistantOverviewDrawer from './components/AssistantOverviewDrawer.vue'
import ShareDialog from './components/ShareDialog.vue'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()
const can = (code: string) => session.can(code)

/**
 * 消费者形态（07 §五）：数据范围 `shared` = 只用别人做好的，不建东西。
 *
 * 判据来自 `session.dataScope` **一处**，页面只据此换形态（标题/说明/动作集），
 * 不在这里另判权限点 —— 权限点回答「能不能做这个动作」，范围回答「这个页面对他是什么」。
 * 两者会同时为真：消费者本来也没有 `assistant:edit`，但只靠权限点的话，
 * 页面仍然是「应用管理」那张建设面，只是按钮少几个 —— 那正是本册要治的病。
 *
 * ★ 这只是 UX。真正的数据边界在后端按 `dataScope` 过滤（07 §3.3），
 * 前端少渲染一个按钮不等于后端会拒绝直连接口。
 */
const consumer = computed(() => session.dataScope === 'shared')

const list = ref<Assistant[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const pageSize = 12
const current = ref<Assistant | null>(null)

/**
 * 列表筛选/分页入 URL（本模块 P1：刷新即丢）。
 * 初值从 route.query 恢复，改动后 replace 回写 —— 用 replace 不用 push，
 * 翻页不该把浏览器历史塞满。
 */
const readQueryFromUrl = () => {
  const q = route.query
  return {
    keyword: typeof q.keyword === 'string' ? q.keyword : '',
    published: (typeof q.published === 'string' ? q.published : '') as '' | 'true' | 'false',
    type: (typeof q.type === 'string' ? q.type : '') as '' | 'simple' | 'workflow',
    origin: (typeof q.origin === 'string' ? q.origin : '') as '' | AssistantOrigin,
    page: Number(q.page) > 0 ? Number(q.page) : 1
  }
}

const query = reactive(readQueryFromUrl())

const syncQueryToUrl = () => {
  router.replace({
    query: {
      ...(query.keyword ? { keyword: query.keyword } : {}),
      ...(query.published ? { published: query.published } : {}),
      ...(query.type ? { type: query.type } : {}),
      ...(query.origin ? { origin: query.origin } : {}),
      ...(query.page > 1 ? { page: String(query.page) } : {})
    }
  })
}

const hasFilter = computed(() =>
  Boolean(query.keyword || query.published || query.type || query.origin)
)

/* ── 来源三态（§六）：与卡片徽标、分段筛选同一套判据 ── */

const meId = computed(() => session.user?.id ?? '')

/**
 * builtin 优先归「官方」—— 内置应用即使归属我也是平台资产，不给配置/分享入口。
 * 其余按 ownerId 分流：我的 / 他人分享。
 */
const originOf = (a: Assistant): AssistantOrigin => {
  if (a.builtin) return 'official'
  return a.ownerId && a.ownerId === meId.value ? 'mine' : 'shared'
}

// 主操作按来源分化（§六 3）：官方与他人分享只有「开始对话」，我的才有配置/分享/概览
const showConfig = (a: Assistant) =>
  !consumer.value && can(PERM.assistantEdit) && originOf(a) === 'mine'
const showShare = (a: Assistant) =>
  !consumer.value && can(PERM.assistantEdit) && originOf(a) === 'mine'
const showOverview = (a: Assistant) => !consumer.value && originOf(a) === 'mine'
const showPublish = (a: Assistant) =>
  !consumer.value && can(PERM.assistantPublish) && originOf(a) === 'mine'
const showDelete = (a: Assistant) =>
  !consumer.value && can(PERM.assistantEdit) && originOf(a) === 'mine'

const emptyTitle = computed(() => {
  if (consumer.value) return '暂时没有可用的应用'
  if (hasFilter.value) return '当前筛选下没有应用'
  return '还没有创建任何应用'
})

const emptyHint = computed(() => {
  if (consumer.value) return '已经发布的应用会出现在这里，可以拿它直接对话'
  if (hasFilter.value) return '换个关键词或清空筛选试试'
  return '创建第一个应用，开始组装对话服务'
})

const createRef = ref<InstanceType<typeof AssistantCreateDialog>>()
const configRef = ref<InstanceType<typeof AssistantConfigDrawer>>()
const overviewRef = ref<InstanceType<typeof AssistantOverviewDrawer>>()
const shareRef = ref<InstanceType<typeof ShareDialog>>()

const onFilterChange = () => {
  query.page = 1
  syncQueryToUrl()
  fetchList()
}

const onPageChange = () => {
  syncQueryToUrl()
  fetchList()
}

const fetchList = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const result = await assistantsApi.listAssistants({
      keyword: query.keyword || undefined,
      // 消费者形态默认只列已发布（§六 5）：共享的草稿不该进广场
      published: consumer.value
        ? true
        : query.published === ''
          ? undefined
          : query.published === 'true',
      type: query.type || undefined,
      origin: query.origin || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    // 失败必须能与「没有数据」分清（本模块 P1）：清空列表 + 就地错误态 + 可重试
    list.value = []
    total.value = 0
    loadError.value =
      error instanceof ApiError
        ? `${error.message}${error.hint ? '。' + error.hint : ''}`
        : '应用列表加载失败，请检查网络后重试'
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

const openShare = (a: Assistant) => {
  current.value = a
  // 直接把 a 传进去：current 刚赋值，props 到子组件可能还没刷新
  shareRef.value?.open(a)
}

const demo = (a: Assistant) => {
  router.push({ path: '/conversation', query: { appId: a.id } })
}

const togglePublish = async (a: Assistant) => {
  if (a.published) {
    // 取消是正常路径，不是错误：用 then/两个回调区分「确认」与「取消」，
    // 不让取消落成 unhandled rejection（本模块 P1）
    const confirmed = await ElMessageBox.confirm(
      `下架后公开链接将失效。确认下架「${a.name}」？`,
      '确认下架',
      { type: 'warning', confirmButtonText: '下架' }
    ).then(
      () => true,
      () => false
    )
    if (!confirmed) return
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
  const confirmed = await ElMessageBox.confirm(
    `删除后公开链接与 API Key 一并作废，且不可恢复。确认删除「${a.name}」？`,
    '确认删除应用',
    { type: 'warning', confirmButtonText: '删除' }
  ).then(
    () => true,
    () => false
  )
  if (!confirmed) return
  try {
    await assistantsApi.deleteAssistant(a.id)
    ElMessage.success('已删除')
    await fetchList()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

/**
 * 对话页顶栏跳过来时带 `?focus=<appId>`（`conversation/TopBar.vue`），
 * 要求落到**那一个应用**而不是全量列表。做法：加载后滚到它的卡片并短暂高亮；
 * 若它被当前筛选滤掉了，清空筛选再找一次。
 */
const focusId = ref('')
const highlightId = ref('')

const applyFocus = async () => {
  if (!focusId.value) return
  const id = focusId.value
  let found = list.value.some((a) => a.id === id)
  if (!found && hasFilter.value) {
    query.keyword = ''
    query.published = ''
    query.type = ''
    query.origin = ''
    query.page = 1
    syncQueryToUrl()
    await fetchList()
    found = list.value.some((a) => a.id === id)
  }
  if (!found) {
    ElMessage.info('目标应用不在当前列表中，可能已删除或你没有查看权限')
    return
  }
  await nextTick()
  highlightId.value = id
  document.getElementById(`app-card-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  window.setTimeout(() => {
    highlightId.value = ''
  }, 2600)
}

onMounted(async () => {
  const focus = route.query.focus
  focusId.value = typeof focus === 'string' ? focus : ''
  await fetchList()
  await applyFocus()
})
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

/* `?focus=` 定位到的那张卡：滚动到视野内后短暂描边，让人看见「落在了哪一个」 */
.app-card.focused {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
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

/* 应用广场的卡片没有读数块（那才是建设者要的），得让**卡片头**把剩余高度吃掉 ——
   否则同一行里描述长短不一会让「开始对话」跳来跳去。 */
.app-card.plaza .ac-head {
  flex: 1;
}

.empty {
  grid-column: 1 / -1;
}

/* 错误态与空态分开：加载失败不许伪装成「还没有应用」 */
.load-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-xs);
}

.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
