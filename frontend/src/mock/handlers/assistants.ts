import { ApiError, ErrorCode } from '@/api/types'
import type {
  AccessCredential,
  ApiKeyItem,
  Assistant,
  AssistantBundleConfig,
  AssistantOrigin,
  AssistantType,
  BundleCheckResult,
  BundleDependency,
  PublishCheckResult,
  UpsertAssistantBody
} from '@/api/assistants'
import type { WorkflowGraph } from '@/api/workflow'
import type { Grant, ResourceVisibility } from '@/api/types'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { apiKeys, assistants, credentials, sampleBundleDeps, workflowDrafts } from '../fixtures/assistants'
import { models } from '../fixtures/models'
import { cannedAnswer } from '../fixtures/canned'
import { promptConfigs } from '../fixtures/prompts'
import { roles, users } from '../fixtures/identity'
import { PERM } from '@/constants/permissions'
import { currentSubjects, requireVisible, selfId, visiblePredicate } from '../policy'

/**
 * 收藏是**按用户**的会话态。mock 存内存 —— 刷新即重置，与真实后端的持久化不同。
 * 键是 userId，值是收藏的 appId 集合。
 */
const favoritesByUser = new Map<string, Set<string>>()

const favSet = (token: string): Set<string> => {
  const uid = selfId(currentSubjects(token)) ?? token
  let s = favoritesByUser.get(uid)
  if (!s) {
    s = new Set()
    favoritesByUser.set(uid, s)
  }
  return s
}

/**
 * 单条取数与**写操作前置**共用同一个判定（07 §3.3 硬规矩 1）。
 * 看不见一律 404（硬规矩 3），不返回 403 —— 403 等于告诉对方"这个 id 存在"。
 */
const findAssistant = (id: string, token: string, action: string = PERM.assistantView) =>
  requireVisible(
    assistants.find((a) => a.id === id),
    currentSubjects(token),
    action,
    '应用',
    id
  )

const modelNameOf = (modelId: string) =>
  models.find((m) => m.id === modelId)?.name ?? ''

/**
 * 响应装饰：补 `ownerName`（#8）。广场卡片要显示「由谁分享」，只有 ownerId 这个 id
 * 是显示不出来的。在**响应边界**做，不动夹具本体 —— 归属的真相源仍是 `ownerId`。
 */
const withOwnerName = (app: Assistant): Assistant => {
  if (!app.ownerId) return app
  const name = users.find((u) => u.id === app.ownerId)?.displayName
  return name ? { ...app, ownerName: name } : app
}

/** 响应装饰：再补 `favorited`（当前用户是否已收藏） */
const withUserMeta = (app: Assistant, token: string): Assistant => ({
  ...withOwnerName(app),
  favorited: favSet(token).has(app.id)
})

/**
 * 发布前校验（AS-I2~I5）。任一项不通过则不允许发布。
 *
 * 两档类型的校验面不同：
 *  - 简单应用：模型 / 知识库 / 角色设定 / 检索参数 / 开场白（提示词约束这一套）
 *  - 编排应用：跳过提示词与检索参数相关项（配置在流程节点里，应用壳上没有）；
 *    改为校验「流程已配置」——空画布无法对外服务，这是编排应用真正的发布门槛。
 */
