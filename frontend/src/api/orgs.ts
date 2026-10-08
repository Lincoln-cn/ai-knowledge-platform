import { request } from '@/lib/http'

/* ────────────────────────── 契约类型（与后端 identity/web/OrgController 逐字对齐） ────────────────────────── */

/**
 * 一个部门节点（扁平形态）。
 *
 * ★ 判据落在 `code` 上而不是 `name`：`code` 是**共享主体与审计里的稳定标识**（改名不改码），
 * 而 `name` 会进文档的**召回前缀**（`12 §4.2`）—— 所以界面上必须把两者分开展示，
 * 免得有人以为"改个名而已"。
 */
export interface OrgView {
  id: string
  parentId: string | null
  code: string
  name: string
  /** 物化路径（形如 `/ROOT/PURCHASING/`，★ 结尾那个斜杠是"含下级"判定的关键，别在前端拼错） */
  path: string
  sort: number
  enabled: boolean
}

/** 树节点（嵌套形态，`GET /orgs/tree` 的返回）。 */
export interface OrgNodeView {
  id: string
  code: string
  name: string
  path: string
  sort: number
  enabled: boolean
  children: OrgNodeView[]
}

export interface UpsertOrgBody {
  /** 不传或 null = 建/移到根 */
  parentId?: string | null
  /** ★ 只在新建时用：后端没有"改 code"的口（它是共享主体标识） */
  code?: string
  name?: string
  sort?: number
  enabled?: boolean
}

/* ────────────────────────── 接口 ────────────────────────── */

/** 树（嵌套）。界面左侧那棵树用它。 */
export const orgTree = () => request<OrgNodeView[]>({ method: 'GET', url: '/orgs/tree' })

/** 扁平清单：下拉与"目标上级"选择器用它（★ 树上做选择器要展开/收起，扁平更省事）。 */
export const listOrgs = () => request<OrgView[]>({ method: 'GET', url: '/orgs' })

export const createOrg = (body: UpsertOrgBody) =>
  request<OrgView>({ method: 'POST', url: '/orgs', body })

/** 改展示名 / 排序 / 启停（Partial）。★ 不含 code。 */
export const updateOrg = (id: string, body: UpsertOrgBody) =>
  request<OrgView>({ method: 'PUT', url: '/orgs/:id', path: { id }, body })

export const moveOrg = (id: string, parentId: string | null) =>
  request<OrgView>({ method: 'POST', url: '/orgs/:id/move', path: { id }, body: { parentId } })

/** 删一个**空**部门。有下级或有用户时后端 400 + 可读 hint（提示改走「停用」）。 */
export const deleteOrg = (id: string) =>
  request<void>({ method: 'DELETE', url: '/orgs/:id', path: { id } })
