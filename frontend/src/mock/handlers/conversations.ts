import { ApiError, ErrorCode } from '@/api/types'
import type { ChatMessage, Conversation } from '@/api/conversations'
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

/**
 * 会话域 mock。
 *
 * 只实现 src/api/conversations.ts 冻结契约里的端点。
 * 赞踩状态写回 fixtures 里的消息对象 —— 与真实后端一样「刷新后仍在」。
 */

type StoredConversation = Conversation & { app?: (typeof appBriefs)[string] }

const findConv = (id: string): StoredConversation => {
  const conv = conversations.find((c) => c.id === id)
  if (!conv) throw new ApiError(ErrorCode.NOT_FOUND, `会话「${id}」不存在或已删除`)
  return conv
}

const findMessage = (id: string): ChatMessage => {
  for (const list of Object.values(messagesByConv)) {
    const msg = list.find((m) => m.id === id)
    if (msg) return msg
  }
  throw new ApiError(ErrorCode.NOT_FOUND, `消息「${id}」不存在`)
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
      if (!token) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      const appId = query.appId ? String(query.appId) : ''
      const list = conversations
        .filter((c) => !appId || c.appId === appId)
        .map(withApp)
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      return paginate(list, query)
    }
  ],

  [
    'GET',
    '/conversations/:id',
    ({ path, token }) => {
      if (!token) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      return withApp(findConv(path.id))
    }
  ],

  [
    'POST',
    '/conversations',
    ({ body, token }) => {
      if (!token) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      const { appId } = (body ?? {}) as { appId?: string }
      const app = appId ? appBriefs[appId] : undefined
      if (appId && !app) {
        throw new ApiError(
          ErrorCode.NOT_FOUND,
          `应用「${appId}」不存在或已下架`,
          '请从智能体广场重新选择应用'
        )
      }

      const created: StoredConversation = {
        id: `c${Date.now().toString(36)}`,
        title: '新建会话',
        appId: appId ?? null,
        appName: app?.name ?? '通用问答',
        turnCount: 0,
        updatedAt: new Date().toISOString(),
        app
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
      if (!token) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      const conv = findConv(path.id)
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
      if (!token) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      const conv = findConv(path.id)
      conversations.splice(conversations.indexOf(conv), 1)
      delete messagesByConv[conv.id]
      return undefined
    }
  ],

  [
    'GET',
    '/conversations/:conversationId/messages',
    ({ path, query, token }) => {
      if (!token) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      findConv(path.conversationId)
      const list = messagesByConv[path.conversationId] ?? []
      return paginate(list, query)
    }
  ],

  /**
   * 赞踩互斥且可撤销：
   * - 传 'up' / 'down' 覆盖为新值（互斥）
   * - 再点同一项传 null 表示取消
   * 状态写回 fixtures，刷新后仍在。
   */
  [
    'POST',
    '/messages/:messageId/feedback',
    ({ path, body, token }) => {
      if (!token) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      const msg = findMessage(path.messageId)
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