const runPublishCheck = (app: Assistant): PublishCheckResult => {
  const items: PublishCheckResult['items'] = []

  const model = models.find((m) => m.id === app.modelId)
  items.push({
    key: 'model',
    label: '对话模型已加载',
    passed: !!model && model.status === '已加载',
    detail:
      !model
        ? '未选择对话模型。请选择一个「已加载」的对话模型'
        : model.status !== '已加载'
          ? `模型「${model.name}」当前状态为「${model.status}」，请先在模型管理中加载完成`
          : null
  })

  if (app.type === 'workflow') {
    /*
     * 编排应用**不查应用级模型** —— 它压根没有这个字段（见 api/assistants.ts）。
     * 改为逐个检查流程里 AI 类节点的模型：这才是"这条流程能不能跑"的真实前提。
     */
    const graph = workflowDrafts[app.id]
    const nodes = graph?.nodes ?? []
    const graphModelIds = nodes
      .filter((n) => n.type === 'ai-chat-node' || n.type === 'question-node')
      .map((n) => (n.config as { modelId?: string | null } | undefined)?.modelId)
      .filter((id): id is string => !!id)

    const notLoaded = graphModelIds.filter((id) => {
      const m = models.find((x) => x.id === id)
      return !m || m.status !== '已加载'
    })
    items.push({
      key: 'node-models',
      label: '流程内的模型均已加载',
      passed: notLoaded.length === 0,
      detail: notLoaded.length
        ? `流程里有 ${notLoaded.length} 个节点引用的模型不可用（${notLoaded.join('、')}），请先加载或改选`
        : null
    })

    const hasStart = nodes.some((n) => n.type === 'start-node')
    const hasReply = nodes.some((n) => n.type === 'reply-node')
    const connected = (graph?.edges.length ?? 0) > 0
    const passed = nodes.length >= 2 && hasStart && hasReply && connected
    items.push({
      key: 'workflow',
      label: '流程已配置',
      passed,
      detail: passed
        ? null
        : !nodes.length
          ? '尚未绘制流程。请进入「编排」画布至少配置开始节点与回复节点'
          : !hasStart
            ? '流程缺少开始节点，无法作为入口'
            : !hasReply
              ? '流程缺少回复节点，无法向用户输出结果'
              : '节点之间尚未连线，请把流程连成可执行路径'
    })
    return { passed: items.every((i) => i.passed), items }
  }

  const disabledKbs = app.kbIds.filter((id) => id === 'kb-disabled')
  items.push({
    key: 'knowledge',
    label: '知识库均可用',
    passed: disabledKbs.length === 0,
    detail:
      disabledKbs.length > 0
        ? '知识库「采购验收规范」已被停用，将无法参与检索。请启用或移除绑定'
        : null
  })

  items.push({
    key: 'rolePrompt',
    label: '角色设定已填写',
    passed: app.rolePrompt.trim().length > 0,
    detail: app.rolePrompt.trim() ? null : '角色设定为空，回答风格不受控。请填写角色设定或选用一条提示词'
  })

  items.push({
    key: 'retrieval',
    label: '检索参数合法',
    passed: app.retrieval.threshold >= 0 && app.retrieval.threshold <= 1 && app.retrieval.topN >= 1,
    detail:
      app.retrieval.threshold >= 0 && app.retrieval.threshold <= 1 && app.retrieval.topN >= 1
        ? null
        : '阈值需在 0–1 之间，TopN 至少为 1'
  })

  items.push({
    key: 'prologue',
    label: '开场白已填写',
    passed: app.prologue.trim().length > 0,
    detail: app.prologue.trim() ? null : '开场白为空，公开页打开后没有引导语。建议填写一句开场白'
  })

  return { passed: items.every((i) => i.passed), items }
}

const ensureCredentials = (appId: string): AccessCredential[] => {
  if (!credentials[appId]) credentials[appId] = []
  return credentials[appId]
}

const ensureApiKeys = (appId: string): ApiKeyItem[] => {
  if (!apiKeys[appId]) apiKeys[appId] = []
  return apiKeys[appId]
}

const randToken = (prefix: string) =>
  `${prefix}_${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36).slice(-4)}`

