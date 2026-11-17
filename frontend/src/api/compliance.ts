/**
 * 合规要件契约（#83）：投诉举报工单 + 合规设置（反馈时限 / 算法备案登记位）。
 *
 * ★ 与 `feedback` 族分开：feedback 是内容质量反馈（赞踩→沉淀问答对），
 *   投诉举报是留痕举证工单（反馈时限 + 公示流程，第十五条）—— 两族受理口径与留痕要求不同。
 */
import { request } from '@/lib/http'
import { API_PREFIX, USE_MOCK } from '@/config/env'
import { getToken } from '@/lib/token'
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
  /**
   * 只看自己提交的（`#356`）。
   *
   * ★ 有办理权限的人默认看到**全部**；「我的投诉」页必须显式传 `true`，
   * 否则处理人自己提的那张单会混在全量分页里，翻三页才碰得到。
   */
  mine?: boolean
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

/**
 * 事件流与附件（`#356`）。
 *
 * ★ 这一族解决的是"整个过程看不见"：工单行只存终态（`handlerNote` 每次覆盖写），
 *   办三次就只剩最后一句话。事件流是追加写的 —— 每次状态流转、每句补充说明各占一行，
 *   附件挂在具体某一条下面。
 */
export type ComplaintSide = 'REQUESTER' | 'HANDLER' | 'SYSTEM'
export type ComplaintEventKind = 'CREATED' | 'NOTE' | 'STATUS_CHANGED' | 'ATTACHMENT'

export interface ComplaintAttachment {
  id: string
  eventId?: string | null
  fileName: string
  contentType: string
  sizeBytes: number
  uploadedAt: string
  uploadedBy?: string | null
  uploadedName: string
  side: ComplaintSide
}

export interface ComplaintEvent {
  id: string
  ticketId: string
  at: string
  actorId?: string | null
  actorName: string
  side: ComplaintSide
  kind: ComplaintEventKind
  fromStatus?: ComplaintStatus | null
  toStatus?: ComplaintStatus | null
  note?: string | null
  attachments: ComplaintAttachment[]
}

/** 整条时间线（时间正序）。 */
export function listComplaintEvents(id: string) {
  return request<ComplaintEvent[]>({ method: 'GET', url: '/complaints/:id/events', path: { id } })
}

/** 补一条说明（不改状态）：投诉人与处理人都能写。 */
export function addComplaintNote(id: string, note: string) {
  return request<ComplaintTicket>({
    method: 'POST',
    url: '/complaints/:id/notes',
    path: { id },
    body: { note }
  })
}

/**
 * 上传附件（截图 / 日志文件）。
 *
 * ★ 用 `FormData` 而不是 JSON：这里是唯一的二进制入口，走 multipart 才是它本来的形状。
 *   `eventId` 可选 —— 传了就是挂在那条消息下，不传是挂在工单上。
 */
export function uploadComplaintAttachment(id: string, file: File, eventId?: string) {
  const form = new FormData()
  form.append('file', file)
  if (eventId) form.append('eventId', eventId)
  return request<ComplaintAttachment>({
    method: 'POST',
    url: '/complaints/:id/attachments',
    path: { id },
    body: form
  })
}

/**
 * 下载附件。
 *
 * ★ 不能直接把路径丢给 `<a href>`：那个请求不带 `Authorization` 头，后端会 401。
 *   所以走 fetch 自己取字节，再交给调用方生成 object URL。
 *   mock 模式下没有真实字节，返回一段说明文字 —— 让"点了没反应"不发生在 mock 里，
 *   但也别在 mock 里假装下载到一个真文件。
 */
export async function downloadComplaintAttachment(
  attachment: ComplaintAttachment
): Promise<{ blob: Blob; fileName: string }> {
  if (USE_MOCK) {
    return {
      blob: new Blob([`（mock 模式）附件「${attachment.fileName}」的字节只在真实后端里。`], {
        type: 'text/plain;charset=utf-8'
      }),
      fileName: `${attachment.fileName}.mock.txt`
    }
  }
  const token = getToken()
  const response = await fetch(`${API_PREFIX}/complaints/attachments/${attachment.id}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined
  })
  if (!response.ok) {
    throw new Error(`下载失败（${response.status}）：${response.statusText || '未知原因'}`)
  }
  return { blob: await response.blob(), fileName: attachment.fileName }
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

/** 服务协议同意记录（`#83` 第九条/第十条：协议与适用人群公示 + 同意留痕）。 */
export interface AgreementConsent {
  id: string
  userId: string
  /** 同意的协议版本（`AGREEMENT_VERSION`）。 */
  version: string
  agreedAt: string
}

/** 查询当前用户最新一次协议同意记录；从未同意过返回 null（首登弹协议）。 */
export function getAgreementConsent() {
  return request<AgreementConsent | null>({
    method: 'GET',
    url: '/compliance/agreement/consent',
  })
}

/** 同意当前服务协议版本（幂等：同版本重复提交返回同一条记录）。 */
export function consentAgreement(body: { version: string }) {
  return request<AgreementConsent>({
    method: 'POST',
    url: '/compliance/agreement/consent',
    body,
  })
}
