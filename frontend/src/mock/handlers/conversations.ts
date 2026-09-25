import { ApiError, ErrorCode } from '@/api/types'
import type { ChatMessage, Conversation } from '@/api/conversations'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import {
  appBriefs,
  conversations,
  messagesByConv,
  publicCredentials,
  publicMessages,
  toPublicContext
} from '../fixtures/conversations'
import { assistants } from '../fixtures/assistants'
import { currentSubjects, requireVisible, selfId, visiblePredicate, type SubjectSet } from '../policy'

/**
 * 会话域 mock。
 *
 * 只实现 src/api/conversations.ts 冻结契约里的端点。
 * 赞踩状态写回 fixtures 里的消息对象 —— 与真实后端一样「刷新后仍在」。
 *
 * 数据权限（07 §4.2「会话：只有自己的」）：
 *  - 会话自己没有权限点（13 个权限点全是操作，没有 `conversation:*`），所以读会话按
 *    `assistant:view` 问 —— 会话挂在应用上（§4.3），共享/公开分支用的就是应用那一套词
 *  - 会话夹具全部 `private` ⇒ 实际由「归属 + 数据范围」两项决定：全平台档看得见全部，
 *    其余角色只看得见自己那几条
 */

/**
 * 会话在权限点里没有自己的点，借用应用的那一个：会话挂在应用上（07 §4.3）。
 * 只用于让 grants / public 两个分支有同一套词汇可用；夹具里会话全部 private。
 */
const CONVERSATION_ACTION = PERM.assistantView

type StoredConversation = Conversation & { app?: (typeof appBriefs)[string] }

/** 单条取数与**写操作前置**共用同一个判定（07 §3.3 硬规矩 1）；看不见一律 404（硬规矩 3） */
const findConv = (id: string, token: string, action: string = CONVERSATION_ACTION): StoredConversation =>
  requireVisible(
    conversations.find((c) => c.id === id),
    currentSubjects(token),
    action,
    '会话',
    id
  )

const findMessage = (id: string): ChatMessage => {
  for (const list of Object.values(messagesByConv)) {
    const msg = list.find((m) => m.id === id)
    if (msg) return msg
  }
  throw new ApiError(ErrorCode.NOT_FOUND, `消息「${id}」不存在`)
}

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

export const conversationRoutes: MockRoute[] = [
  [
    'GET',
    '/conversations',
    ({ query, token }) => {
      const subjects = currentSubjects(token)
      const appId = query.appId ? String(query.appId) : ''
      // mine=true 只看自己名下的（07 §六 阶段 1 的唯一新增查询参数）
      const mine = query.mine === true || query.mine === 'true'
      const list = conversations
        .filter(visiblePredicate(subjects, CONVERSATION_ACTION, { mine }))
        .filter((c) => !appId || c.appId === appId)
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

      const created: StoredConversation = {
        id: `c${Date.now().toString(36)}`,
        title: '新建会话',
        appId: appId ?? null,
        appName: app?.name ?? '通用问答',
        turnCount: 0,
        updatedAt: new Date().toISOString(),
        app,
        // 归属由"后端"写（07 §六 阶段 1）：会话是私人对话，默认 private
        ownerId: selfId(subjects),
        visibility: 'private'
      }
      conversations.unshift(created)
      messagesByConv[created.id] = []
      return created
    }
  ],

  [
    'PATCH',
    '/conversations/:id',
    ({ path, body, token }) => {
      const conv = findConv(path.id, token)
      const { title } = (body ?? {}) as { title?: string }
      if (!title?.trim()) throw new ApiError(4001, '会话名称不能为空')
      conv.title = title.trim()
      conv.updatedAt = new Date().toISOString()
      return withApp(conv)
    }
  ],

  [
    'DELETE',
    '/conversations/:id',
    ({ path, token }) => {
      const conv = findConv(path.id, token)
      conversations.splice(conversations.indexOf(conv), 1)
      delete messagesByConv[conv.id]
      return undefined
    }
  ],

  [
    'GET',
    '/conversations/:conversationId/messages',
    ({ path, query, token }) => {
      findConv(path.conversationId, token)
      const list = messagesByConv[path.conversationId] ?? []
      return paginate(list, query)
    }
  ],

  /**
   * 赞踩互斥且可撤销：
   * - 传 'up' / 'down' 覆盖为新值（互斥）
   * - 再点同一项传 null 表示取消
   * 状态写回 fixtures，刷新后仍在。
   *
   * 写操作也要过判定：否则"看得见消息、改得了别人的赞踩"就是一条越权路径（§3.3 硬规矩 1）
   */
  [
    'POST',
    '/messages/:messageId/feedback',
    ({ path, body, token }) => {
      const msg = findMessage(path.messageId)
      findConv(msg.conversationId, token)
      if (msg.role !== 'assistant') {
        throw new ApiError(4002, '只能对助手回答进行赞踩')
      }
      const { type } = (body ?? {}) as { type: 'up' | 'down' | null }
      if (type !== null && type !== 'up' && type !== 'down') {
        throw new ApiError(4003, '反馈类型只能是 up / down / null')
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
      const cred = publicCredentials.find((c) => c.token === path.token)
      // 三要素校验：存在 / 启用且未撤销 / 未过期。失败必须给出可读原因与下一步
      if (!cred) {
        throw new ApiError(
          40401,
          '访问凭证无效',
          '链接可能不完整，请向分享者重新索取公开链接'
        )
      }
      if (!cred.enabled || cred.revokedAt) {
        throw new ApiError(
          40402,
          '访问凭证已被撤销',
          '该公开链接已停止访问，请联系应用管理员重新生成链接'
        )
      }
      if (cred.expiresAt && new Date(cred.expiresAt).getTime() < Date.now()) {
        throw new ApiError(
          40403,
          '访问凭证已过期',
          '请向分享者索取新的公开链接'
        )
      }
      const ctx = toPublicContext(cred)
      if (!ctx) {
        throw new ApiError(
          40404,
          '该公开链接对应的应用已不存在',
          '请联系应用管理员重新生成公开链接'
        )
      }
      return ctx
    }
  ],

  [
    'GET',
    '/public/:token/messages',
    ({ path }) => {
      const cred = publicCredentials.find((c) => c.token === path.token)
      if (!cred) throw new ApiError(40401, '访问凭证无效', '请向分享者重新索取公开链接')
      if (!cred.enabled || cred.revokedAt) {
        throw new ApiError(40402, '访问凭证已被撤销', '该公开链接已停止访问')
      }
      if (cred.expiresAt && new Date(cred.expiresAt).getTime() < Date.now()) {
        throw new ApiError(40403, '访问凭证已过期', '请向分享者索取新的公开链接')
      }
      return publicMessages[cred.token] ?? []
    }
  ]
]