export const assistantRoutes: MockRoute[] = [
  [
    'GET',
    '/assistants',
    ({ query, token }) => {
      const subjects = currentSubjects(token)
      const keyword = String(query.keyword ?? '')
      const published = query.published === true || query.published === 'true'
      const type = query.type === 'simple' || query.type === 'workflow' ? (query.type as AssistantType) : null
      // mine=true 只看自己名下的（07 §六 阶段 1 的唯一新增查询参数）
      const mine = query.mine === true || query.mine === 'true'
      const origin =
        query.origin === 'official' || query.origin === 'shared' || query.origin === 'mine'
          ? (query.origin as AssistantOrigin)
          : null
      const me = selfId(subjects)
      // 市场三件套：排序 / 标签 / 只看收藏
      const sort =
        query.sort === 'popular' || query.sort === 'latest' || query.sort === 'name'
          ? (query.sort as 'popular' | 'latest' | 'name')
          : null
      const tag = typeof query.tag === 'string' && query.tag ? query.tag : null
      const favoriteOnly = query.favorite === true || query.favorite === 'true'

      let list = assistants
        // 先在数据层过滤再分页：total 必须是"我看得见的条数"
        .filter(visiblePredicate(subjects, PERM.assistantView, { mine }))
        .filter((a) => !keyword || a.name.includes(keyword) || a.description.includes(keyword))
      if (query.published !== undefined && query.published !== '') {
        list = list.filter((a) => a.published === published)
      }
      if (type) {
        list = list.filter((a) => a.type === type)
      }
      // 来源分段与卡片徽标同一套判据：builtin 优先归「官方」，其余按 ownerId 分流
      if (origin === 'official') {
        list = list.filter((a) => a.builtin)
      } else if (origin === 'mine') {
        list = list.filter((a) => !a.builtin && a.ownerId === me)
      } else if (origin === 'shared') {
        list = list.filter((a) => !a.builtin && a.ownerId !== me)
      }
      if (tag) {
        list = list.filter((a) => (a.tags ?? []).includes(tag))
      }
      if (favoriteOnly) {
        const favs = favSet(token)
        list = list.filter((a) => favs.has(a.id))
      }
      // 排序在过滤后、分页前
      if (sort === 'popular') {
        list = [...list].sort((a, b) => b.callCount - a.callCount)
      } else if (sort === 'latest') {
        list = [...list].sort((a, b) => String(b.createdAt ?? '').localeCompare(String(a.createdAt ?? '')))
      } else if (sort === 'name') {
        list = [...list].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
      }
      const page = paginate(list, query)
      return { ...page, list: page.list.map((a) => withUserMeta(a, token)) }
    }
  ],

  [
    'POST',
    '/assistants',
    ({ body, token }) => {
      const subjects = currentSubjects(token)
      const input = body as UpsertAssistantBody
      // type 创建时必填且此后不可改（SRS 02 / FR-04）
      if (input.type !== 'simple' && input.type !== 'workflow') {
        throw new ApiError(4004, '创建应用必须指定类型', '请选择「简单应用」或「编排应用」。类型创建后不可更改')
      }
      if (assistants.some((a) => a.name === input.name)) {
        throw new ApiError(4002, `应用名称「${input.name}」已存在`)
      }
      const model = models.find((m) => m.id === input.modelId)
      const created: Assistant = {
        id: `as${Date.now()}`,
        name: input.name,
        icon: input.icon,
        description: input.description,
        type: input.type,
        builtin: false,
        published: false,
        modelId: input.modelId,
        modelName: modelNameOf(input.modelId),
        kbIds: input.kbIds,
        rolePrompt: input.rolePrompt,
        promptIds: input.promptIds,
        retrieval: input.retrieval,
        multiTurn: input.multiTurn,
        // ALWAYS_ON / TOGGLEABLE 由模型能力决定，创建时按能力归一
        thinkingEnabled:
          model?.thinking === 'ALWAYS_ON'
            ? true
            : model?.thinking === 'TOGGLEABLE'
              ? input.thinkingEnabled
              : false,
        prologue: input.prologue,
        quickQuestions: input.quickQuestions,
        callCount: 0,
        tokenCount: 0,
        satisfaction: null,
        // 归属由"后端"写，不从请求体读（07 §六 阶段 1）：ownerId 取主体集合里的本人
        ownerId: selfId(subjects),
        visibility: 'private'
      }
      // 引用计数
      for (const pid of created.promptIds) {
        const p = promptConfigs.find((c) => c.id === pid)
        if (p) p.usageCount += 1
      }
      if (created.type === 'workflow' && !workflowDrafts[created.id]) {
        workflowDrafts[created.id] = { nodes: [], edges: [] }
      }
      assistants.unshift(created)
      return withOwnerName(created)
    }
  ],

  // 字面量路径必须排在 :id 参数路由之前，避免 /assistants/import 被吞成 id
  [
    'POST',
    '/assistants/import/check',
    ({ body }) => {
      const input = body as { format: string; dependencies: BundleDependency[] }
      const deps = input.dependencies?.length
        ? input.dependencies
        : input.format?.includes('legacy')
          ? sampleBundleDeps.missing
          : sampleBundleDeps.complete

      const result: BundleCheckResult = {
        format: input.format || 'assistant-bundle@1',
        legacy: Boolean(input.format?.includes('legacy')),
        dependencies: deps,
        hasMissing: deps.some((d) => !d.satisfied)
      }
      return result
    }
  ],

  [
    'POST',
    '/assistants/import',
    ({ body, token }) => {
      const subjects = currentSubjects(token)
      const input = body as {
        bundle: {
          format: string
          config: AssistantBundleConfig
          dependencies: BundleDependency[]
          includesDocuments?: boolean
        }
        skipMissingReferences: boolean
      }
      const missing = (input.bundle.dependencies ?? []).filter((d) => !d.satisfied)
      if (missing.length > 0 && !input.skipMissingReferences) {
        throw new ApiError(
          ErrorCode.PUBLISH_CHECK_FAILED,
          `依赖清单中有 ${missing.length} 项在本环境不存在`,
          '确认降级后果后勾选「跳过缺失引用导入」，或先补齐依赖再导入'
        )
      }
      const config = input.bundle.config
      if (assistants.some((a) => a.name === config.name)) {
        throw new ApiError(4002, `应用名称「${config.name}」已存在，请先改名再导入`)
      }
      // 旧包可能没有 type；缺省按简单应用，避免导入后类型不明
      const importedType: AssistantType = config.type === 'workflow' ? 'workflow' : 'simple'
      const created: Assistant = {
        id: `as${Date.now()}`,
        name: config.name,
        icon: config.icon,
        description: config.description,
        type: importedType,
        builtin: false,
        published: false,
        modelId: config.modelId,
        modelName: modelNameOf(config.modelId),
        kbIds: input.skipMissingReferences ? config.kbIds.filter((id) => !id.startsWith('missing')) : config.kbIds,
        rolePrompt: missing.some((m) => m.kind === '提示词') && input.skipMissingReferences ? '' : config.rolePrompt,
        promptIds: input.skipMissingReferences
          ? config.promptIds.filter((id) => promptConfigs.some((p) => p.id === id))
          : config.promptIds,
        retrieval: config.retrieval,
        multiTurn: config.multiTurn,
        thinkingEnabled: config.thinkingEnabled,
        prologue: config.prologue,
        quickQuestions: config.quickQuestions,
        callCount: 0,
        tokenCount: 0,
        satisfaction: null,
        // 导入也是新建：归属给导入人，默认 private
        ownerId: selfId(subjects),
        visibility: 'private'
      }
      // 编排应用：包里带了流程定义就恢复进草稿，否则建一个空画布（与新建同形）
      if (importedType === 'workflow') {
        const restored: WorkflowGraph = config.workflow ?? { nodes: [], edges: [] }
        workflowDrafts[created.id] = restored
        created.workflow = {
          nodeCount: restored.nodes.length,
          publishedVersion: null
        }
      }
      assistants.unshift(created)
      return withOwnerName(created)
    }
  ],

  // 字面量路径必须排在 :id 参数路由之前（与 /assistants/import 同理）
  [
    'GET',
    '/assistants/tags',
    ({ token }) => {
      const subjects = currentSubjects(token)
      const visible = assistants.filter(visiblePredicate(subjects, PERM.assistantView, {}))
      const set = new Set<string>()
      for (const a of visible) for (const t of a.tags ?? []) set.add(t)
      return [...set].sort((a, b) => a.localeCompare(b, 'zh-CN'))
    }
  ],

  [
    'GET',
    '/assistants/:id',
    ({ path, token }) => withUserMeta(findAssistant(path.id, token), token)
  ],

  [
    'PUT',
    '/assistants/:id',
    ({ path, body, token }) => {
      const app = findAssistant(path.id, token, PERM.assistantEdit)
      if (app.builtin) {
        throw new ApiError(4006, `「${app.name}」是内置应用，对话设定不可编辑`)
      }
      const input = body as Partial<UpsertAssistantBody>
      // 类型创建后不可更改：改类型等于换了一个应用，应新建
      if (input.type !== undefined && input.type !== app.type) {
        throw new ApiError(
          4003,
          '应用类型创建后不可更改；如需改为编排/简单，请新建应用',
          `当前是「${app.type === 'workflow' ? '编排' : '简单'}」应用`
        )
      }
      if (input.name && input.name !== app.name && assistants.some((a) => a.name === input.name)) {
        throw new ApiError(4002, `应用名称「${input.name}」已存在`)
      }

      // 提示词引用计数先减后加
      if (input.promptIds) {
        for (const pid of app.promptIds) {
          const p = promptConfigs.find((c) => c.id === pid)
          if (p) p.usageCount = Math.max(0, p.usageCount - 1)
        }
        for (const pid of input.promptIds) {
          const p = promptConfigs.find((c) => c.id === pid)
          if (p) p.usageCount += 1
        }
        app.promptIds = input.promptIds
      }

      if (input.name) app.name = input.name
      if (input.icon) app.icon = input.icon
      if (input.description !== undefined) app.description = input.description
      if (input.kbIds) app.kbIds = input.kbIds
      if (input.rolePrompt !== undefined) app.rolePrompt = input.rolePrompt
      if (input.retrieval) app.retrieval = input.retrieval
      if (input.multiTurn !== undefined) app.multiTurn = input.multiTurn
      if (input.prologue !== undefined) app.prologue = input.prologue
      if (input.quickQuestions) app.quickQuestions = input.quickQuestions

      if (input.modelId && input.modelId !== app.modelId) {
        app.modelId = input.modelId
        app.modelName = modelNameOf(input.modelId)
        const model = models.find((m) => m.id === input.modelId)
        // 换模型时思考开关按新模型能力归一（M-I3～M-I6）
        if (model?.thinking === 'ALWAYS_ON') app.thinkingEnabled = true
        else if (model?.thinking === 'NONE') app.thinkingEnabled = false
        else app.thinkingEnabled = input.thinkingEnabled ?? false
      } else if (input.thinkingEnabled !== undefined) {
        const model = models.find((m) => m.id === app.modelId)
        if (model?.thinking === 'ALWAYS_ON') app.thinkingEnabled = true
        else if (model?.thinking === 'NONE') app.thinkingEnabled = false
        else app.thinkingEnabled = input.thinkingEnabled
      }

      return withOwnerName(app)
    }
  ],

  [
    'DELETE',
    '/assistants/:id',
    ({ path, token }) => {
      const app = findAssistant(path.id, token, PERM.assistantEdit)
      if (app.builtin) {
        throw new ApiError(4006, `「${app.name}」是内置应用，不可删除`)
      }
      for (const pid of app.promptIds) {
        const p = promptConfigs.find((c) => c.id === pid)
        if (p) p.usageCount = Math.max(0, p.usageCount - 1)
      }
      assistants.splice(assistants.indexOf(app), 1)
      delete credentials[app.id]
      delete apiKeys[app.id]
      return undefined
    }
  ],

  [
    'GET',
    '/assistants/:id/publish-check',
    ({ path, token }) => runPublishCheck(findAssistant(path.id, token))
  ],

  [
    'PATCH',
    '/assistants/:id/published',
    ({ path, body, token }) => {
      const app = findAssistant(path.id, token, PERM.assistantPublish)
      const { published } = body as { published: boolean }
      if (published) {
        const check = runPublishCheck(app)
        if (!check.passed) {
          const failed = check.items.filter((i) => !i.passed)
          throw new ApiError(
            ErrorCode.PUBLISH_CHECK_FAILED,
            `发布前校验未通过：${failed.map((i) => i.label).join('、')}`,
            failed[0]?.detail ?? '请按校验项逐条修复后再发布'
          )
        }
      }
      app.published = published
      return withOwnerName(app)
    }
  ],

  /* ── 分享写（07 §3.1 / §8.1）：可见性与授权是两个写口，分开落 ── */

  [
    'PATCH',
    '/assistants/:id/visibility',
    ({ path, body, token }) => {
      const app = findAssistant(path.id, token, PERM.assistantEdit)
      if (app.builtin) {
        throw new ApiError(4006, `「${app.name}」是内置应用，可见性由平台统一维护`)
      }
      const { visibility } = body as { visibility: ResourceVisibility }
      if (visibility !== 'private' && visibility !== 'shared' && visibility !== 'public') {
        throw new ApiError(4004, '可见性只能是 private / shared / public')
      }
      app.visibility = visibility
      return withOwnerName(app)
    }
  ],

  [
    'PUT',
    '/assistants/:id/grants',
    ({ path, body, token }) => {
      const app = findAssistant(path.id, token, PERM.assistantEdit)
      if (app.builtin) {
        throw new ApiError(4006, `「${app.name}」是内置应用，可见性由平台统一维护`)
      }
      const { grants } = body as { grants: Grant[] }
      if (!Array.isArray(grants)) {
        throw new ApiError(4004, 'grants 必须是数组')
      }
      // 主体必须真实存在：写错 id 的授权等于静默没共享出去（对方看不到，自己以为共享了）
      for (const g of grants) {
        const kind = g.subject?.kind
        const id = g.subject?.id
        if ((kind !== 'user' && kind !== 'role') || !id) {
          throw new ApiError(4004, '共享主体只支持账号（user）与角色（role）')
        }
        const exists =
          kind === 'user' ? users.some((u) => u.id === id) : roles.some((r) => r.id === id)
        if (!exists) {
          throw new ApiError(4004, `共享主体不存在：${kind} ${id}`)
        }
        if (!Array.isArray(g.actions) || g.actions.length === 0) {
          throw new ApiError(4004, '每条授权至少要有一个动作')
        }
      }
      app.grants = grants.map((g) => ({ subject: { ...g.subject }, actions: [...g.actions] }))
      return withOwnerName(app)
    }
  ],

  /* ── 收藏（市场）。幂等：重复收藏/取消不报错 ── */

  [
    'POST',
    '/assistants/:id/favorite',
    ({ path, token }) => {
      const app = findAssistant(path.id, token)
      favSet(token).add(app.id)
      return withUserMeta(app, token)
    }
  ],

  [
    'DELETE',
    '/assistants/:id/favorite',
    ({ path, token }) => {
      const app = findAssistant(path.id, token)
      favSet(token).delete(app.id)
      return withUserMeta(app, token)
    }
  ],

  [
    'POST',
    '/assistants/:id/debug',
    ({ path, body, token }) => {
      const app = findAssistant(path.id, token)
      const { question } = body as { question: string }
      if (!question?.trim()) {
        throw new ApiError(4004, '调试问题不能为空')
      }
      // 调试使用当前草稿配置，不产生正式会话记录
      const withRefs = app.kbIds.length > 0
      const src = withRefs ? cannedAnswer.withRefs : cannedAnswer.withoutRefs
      return {
        answer: src.text,
        refs: src.refs,
        elapsedMs: 3800,
        tokens: 1120
      }
    }
  ],

  /* ── 访问凭证 ── */

  [
    'GET',
    '/assistants/:appId/credentials',
    ({ path, token }) => {
      findAssistant(path.appId, token)
      return ensureCredentials(path.appId)
    }
  ],

  [
    'POST',
    '/assistants/:appId/credentials',
    ({ path, body, token }) => {
      findAssistant(path.appId, token, PERM.assistantEdit)
      const { expiresAt } = body as { expiresAt: string | null }
      const created: AccessCredential = {
        id: `cr${Date.now()}`,
        token: randToken('pk_live'),
        enabled: true,
        expiresAt: expiresAt ?? null,
        accessCount: 0,
        lastAccessAt: null,
        revokedAt: null,
        createdAt: new Date().toISOString()
      }
      ensureCredentials(path.appId).unshift(created)
      return created
    }
  ],

  [
    'PATCH',
    '/assistants/:appId/credentials/:id/enabled',
    ({ path, body, token }) => {
      findAssistant(path.appId, token, PERM.assistantEdit)
      const list = ensureCredentials(path.appId)
      const item = list.find((c) => c.id === path.id)
      if (!item) throw new ApiError(ErrorCode.NOT_FOUND, '访问凭证不存在')
      // 撤销是终态：不可重新启用
      if (item.revokedAt) {
        throw new ApiError(4005, '该凭证已撤销，无法重新启用', '撤销是终态。请重新生成一个新凭证')
      }
      const { enabled } = body as { enabled: boolean }
      item.enabled = enabled
      return item
    }
  ],

  [
    'POST',
    '/assistants/:appId/credentials/:id/revoke',
    ({ path, token }) => {
      findAssistant(path.appId, token, PERM.assistantEdit)
      const list = ensureCredentials(path.appId)
      const item = list.find((c) => c.id === path.id)
      if (!item) throw new ApiError(ErrorCode.NOT_FOUND, '访问凭证不存在')
      if (item.revokedAt) {
        throw new ApiError(4005, '该凭证已撤销，无需重复撤销')
      }
      item.revokedAt = new Date().toISOString()
      item.enabled = false
      return item
    }
  ],

  /* ── API Key（与访问凭证是两套机制） ── */

  [
    'GET',
    '/assistants/:appId/api-keys',
    ({ path, token }) => {
      findAssistant(path.appId, token)
      return ensureApiKeys(path.appId)
    }
  ],

  [
    'POST',
    '/assistants/:appId/api-keys',
    ({ path, token }) => {
      findAssistant(path.appId, token, PERM.assistantEdit)
      const created: ApiKeyItem = {
        id: `ak${Date.now()}`,
        key: randToken('sk-ai'),
        enabled: true,
        createdAt: new Date().toISOString(),
        lastUsedAt: null
      }
      ensureApiKeys(path.appId).unshift(created)
      return created
    }
  ],

  // 单条启停（与凭证面板的 …/credentials/:id/enabled 同形）。停用是可逆的，不是终态
  [
    'PATCH',
    '/assistants/:appId/api-keys/:id/enabled',
    ({ path, body, token }) => {
      findAssistant(path.appId, token, PERM.assistantEdit)
      const list = ensureApiKeys(path.appId)
      const item = list.find((k) => k.id === path.id)
      if (!item) throw new ApiError(ErrorCode.NOT_FOUND, 'API Key 不存在')
      const { enabled } = body as { enabled: boolean }
      item.enabled = enabled
      return item
    }
  ],

  /* ── 导入导出 ── */

  [
    'GET',
    '/assistants/:id/export',
    ({ path, query, token }) => {
      const app = findAssistant(path.id, token)
      const includeDocuments = query.includeDocuments === true || query.includeDocuments === 'true'
      const useMissing = query.demoMissing === true || query.demoMissing === 'true'
      /*
       * 依赖项里的「模型」：简单应用取应用级模型；**编排应用没有它** ⇒ 列流程里
       * AI 节点用到的模型（去重）。
       * 编排应用另加一档「流程」：画布定义随包走，否则导入后流程是空的（P1）。
       */
      const graph = app.type === 'workflow' ? (workflowDrafts[app.id] ?? { nodes: [], edges: [] }) : null
      const modelNames =
        app.type === 'workflow'
          ? [
              ...new Set(
                (graph?.nodes ?? [])
                  .filter((n) => n.type === 'ai-chat-node' || n.type === 'question-node')
                  .map((n) => (n.config as { modelId?: string | null } | undefined)?.modelId)
                  .filter((id): id is string => !!id)
                  .map((id) => models.find((m) => m.id === id)?.name ?? id)
              )
            ]
          : [app.modelName ?? '—']
      const dependencies: BundleDependency[] = useMissing
        ? sampleBundleDeps.missing
        : [
            {
              kind: '模型',
              name: modelNames.join('、'),
              detail: '本地推理 · 已加载',
              satisfied: true,
              degrade: null
            },
            ...app.kbIds.map(
              (id): BundleDependency => ({
                kind: '知识库',
                name: id === 'kb1' ? '合同管理制度' : id === 'kb2' ? '采购验收规范' : '报销制度',
                detail: id === 'kb1' ? '128 个文档 · 412 个分段' : '36 个文档 · 180 个分段',
                satisfied: true,
                degrade: null
              })
            ),
            ...app.promptIds.map((id): BundleDependency => {
              const p = promptConfigs.find((c) => c.id === id)
              return {
                kind: '提示词',
                name: p?.roleName ?? id,
                detail: p ? `${p.typeName} · 被 ${p.usageCount} 个应用引用` : '未知提示词',
                satisfied: !!p,
                degrade: p ? null : '跳过后使用空角色设定'
              }
            }),
            // 流程档只在编排应用出现；简单应用的清单里不该有它
            ...(graph
              ? [
                  {
                    kind: '流程' as const,
                    name: `${graph.nodes.length} 个节点 · ${graph.edges.length} 条连线`,
                    detail: '画布流程定义，随包导出',
                    satisfied: true,
                    degrade: null
                  }
                ]
              : [])
          ]

      const config: AssistantBundleConfig = {
        name: app.name,
        icon: app.icon,
        description: app.description,
        type: app.type,
        modelId: app.modelId ?? '',
        kbIds: app.kbIds,
        rolePrompt: app.rolePrompt,
        promptIds: app.promptIds,
        retrieval: app.retrieval,
        multiTurn: app.multiTurn,
        thinkingEnabled: app.thinkingEnabled,
        prologue: app.prologue,
        quickQuestions: app.quickQuestions,
        // 编排应用带流程定义；简单应用不写这个字段
        ...(graph ? { workflow: graph } : {})
      }

      return {
        format: 'assistant-bundle@1',
        exportedAt: new Date().toISOString(),
        includesDocuments: includeDocuments,
        config,
        dependencies
      }
    }
  ]
]
