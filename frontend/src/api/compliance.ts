/**
 * 合规要件契约（#83）：投诉举报工单 + 合规设置（反馈时限 / 算法备案登记位）。
 *
 * ★ 与 `feedback` 族分开：feedback 是内容质量反馈（赞踩→沉淀问答对），
 *   投诉举报是留痕举证工单（反馈时限 + 公示流程，第十五条）—— 两族受理口径与留痕要求不同。
 */
import { request } from '@/lib/http'
import type { PageResult } from '@/api'

/** 投诉 / 举报（同一入口，第十五条）。 */
export type ComplaintKind = 'COMPLAINT' | 'REPORT'

/** 工单状态：只往前走；RESOLVED / REJECTED 是终态（办结 / 不予受理）。 */
export type ComplaintStatus = 'SUBMITTED' | 'PROCESSING' | 'RESOLVED' | 'REJECTED'

export interface ComplaintTicket {
  id: string
  ticketNo: string
  kind: ComplaintKind
  subject: string
  content: string
  contact: string
  status: ComplaintStatus
  handlerId?: string
  handlerName?: string
  handlerNote?: string
  handledAt?: string
  dueAt: string
  requesterId: string
  requesterName: string
  createdAt: string
  updatedAt: string
}

export interface ComplaintQuery {
  status?: ComplaintStatus
  kind?: ComplaintKind
  keyword?: string
  page?: number
  size?: number
}

export interface ComplaintCreateBody {
  kind?: ComplaintKind
  subject: string
  content: string
  contact?: string
}

export interface ComplaintPatchBody {
  status: ComplaintStatus
  handlerNote: string
}

/** 合规设置：投诉举报反馈时限 + 算法备案号/安全评估结论登记位（后者迁移落列后随契约同步加字段）。 */
export interface ComplianceSettings {
  complaintFeedbackDays: number
  /**
   * 算法备案号（#83 第十七条「留缝」登记位）。空 = 未登记。
   * ★ 合规设置页可登记、「关于」引用；不建申报/变更/注销流程 —— 那是流程系统不是字段。
   */
  algorithmFilingNo?: string | null
  /** 安全评估结论（#83 第十七条「留缝」登记位）。空 = 未登记。 */
  securityAssessmentConclusion?: string | null
  updatedAt?: string
  updatedBy?: string
}

/** 提交投诉举报（登录即可）。 */
export function createComplaint(body: ComplaintCreateBody) {
  return request<ComplaintTicket>({ method: 'POST', url: '/complaints', body })
}

/** 工单列表：有 `complaint:handle` 看全部，否则只看自己提交的。 */
export function listComplaints(query: ComplaintQuery = {}) {
  return request<PageResult<ComplaintTicket>>({ method: 'GET', url: '/complaints', query })
}

export function getComplaint(id: string) {
  return request<ComplaintTicket>({ method: 'GET', url: '/complaints/:id', path: { id } })
}

/** 办理工单：状态流转 + 处理说明（办结/不予受理必填）。 */
export function patchComplaint(id: string, body: ComplaintPatchBody) {
  return request<ComplaintTicket>({ method: 'PATCH', url: '/complaints/:id', path: { id }, body })
}

/** 读合规设置（登录即可：反馈时限要在投诉入口公示）。 */
export function getComplianceSettings() {
  return request<ComplianceSettings>({ method: 'GET', url: '/compliance/settings' })
}

/** 登记合规设置（`platform:compliance:manage`）：反馈时限 + 算法备案号/安全评估结论登记位（空 = 未登记，不校验格式）。 */
export function updateComplianceSettings(body: {
  complaintFeedbackDays: number
  algorithmFilingNo?: string
  securityAssessmentConclusion?: string
}) {
  return request<ComplianceSettings>({ method: 'PUT', url: '/compliance/settings', body })
}
