import { ApiError, ErrorCode } from '@/api/types'
import type { ChatMessage, ShareScope } from '@/api/conversations'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import {
  appBriefs,
  publicCredentials
} from '../fixtures/conversations'
import { assistants, credentials as assistantCredentials } from '../fixtures/assistants'
import { cannedAnswer } from '../fixtures/canned'
import { documents, knowledgeBases, segments } from '../fixtures/knowledge'
import {
  addConversation,
  appendTurn,
  conversations,
  createShare,
  findConversation,
  findMessage,
  listShares,
  messagesOf,
  messagesOfShare,
  findShareByToken,
  patchConversation,
  publicMessages,
  removeConversation,
  revokeShare,
  toShareView,
  type StoredConversation
} from '../chat-store'
import {
  currentSubjects,
  requireVisible,
  selfId,
  visiblePredicate,
  type SubjectSet
} from '../policy'

/**
 * 会话域 mock。
 *
 * 只实现 src/api/conversations.ts 冻结契约里的端点。
 * 数据本体在 `../chat-store`：那是**可变**的运行时存储（镜像 localStorage），
 * 而 fixtures 只是种子 —— 所以刷新页面后本轮问答仍在（#4）。
 *
 * 数据权限（07 §4.2「会话：只有自己的」）：
 *  - 读会话按 `conversation:view` 问（会话族权限点；此前借用 `assistant:view`）
 *  - 会话夹具全部 `private` ⇒ 实际由「归属 + 数据范围」两项决定：全平台档看得见全部，
 *    其余角色只看得见自己那几条
 */

/**
 * 读会话动作用会话族自己的权限点。
 * 只用于让 grants / public 两个分支有同一套词汇可用；夹具里会话全部 private。
 */
const CONVERSATION_ACTION = PERM.conversationView

/** 单条取数与**写操作前置**共用同一个判定（07 §3.3 硬规矩 1）；看不见一律 404（硬规矩 3） */
const findConv = (id: string, token: string, action: string = CONVERSATION_ACTION): StoredConversation =>
  requireVisible(
    findConversation(id),
    currentSubjects(token),
    action,
    '会话',
    id
  )

/** 附件应用可见性：看得见应用才开得了它的会话，否则应用名会从会话标题里漏出去（07 §4.3） */
const appVisible = (appId: string, subjects: SubjectSet): boolean => {
  const app = assistants.find((a) => a.id === appId)
  return !!app && visiblePredicate(subjects, PERM.assistantView)(app)
}

/** 附加应用展示信息。契约类型上没有，但页面展示需要 —— 见报告「契约缺口」 */
const withApp = (conv: StoredConversation) => {
  if (conv.appId && !conv.app) conv.app = appBriefs[conv.appId]
  return conv
}

/* ── 免登录 token 解析：应用凭证 与 会话/消息分享 共用同一套校验词 ── */

interface PublicGateOk {
  ok: true
  /** 应用凭证分支 */
  cred?: { token: string; appId: string }
  /** 分享分支 */
  share?: { token: string; conversationId: string; scope: ShareScope; messageId?: string }
}
type PublicGate = PublicGateOk | { ok: false; code: number; message: string; hint?: string }

function gatePublicToken(token: string): PublicGate {
  const cred = publicCredentials.find((c) => c.token === token)
  if (cred) {
    if (!cred.enabled || cred.revokedAt) {
      return {
        ok: false,
        code: 40402,
        message: '访问凭证已被撤销',
        hint: '该公开链接已停止访问，请联系应用管理员重新生成链接'
      }
    }
    if (cred.expiresAt && new Date(cred.expiresAt).getTime() < Date.now()) {
      return {
        ok: false,
        code: 40403,
        message: '访问凭证已过期',
        hint: '请向分享者索取新的公开链接'
      }
    }
    return { ok: true, cred: { token: cred.token, appId: cred.appId } }
  }

  /*
   * 应用侧的访问凭证（`fixtures/assistants` 的 `credentials`）也要认。
   * 两套夹具是并行任务各自写的：CredentialsPanel 展示/生成的是这边的 token，
   * 而公开页校验走的是上面的 `publicCredentials` —— 不打通的话，
   * 「复制链接发给别人」永远打不开（token 对不上，报「访问凭证无效」）。
   */
  for (const [appId, list] of Object.entries(assistantCredentials)) {
    const ac = list.find((c) => c.token === token)
    if (!ac) continue
    if (!ac.enabled || ac.revokedAt) {
      return {
        ok: false,
        code: 40402,
        message: '访问凭证已被撤销',
        hint: '该公开链接已停止访问，请联系应用管理员重新生成链接'
      }
    }
    if (ac.expiresAt && new Date(ac.expiresAt).getTime() < Date.now()) {
      return {
        ok: false,
        code: 40403,
        message: '访问凭证已过期',
        hint: '请向分享者索取新的公开链接'
      }
    }
    return { ok: true, cred: { token: ac.token, appId } }
  }

  const share = findShareByToken(token)
  if (share) {
    if (share.revokedAt) {
      return {
        ok: false,
        code: 40402,
        message: '访问凭证已被撤销',
        hint: '该公开链接已停止访问，请向分享者索取新的公开链接'
      }
    }
    if (share.expiresAt && new Date(share.expiresAt).getTime() < Date.now()) {
      return {
        ok: false,
        code: 40403,
        message: '访问凭证已过期',
        hint: '请向分享者索取新的公开链接'
      }
    }
    return {
      ok: true,
      share: {
        token: share.token,
        conversationId: share.conversationId,
        scope: share.scope,
        messageId: share.messageId
      }
    }
  }

  return {
    ok: false,
    code: 40401,
    message: '访问凭证无效',
    hint: '链接可能不完整，请向分享者重新索取公开链接'
  }
}

