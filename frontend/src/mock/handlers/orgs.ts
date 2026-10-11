import { ApiError, ErrorCode } from '@/api/types'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { currentSubjects } from '../policy'

/**
 * 组织机构（`07 §十`）—— 与后端 `OrgController` / `OrgService` **逐条对齐**。
 *
 * ★ 夹具是 ROOT + 八个部门，与后端 `V25` 的种子**同值**（照抄而不是随手编）：
 * 两边不一致的症状是"演示时好用、部署后不一样"，而这条链（建档选责任部门、共享给部门）
 * 正是演示路径。
 *
 * ★ `path` 结尾**必须**有斜杠（`/ROOT/PURCHASING/`）：那是"含下级"判定的关键 ——
 * 少了它 `/ROOT/AD/` 会匹配到 `/ROOT/ADMIN/`，一次"部门看到别人部门"的**静默越权**。
 *
 * ★ 读登录即可、写要 `identity:org:manage`（与后端同口径）：建档要选责任部门，
 * 卡上管理权限等于让每个上传者在建档页撞 403。
 */

const MANAGE_ACTION = PERM.orgManage

interface Org {
  id: string
  parentId: string | null
  code: string
  name: string
  path: string
  sort: number
  enabled: boolean
}

const ROOT_ID = '00000000-0000-4000-8000-000000000001'

const orgs: Org[] = [
  { id: ROOT_ID, parentId: null, code: 'ROOT', name: '总部', path: '/ROOT/', sort: 0, enabled: true },
  ...(
    [
      ['PURCHASING', '采购部', 1],
      ['FINANCE', '财务部', 2],
      ['LEGAL', '法务部', 3],
      ['HR', '人事部', 4],
      ['TECH', '技术部', 5],
      ['ADMIN', '行政部', 6],
      ['MARKETING', '市场部', 7],
      ['PRODUCTION', '生产部', 8]
    ] as Array<[string, string, number]>
  ).map(([code, name, sort], index) => ({
    id: `00000000-0000-4000-8000-00000000010${index + 1}`,
    parentId: ROOT_ID,
    code,
    name,
    path: `/ROOT/${code}/`,
    sort,
    enabled: true
  }))
]

/** 按 path 排序（与后端 `findAllByOrderByPathAsc` 同序，界面才不会两处不一样）。 */
const ordered = () => [...orgs].sort((a, b) => a.path.localeCompare(b.path))

