/**
 * 合规要件 mock 处理器（#83）：投诉举报工单 + 合规设置。
 *
 * ★ 与后端 `platform/compliance` 是同一份契约的两种实现（ContractDriftTest 对账 URL 字面量）：
 *   - 权限门禁照后端两层：动作上 `complaint:handle` / `platform:compliance:manage`，
 *     越权读取走 404（不暴露单据存在性）。
 *   - 状态机与后端 `ComplaintStatus.canTransitionTo` 同步：只往前走，RESOLVED/REJECTED 是终态。
 */
import { ApiError, ErrorCode } from '@/api/types'
import type {
  ComplaintPatchBody,
  ComplaintStatus,
  ComplianceSettings
} from '@/api/compliance'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { complaints, complianceSettings } from '../fixtures/compliance'
import { users } from '../fixtures/identity'
import { currentSubjects, selfId } from '../policy'

/** 状态流转表：与后端 ComplaintStatus.canTransitionTo 一一对应。 */
const TRANSITIONS: Record<ComplaintStatus, ComplaintStatus[]> = {
  SUBMITTED: ['SUBMITTED', 'PROCESSING', 'REJECTED'],
  PROCESSING: ['PROCESSING', 'RESOLVED', 'REJECTED'],
  RESOLVED: ['RESOLVED'],
  REJECTED: ['REJECTED']
}

const TERMINAL: ComplaintStatus[] = ['RESOLVED', 'REJECTED']

function displayNameOf(userId: string): string {
  return users.find((u) => u.id === userId)?.displayName ?? userId
}

/** 能不能办理工单（有 complaint:handle 看全部，否则只看自己提交的）。 */
function canHandleAll(token: string): boolean {
  return currentSubjects(token).permissions.has(PERM.complaintHandle)
}

function requireComplianceManage(token: string): void {
  if (!currentSubjects(token).permissions.has(PERM.complianceManage)) {
    throw new ApiError(ErrorCode.FORBIDDEN, '需要合规登记权限')
  }
}

function requireLogin(token: string): string {
  const id = selfId(currentSubjects(token))
  if (!id) {
    throw new ApiError(ErrorCode.UNAUTHORIZED, '请先登录')
  }
  return id
}

export const complianceRoutes: MockRoute[] = [
  // ★ 静态段排在 :id 之前（dispatch 顺序匹配）。
  ['GET', '/compliance/settings', () => ({ ...complianceSettings })],
  ['PUT', '/compliance/settings', ({ body, token }) => {
    requireComplianceManage(token)
    const input = body as {
      complaintFeedbackDays?: unknown
      algorithmFilingNo?: unknown
      securityAssessmentConclusion?: unknown
    }
    const days = Number(input.complaintFeedbackDays)
    if (!Number.isInteger(days) || days < 1 || days > 90) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '反馈时限须为 1-90 天的整数')
    }
    const filingNo = String(input.algorithmFilingNo ?? '').trim()
    const conclusion = String(input.securityAssessmentConclusion ?? '').trim()
    if (filingNo.length > 128) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '算法备案号不超过 128 个字符')
    }
    if (conclusion.length > 512) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '安全评估结论不超过 512 个字符')
    }
    complianceSettings.complaintFeedbackDays = days
    complianceSettings.algorithmFilingNo = filingNo
    complianceSettings.securityAssessmentConclusion = conclusion
    return { ...(complianceSettings as ComplianceSettings) }
  }],
  ['GET', '/complaints', ({ query, token }) => {
    const me = requireLogin(token)
    const all = canHandleAll(token)
    let list = complaints.filter((t) => all || t.requesterId === me)
    const status = query.status as ComplaintStatus | undefined
    if (status) list = list.filter((t) => t.status === status)
    const kind = query.kind as string | undefined
    if (kind) list = list.filter((t) => t.kind === kind)
    const keyword = String(query.keyword ?? '').trim()
    if (keyword) {
      list = list.filter(
        (t) => t.subject.includes(keyword) || t.content.includes(keyword) || t.ticketNo.includes(keyword)
      )
    }
    return paginate(list, query as Record<string, unknown>)
  }],
  ['POST', '/complaints', ({ body, token }) => {
    const me = requireLogin(token)
    const input = body as { kind?: string; subject?: string; content?: string; contact?: string }
    const subject = String(input.subject ?? '').trim()
    const content = String(input.content ?? '').trim()
    if (!subject || !content) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '标题与内容不能为空')
    }
    const now = new Date()
    const due = new Date(now.getTime() + complianceSettings.complaintFeedbackDays * 86400_000)
    const stamp = now.toISOString().slice(0, 10).replace(/-/g, '')
    const rand = Math.random().toString(36).slice(2, 6).toUpperCase()
    const ticket = {
      id: `cmp-${Date.now()}`,
      ticketNo: `CMP-${stamp}-${rand}`,
      kind: (input.kind === 'REPORT' ? 'REPORT' : 'COMPLAINT') as 'COMPLAINT' | 'REPORT',
      subject,
      content,
      contact: String(input.contact ?? ''),
      status: 'SUBMITTED' as ComplaintStatus,
      requesterId: me,
      requesterName: displayNameOf(me),
      createdAt: now.toISOString(),
      dueAt: due.toISOString(),
      updatedAt: now.toISOString()
    }
    complaints.unshift(ticket as (typeof complaints)[number])
    return ticket
  }],
  ['GET', '/complaints/:id', ({ path, token }) => {
    const me = requireLogin(token)
    const ticket = complaints.find((t) => t.id === path.id)
    // ★ 越权读取 404 —— 不暴露单据存在性（与后端 ComplaintService.get 同口径）。
    if (!ticket || (!canHandleAll(token) && ticket.requesterId !== me)) {
      throw new ApiError(ErrorCode.NOT_FOUND, '工单不存在')
    }
    return ticket
  }],
  ['PATCH', '/complaints/:id', ({ path, body, token }) => {
    const me = requireLogin(token)
    if (!canHandleAll(token)) {
      throw new ApiError(ErrorCode.FORBIDDEN, '需要投诉举报办理权限')
    }
    const ticket = complaints.find((t) => t.id === path.id)
    if (!ticket) {
      throw new ApiError(ErrorCode.NOT_FOUND, '工单不存在')
    }
    const input = body as ComplaintPatchBody
    const target = input.status
    if (!TRANSITIONS[ticket.status]?.includes(target)) {
      throw new ApiError(ErrorCode.BAD_REQUEST, `工单状态不能从 ${ticket.status} 变更为 ${target}`)
    }
    const note = String(input.handlerNote ?? '').trim()
    if (TERMINAL.includes(target) && !note) {
      throw new ApiError(ErrorCode.BAD_REQUEST, '办结或不予受理时必须填写处理说明')
    }
    ticket.status = target
    ticket.handlerName = displayNameOf(me)
    ticket.handlerNote = note
    ticket.updatedAt = new Date().toISOString()
    return ticket
  }],
]