function gateOrThrow(token: string): PublicGateOk {
  const gate = gatePublicToken(token)
  if (!gate.ok) throw new ApiError(gate.code, gate.message, gate.hint)
  return gate
}

/**
 * 分享分支的公开上下文：从会话反查应用摘要。
 * 无应用（通用问答）时按会话标题合成一个最小上下文，让公开页仍能开门，
 * 而不是因为 `appId` 为空就报「应用已不存在」。
 */
function contextFromShare(share: { conversationId: string }) {
  const conv = findConversation(share.conversationId)
  if (!conv) return null
  const app = conv.appId ? appBriefs[conv.appId] : undefined
  if (app) {
    return {
      appId: app.id,
      appName: app.name,
      icon: app.icon,
      description: app.description,
      prologue: app.prologue,
      quickQuestions: app.quickQuestions
    }
  }
  return {
    appId: conv.appId ?? '',
    appName: conv.appName || '通用问答',
    icon: conv.appName?.slice(0, 1) || '通',
    description: conv.title || '共享的对话',
    prologue: '这是共享的对话记录，可直接阅读下方内容。',
    quickQuestions: []
  }
}

function publicMessagesFor(gate: PublicGateOk): ChatMessage[] {
  if (gate.cred) {
    // 应用凭证分支：公开演示消息（chat-store 水合自 fixtures，允许被追加）
    return publicMessages[gate.cred.token] ?? []
  }
  if (gate.share) {
    const share = findShareByToken(gate.share.token)
    return share ? messagesOfShare(share) : []
  }
  return []
}