const toTree = (): Array<Org & { children: unknown[] }> => {
  const nodes = new Map<string, Org & { children: Array<Org & { children: unknown[] }> }>()
  ordered().forEach((o) => nodes.set(o.id, { ...o, children: [] }))
  const roots: Array<Org & { children: unknown[] }> = []
  ordered().forEach((o) => {
    const node = nodes.get(o.id)
    if (!node) return
    const parent = o.parentId ? nodes.get(o.parentId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
}

const findById = (id: string) => orgs.find((o) => o.id === id)

/** 动作级门禁：拿不到权限点一律 **403 且说清缺哪一个**（静默返空会把"写被拒"伪装成"保存成功但没变"）。 */
function requireManage(token: string): void {
  if (currentSubjects(token).permissions.has(MANAGE_ACTION)) return
  throw new ApiError(
    ErrorCode.FORBIDDEN,
    `没有维护组织机构的权限（需要 ${MANAGE_ACTION}）`,
    '请联系管理员为你的角色勾选该权限点'
  )
}

/**
 * 按 id 找一个部门 —— 给**共享授权**的写入口做主体校验用（`07 §8.2`）。
 *
 * ★ 与后端的 `GrantSubjectPort` 同口径：停用（`enabled = false`）的部门**不接受新的共享**
 * （`07 §10.5` 判据 3："停用不产生新共享，既有共享不变成不可见"），所以这里对停用的返回 undefined。
 */
export const findShareableOrg = (id: string): Org | undefined =>
  orgs.find((o) => o.id === id && o.enabled)

export const orgRoutes: MockRoute[] = [
  ['GET', '/orgs/tree', () => toTree()],
  ['GET', '/orgs', () => ordered()],
  [
    'POST',
    '/orgs',
    ({ body, token }) => {
      requireManage(token)
      const input = (body ?? {}) as Partial<Org>
      if (!input.code || !/^[A-Za-z0-9_]{1,64}$/.test(input.code)) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          '部门编码只允许字母、数字与下划线，且不超过 64 字',
          '编码是共享主体与审计里的标识，形状必须稳定'
        )
      }
      if (orgs.some((o) => o.code === input.code)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `部门编码「${input.code}」已存在`)
      }
      // ★★ 同级展示名唯一（与后端 `OrgService.create` 同口径）：重名会让"按名字比较"的地方
      //   把两个部门混成一个 ⇒ 一个部门看到另一个部门的文档，且不报错。跨级重名允许。
      const name = (input.name ?? input.code).trim()
      if (orgs.some((o) => o.parentId === (input.parentId ?? null) && o.name === name)) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `同级已有叫「${name}」的部门`,
          '展示名在同级必须唯一；跨级重名是允许的（两个分公司各有一个「行政部」是合理的）'
        )
      }
      const parent = input.parentId ? findById(input.parentId) : undefined
      if (input.parentId && !parent) {
        throw new ApiError(ErrorCode.NOT_FOUND, '上级部门不存在', '请刷新后重试')
      }
      const created: Org = {
        id: crypto.randomUUID(),
        parentId: input.parentId ?? null,
        code: input.code,
        name: input.name ?? input.code,
        path: `${parent ? parent.path : '/'}${input.code}/`,
        sort: input.sort ?? 0,
        enabled: true
      }
      orgs.push(created)
      return created
    }
  ],
  [
    'PUT',
    '/orgs/:id',
    ({ path, body, token }) => {
      requireManage(token)
      const org = findById(path.id)
      if (!org) throw new ApiError(ErrorCode.NOT_FOUND, '部门不存在', '请刷新后重试')
      const input = (body ?? {}) as Partial<Org>
      // ★ 故意不处理 code：与后端同口径 —— 它进了共享主体与审计，改它等于把共享悄悄指到别处
      if (input.name !== undefined) org.name = input.name
      if (input.sort !== undefined) org.sort = input.sort
      if (input.enabled !== undefined) org.enabled = input.enabled
      return org
    }
  ],
  [
    'POST',
    '/orgs/:id/move',
    ({ path, body, token }) => {
      requireManage(token)
      const org = findById(path.id)
      if (!org) throw new ApiError(ErrorCode.NOT_FOUND, '部门不存在', '请刷新后重试')
      const parentId = ((body ?? {}) as { parentId?: string | null }).parentId ?? null
      if (parentId === org.id) {
        throw new ApiError(ErrorCode.BAD_REQUEST, '不能把部门移到它自己下面')
      }
      const parent = parentId ? findById(parentId) : undefined
      if (parentId && !parent) {
        throw new ApiError(ErrorCode.NOT_FOUND, '上级部门不存在', '请刷新后重试')
      }
      // ★ 移进自己的子树会成环：树遍历与"含下级"的前缀判定都会失去意义，而且**不报错**
      if (parent && parent.path.startsWith(org.path)) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `不能把「${org.name}」移到它自己的下级「${parent.name}」下面`,
          "那会形成父子环：树与'含下级'的判定都会失去意义，而检索不会报错"
        )
      }
      const oldPrefix = org.path
      const newPrefix = `${parent ? parent.path : '/'}${org.code}/`
      org.parentId = parentId
      // 子树跟着走（自己 + 后代，一次前缀替换 —— 与后端 rewriteSubtreePath 同语义）
      orgs
        .filter((o) => o.path.startsWith(oldPrefix))
        .forEach((o) => {
          o.path = newPrefix + o.path.slice(oldPrefix.length)
        })
      return org
    }
  ],
  [
    'DELETE',
    '/orgs/:id',
    ({ path, token }) => {
      requireManage(token)
      const org = findById(path.id)
      if (!org) throw new ApiError(ErrorCode.NOT_FOUND, '部门不存在', '请刷新后重试')
      if (orgs.some((o) => o.path.startsWith(org.path) && o.id !== org.id)) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `「${org.name}」还有下级部门，不能删除`,
          '请先处理下级部门；如果只是要停止使用，请**停用**它（停用不会影响既有共享）'
        )
      }
      orgs.splice(orgs.indexOf(org), 1)
      return null
    }
  ]
]
