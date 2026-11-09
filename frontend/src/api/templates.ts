import { request } from '@/lib/http'

/**
 * 模板中心契约（`#295`）。
 *
 * 这一版模板的唯一来源是「把一个应用存成快照」，所以这里**没有 update**：
 * 改模板的正确做法是存一个新的（后端 `as_template` 刻意没有 update-payload 的仓储方法）。
 *
 * 视图里不带 payload 全文 —— 列表接口的体积不该与模板数成正比，
 * 拿 payload 去建应用是 `POST /templates/{id}/instantiate` 的事，前端不需要读懂它。
 */

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 模板（列表与详情同一个形状） */
export interface Template {
  id: string
  name: string
  description: string
  /** 分类（用于列表页分组；不填按「未分类」） */
  category: string
  /** 从哪个应用抽出来的；内置模板为 null */
  sourceAppId: string | null
  /** 内置模板不可改不可删 */
  builtin: boolean
  createdAt: string
}

export interface CreateTemplateBody {
  /** 源应用 id —— 后端据此导出快照 */
  sourceAppId: string
  name: string
  description: string
  category: string
}

export interface InstantiateTemplateBody {
  /** 依赖缺失时是否继续建（缺的引用会被跳过） */
  skipMissingReferences?: boolean
  /** 建应用时可以覆盖的字段（如换名字） */
  extra?: Record<string, unknown>
}

/* ────────────────────────── 接口 ────────────────────────── */

/**
 * 模板列表。
 *
 * ★ 后端这个端点返回的是**裸数组**不是分页对象（模板数量是人手数得过来的量级），
 * 与 `/prompt-types` 的 `{list,total}` 不同 —— 别照抄。
 */
export const listTemplates = () => request<Template[]>({ method: 'GET', url: '/templates' })

export const getTemplate = (id: string) =>
  request<Template>({ method: 'GET', url: '/templates/:id', path: { id } })

export const createTemplate = (body: CreateTemplateBody) =>
  request<Template>({ method: 'POST', url: '/templates', body })

/**
 * 从模板建应用，返回新建的应用（形状同 `GET /assistants/{id}`）。
 *
 * 这里不加类型约束是有意的：应用视图字段一多就爱变，而模板中心只需要「建成了、id 是多少」。
 * 调用方要类型就用 `assistantsApi.getAssistant(res.id)` 再取一次。
 */
export const instantiateTemplate = (id: string, body: InstantiateTemplateBody = {}) =>
  request<Record<string, unknown>>({
    method: 'POST',
    url: '/templates/:id/instantiate',
    path: { id },
    body,
  })

export const deleteTemplate = (id: string) =>
  request<void>({ method: 'DELETE', url: '/templates/:id', path: { id } })