export const conversationRoutes: MockRoute[] = [
  /* ── 静态段路由必须排在参数路由之前（dispatch 按序首个命中） ── */

  [
    'GET',
    '/conversations',
    ({ query, token }) => {
      const subjects = currentSubjects(token)
      const appId = query.appId ? String(query.appId) : ''
      // mine=true 只看自己名下的（07 §六 阶段 1 的唯一新增查询参数）
      const mine = query.mine === true || query.mine === 'true'
      const keyword = String(query.keyword ?? '').trim()
      const list = conversations
        .filter(visiblePredicate(subjects, CONVERSATION_ACTION, { mine }))
        .filter((c) => !appId || c.appId === appId)
        // 历史搜索：按标题 / 应用名模糊匹配
        .filter((c) => {
          if (!keyword) return true
          return c.title.includes(keyword) || (c.appName ?? '').includes(keyword)
        })
        .map(withApp)
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      return paginate(list, query)
    }
  ],

  [
    'GET',
    '/conversations/:id',
    ({ path, token }) => withApp(findConv(path.id, token))
  ],

  /**
   * 一轮问答的落库入口（#4）。
   *
   * 页面在 mock 下走 `chatStreamMock`，但两条链路共用 `appendTurn`：
   * 流结束/中断的收尾处调用它，所以用户点「停止」时已生成的部分也写得进库。
   * 无 conversationId 时自动建会话并把 id 回给调用方。
   */
  [
    'POST',
    '/conversations/chat',
    ({ body, token }) => {
      const subjects = currentSubjects(token)
      const req = (body ?? {}) as {
        appId?: string
        conversationId?: string
        question: string
        attachmentIds?: string[]
        mode?: 'NEW' | 'REGENERATE' | 'CONTINUE'
        targetMessageId?: string
      }
      const question = (req.question ?? '').trim()
      if (!question) throw new ApiError(4001, '问题不能为空')

      if (req.conversationId) {
        findConv(req.conversationId, token)
      } else if (req.appId && !appVisible(req.appId, subjects)) {
        throw new ApiError(ErrorCode.NOT_FOUND, `应用「${req.appId}」不存在或已下架`)
      }

      // 同步生成（mock 语义）：非流式入口直接给完整回答
      const payload = req.appId ? cannedAnswer.withRefs : cannedAnswer.withoutRefs
      // 继续生成：只补还没写的尾巴（与流式 mock 同一口径）
      const prev =
        req.mode === 'CONTINUE' && req.targetMessageId
          ? findMessage(req.targetMessageId)
          : undefined
      const answer =
        req.mode === 'CONTINUE' && prev
          ? payload.text.slice(prev.content.length)
          : payload.text
      const turn = appendTurn({
        conversationId: req.conversationId,
        appId: req.appId ?? null,
        ownerId: selfId(subjects),
        question,
        answer,
        status: 'GENERATED',
        refs: payload.refs,
        suggestions: payload.suggestions,
        tokens: Math.round(answer.length / 2.2),
        durationMs: 900,
        attachmentIds: req.attachmentIds,
        messageId: `msg_${Date.now().toString(36)}`,
        mode: req.mode,
        targetMessageId: req.targetMessageId
      })
      return {
        conversationId: turn.conversationId,
        userMessage: turn.user,
        assistantMessage: turn.assistant
      }
    }
  ],

  [
    'GET',
    '/conversations/:id/shares',
    ({ path, token }) => {
      findConv(path.id, token)
      return listShares(path.id).map(toShareView)
    }
  ],

  [
    'POST',
    '/conversations/:id/shares',
    ({ path, body, token }) => {
      // 写操作前置也要过判定（§3.3 硬规矩 1）
      const conv = findConv(path.id, token)
      const req = (body ?? {}) as {
        scope?: ShareScope
        messageId?: string
        expiresAt?: string | null
      }
      const scope = req.scope ?? 'CONVERSATION'
      if (scope !== 'APP' && scope !== 'CONVERSATION' && scope !== 'MESSAGE') {
        throw new ApiError(4004, '分享范围只能是 app / conversation / message')
      }
      if (scope === 'MESSAGE') {
        if (!req.messageId) throw new ApiError(4005, '消息级分享必须指定 messageId')
        const msg = messagesOf(conv.id).find((m) => m.id === req.messageId)
        if (!msg) throw new ApiError(ErrorCode.NOT_FOUND, `消息「${req.messageId}」不存在`)
      }
      const rec = createShare({
        conversationId: conv.id,
        scope,
        messageId: req.messageId,
        expiresAt: req.expiresAt ?? null
      })
      return toShareView(rec)
    }
  ],

  [
    'DELETE',
    '/conversations/:id/shares/:token',
    ({ path, token }) => {
      findConv(path.id, token)
      // 撤销是幂等的：对的会话 + 不存在的 token 也算「撤销完成」
      revokeShare(path.id, path.token)
      return undefined
    }
  ],

  [
    'POST',
    '/conversations',
    ({ body, token }) => {
      const subjects = currentSubjects(token)
      const { appId } = (body ?? {}) as { appId?: string }
      const app = appId ? appBriefs[appId] : undefined
      if (appId && !app) {
        throw new ApiError(
          ErrorCode.NOT_FOUND,
          `应用「${appId}」不存在或已下架`,
          '请从智能体广场重新选择应用'
        )
      }
      // 看不见的应用：连会话也不该开得出来（新会话会带走应用名）
      if (appId && !appVisible(appId, subjects)) {
        throw new ApiError(ErrorCode.NOT_FOUND, `应用「${appId}」不存在或已下架`)
      }

      return addConversation({
        id: `c${Date.now().toString(36)}`,
        title: '新建会话',
        appId: appId ?? null,
        appName: app?.name ?? '通用问答',
        turnCount: 0,
        updatedAt: new Date().toISOString(),
        app,
        // 归属由"后端"写（07 §六 阶段 1）：会话是私人对话，默认 private
        ownerId: selfId(subjects),
        visibility: 'PRIVATE'
      })
    }
  ],

  [
    'PATCH',
    '/conversations/:id',
    ({ path, body, token }) => {
      const conv = findConv(path.id, token)
      const { title } = (body ?? {}) as { title?: string }
      if (!title?.trim()) throw new ApiError(4001, '会话名称不能为空')
      patchConversation(conv.id, { title: title.trim(), updatedAt: new Date().toISOString() })
      return withApp(conv)
    }
  ],

  [
    'DELETE',
    '/conversations/:id',
    ({ path, token }) => {
      const conv = findConv(path.id, token)
      removeConversation(conv.id)
      return undefined
    }
  ],

  [
    'GET',
    '/conversations/:conversationId/messages',
    ({ path, query, token }) => {
      findConv(path.conversationId, token)
      const list = messagesOf(path.conversationId)

      /*
       * around=messageId：以该消息为中心取一个窗口（深链 / 分享跳到某条消息）。
       * 与 page 互斥 —— 传了 around 就忽略 page；size 表示窗口宽度（前后各约 size/2）。
       * 找不到目标消息报 404，不静默回退第一页（回退会让深链「跳到别的消息」）。
       */
      const around = query.around != null && query.around !== '' ? String(query.around) : ''
      if (around) {
        const idx = list.findIndex((m) => m.id === around)
        if (idx < 0) {
          throw new ApiError(ErrorCode.NOT_FOUND, `消息「${around}」不存在`)
        }
        const size = Number(query.size ?? 20)
        const half = Math.floor(size / 2)
        const start = Math.max(0, idx - half)
        const end = Math.min(list.length, start + size)
        // 贴到末尾时向前扩，保证窗口尽量满
        const start2 = Math.max(0, end - size)
        return {
          list: list.slice(start2, end),
          total: list.length,
          page: 1,
          size
        }
      }

      // page=-1 表示取最后一页 —— 聊天窗口总是先看到最近的几条，再「加载更早」
      if (String(query.page ?? '') === '-1') {
        const size = Number(query.size ?? 50)
        const lastPage = Math.max(1, Math.ceil(list.length / size))
        return paginate(list, { ...query, page: lastPage, size })
      }
      return paginate(list, query)
    }
  ],

  /**
   * 引用回溯：按 id 取一条知识分段。
   * 可见性继承所属知识库（与 ingestion 的分段端点同口径）；看不见一律 404。
   */
  [
    'GET',
    '/segments/:id',
    ({ path, token }) => {
      const seg = segments.find((s) => s.id === path.id)
      if (!seg) throw new ApiError(ErrorCode.NOT_FOUND, `分段「${path.id}」不存在`)
      const doc = documents.find((d) => d.id === seg.documentId)
      const kb = doc ? knowledgeBases.find((k) => k.id === doc.kbId) : undefined
      requireVisible(kb, currentSubjects(token), PERM.knowledgeView, '分段', seg.id)
      return {
        id: seg.id,
        documentId: seg.documentId,
        title: seg.title,
        content: seg.content,
        charCount: seg.charCount,
        enabled: seg.enabled
      }
    }
  ],

  /**
   * 赞踩互斥且可撤销：
   * - 传 'up' / 'down' 覆盖为新值（互斥）
   * - 再点同一项传 null 表示取消
   * 状态写回存储，刷新后仍在。
   *
   * 写操作也要过判定：否则"看得见消息、改得了别人的赞踩"就是一条越权路径（§3.3 硬规矩 1）
   */
  [
    'POST',
    '/messages/:messageId/feedback',
    ({ path, body, token }) => {
      const msg = findMessage(path.messageId)
      if (!msg) throw new ApiError(ErrorCode.NOT_FOUND, `消息「${path.messageId}」不存在`)
      findConv(msg.conversationId, token)
      if (msg.role !== 'ASSISTANT') {
        throw new ApiError(4002, '只能对助手回答进行赞踩')
      }
      const { type } = (body ?? {}) as { type: 'UP' | 'DOWN' | null }
      if (type !== null && type !== 'UP' && type !== 'DOWN') {
        throw new ApiError(4003, '反馈类型只能是 UP / DOWN / null')
      }
      msg.feedback = type
      return msg
    }
  ],

  /* ── 免登录 ── */

  [
    'GET',
    '/public/:token',
    ({ path }) => {
      const gate = gateOrThrow(path.token)
      if (gate.cred) {
        // 两套凭证夹具（publicCredentials / assistants.credentials）都已在 gate 里归一成 appId
        const app = appBriefs[gate.cred.appId]
        const ctx = app
          ? {
              appId: app.id,
              appName: app.name,
              icon: app.icon,
              description: app.description,
              prologue: app.prologue,
              quickQuestions: app.quickQuestions
            }
          : null
        if (!ctx) {
          throw new ApiError(
            40404,
            '该公开链接对应的应用已不存在',
            '请联系应用管理员重新生成公开链接'
          )
        }
        return ctx
      }
      const ctx = contextFromShare(gate.share!)
      if (!ctx) {
        throw new ApiError(
          40404,
          '该公开链接对应的会话已不存在',
          '请向分享者索取新的公开链接'
        )
      }
      return ctx
    }
  ],

  [
    'GET',
    '/public/:token/messages',
    ({ path }) => {
      const gate = gateOrThrow(path.token)
      return publicMessagesFor(gate)
    }
  ]
]
