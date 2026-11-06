/**
 * 合规要件 mock 样例数据（#83）：投诉举报工单 + 合规设置。
 *
 * ★ 工单号沿用后端形状 `CMP-<yyyyMMdd>-<4位>`；反馈时限 15 天与 V83 迁移的种入值一致
 *   （15 天 = 运营口径，不是法条数字 —— 见迁移头注释）。
 */
import type { ComplaintTicket } from '@/api/compliance'

export const complianceSettings = {
  complaintFeedbackDays: 15,
  // ★ #83 第十七条「留缝」登记位：mock 呈现「未登记」状态（MVP 真实状态），不编造备案号。
  algorithmFilingNo: null as string | null,
  securityAssessmentConclusion: null as string | null,
}

export const complaints: ComplaintTicket[] = [
  {
    id: 'cmp-001',
    ticketNo: 'CMP-20261106-8K3M',
    kind: 'COMPLAINT',
    subject: '检索结果与文档内容不符',
    content: '在「合同管理制度」知识库提问验收流程，回答引用的分段与文档原文对不上，怀疑引用错位。',
    contact: 'zhangsan@example.com',
    status: 'SUBMITTED',
    requesterId: 'u2',
    requesterName: '张三',
    createdAt: '2026-11-06T09:12:00+08:00',
    dueAt: '2026-11-21T09:12:00+08:00',
    updatedAt: '2026-11-06T09:12:00+08:00'
  },
  {
    id: 'cmp-002',
    ticketNo: 'CMP-20261105-7H2N',
    kind: 'REPORT',
    subject: '举报回答中出现不当内容',
    content: '某个会话里回答包含疑似违规信息，已截图留存，请求核查处置。',
    contact: '13800000000',
    status: 'PROCESSING',
    requesterId: 'u3',
    requesterName: '李四',
    handlerName: '知识运营',
    handlerNote: '已定位到对应会话记录，正在按第十四条口径核查并保存记录。',
    createdAt: '2026-11-05T14:30:00+08:00',
    dueAt: '2026-11-20T14:30:00+08:00',
    updatedAt: '2026-11-05T16:02:00+08:00'
  },
  {
    id: 'cmp-003',
    ticketNo: 'CMP-20261102-3P9Q',
    kind: 'COMPLAINT',
    subject: '反馈时限内未收到答复',
    content: '此前提交的投诉没有收到反馈，超出了公示的 15 天反馈时限。',
    contact: '',
    status: 'RESOLVED',
    requesterId: 'u4',
    requesterName: '王五',
    handlerName: '管理员',
    handlerNote: '已于 11-04 电话答复处理结果并记录办结；本单为重复提交，已合并归档。',
    createdAt: '2026-11-02T10:05:00+08:00',
    dueAt: '2026-11-17T10:05:00+08:00',
    updatedAt: '2026-11-04T11:20:00+08:00'
  }
]
