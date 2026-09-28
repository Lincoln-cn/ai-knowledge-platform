import { ApiError, ErrorCode } from '@/api/types'
import type { DataScope, OwnedResource, Subject } from '@/api/types'
import { roles, userFromToken } from './fixtures/identity'

/**
 * 数据权限判定的**唯一入口**（mock 侧 ≈ 后端侧那一个判定）。
 *
 * 设计真源：`docs/系统设计方案/07-角色与数据权限模型.md`
 *  - §3.3 判定全工程只有一条：列表 / 单条 / **写操作的前置校验**三处都走它
 *  - §8.2 判定接收「主体集合 S」，不接收「当前用户」—— 将来加部门只是往 S 里多塞一项
 *  - §8.5 判定必须可下推为 SQL 谓词（性能口），所以形状写成集合运算而不是遍历谁
 *
 * ```sql
 * WHERE owner_id = :me
 *    OR visibility = 'PUBLIC'
 *    OR EXISTS (SELECT 1 FROM grants g
 *                WHERE g.resource_id = r.id
 *                  AND g.subject IN (:subjects)
 *                  AND :action = ANY(g.actions))
 * ```
 *
 * ⚠️ 前端页面上也有一份按 `dataScope` 少渲染几个入口的**镜像**，但那是 UX 不是边界：
 * 隐藏入口挡不住直接调接口（§3.3 硬规矩 2），**这里才是边界**。
 * 判定收敛到一处还有第二个理由（§8.7）：将来挂审计只有一个挂载点。
 */

/** 判定只依赖 `OwnedResource` 的三个字段，而冻结契约里它们是**可选**的 —— 所以按可选收 */
export type ResourceLike = Partial<OwnedResource>

/**
 * 主体集合 S。
 *
 * 现在只装两项 ——「该用户」与「其角色」；将来加部门 / 项目组，只是往 `subjects` 里
 * 多塞一项，**判定本身一个字不改**（§8.2）。这也是外部协作者的接入点。
 */
export interface SubjectSet {
  /** 参与判定的全部主体（S 的元素） */
  subjects: Subject[]
  /** 主体 id 全集：供 `资源.ownerId ∈ S.ids` 与 `mine=true` 用 */
  ids: Set<string>
  /** `kind:id` 规范化键：供 `∃ g.subject ∈ S` 用（集合成员要 kind 与 id 都相等） */
  keys: Set<string>
  /** 主体权限点并集：供 `public` 分支判定「动作 ∈ 主体权限点」 */
  permissions: Set<string>
  /** 主体的数据范围档位：任一为 `'ALL'` 即全平台可见 */
  scopes: DataScope[]
}

/** 主体的集合键。部门「3」与角色「3」不是同一个主体 */
export const subjectKey = (subject: Subject): string => `${subject.kind}:${subject.id}`

/** 通用构造：将来加一类主体（部门 / 项目组）复用它，不必改判定 */
export function subjectSetOf(
  subjects: Subject[],
  permissions: Iterable<string> = [],
  scopes: DataScope[] = []
): SubjectSet {
  return {
    subjects,
    ids: new Set(subjects.map((s) => s.id)),
    keys: new Set(subjects.map(subjectKey)),
    permissions: new Set(permissions),
    scopes
  }
}

/**
 * 主体集合 S 的**现在**：{该用户, 其角色} 两项。
 *
 * 角色带进来两样东西：权限点（`PUBLIC` 分支要查）与数据范围档位（`'ALL'` 短路）。
 */
export function subjectsOf(user: { id: string; roleId: string }): SubjectSet {
  const role = roles.find((r) => r.id === user.roleId)
  const subjects: Subject[] = [{ kind: 'USER', id: user.id }]
  if (role) subjects.push({ kind: 'ROLE', id: role.id })
  return subjectSetOf(subjects, role?.permissions ?? [], role?.dataScope ? [role.dataScope] : [])
}

/**
 * mock 传输层的「我是谁」：令牌 → 用户 → S（真实后端从 Authorization 头解析 JWT）。
 *
 * 拿不到人一律 **401**：宁可报"登录过期"，也不要静默按空主体集合返回空列表 ——
 * 那会把"没登录"伪装成"没数据"，是最难查的一类现象。
 */
export function currentSubjects(token: string): SubjectSet {
  const user = userFromToken(token)
  if (!user) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
  return subjectsOf(user)
}

/** S 里那个「本人」的 id。新建资源写 `ownerId` 只能从这里取 —— 绝不从请求体取 */
export function selfId(subjects: SubjectSet): string {
  const self = subjects.subjects.find((s) => s.kind === 'USER')
  if (!self) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
  return self.id
}

/**
 * `可见(资源, 主体集合 S, 动作)` —— §8.2 的四项，书写顺序即短路顺序：
 *
 *  ① `scope(角色) === 'ALL'`                            → 全平台
 *  ② `资源.ownerId ∈ S.ids`                             → 我建的
 *  ③ `∃ g ∈ grants: g.subject ∈ S ∧ 动作 ∈ g.actions`    → 共享给我的
 *  ④ `visibility === 'PUBLIC' ∧ 动作 ∈ 主体权限点`       → 公开的（登录用户里人人可见）
 *
 * 动作取**现有权限点词汇**（`src/constants/permissions.ts`）：同一份资源在"看"与"改"
 * 上要分别问一次，所以调用方每次都必须显式给动作，共享里授予的 `actions` 也用同一套词。
 *
 * 三处刻意的"没做"，都不是漏：
 *  - `'OWN'` 档不做收窄：§8.2 只对 `'ALL'` 短路（`OWN` / `SHARED` 的差别留给页面形态），
 *    实现严格照 §8.2，不自行加一条判定
 *  - ③ 不再额外要求 `visibility === 'SHARED'`：与 §8.2 逐字一致；夹具也只把 grants 挂在 shared 上
 *  - `grants[].expiresAt` / `canDelegate` 不解析：§8.1 的形状口子，MVP 不填也不读
 */
export function visibleTo(resource: ResourceLike, subjects: SubjectSet, action: string): boolean {
  if (subjects.scopes.includes('ALL')) return true
  if (resource.ownerId && subjects.ids.has(resource.ownerId)) return true
  for (const grant of resource.grants ?? []) {
    if (!grant.actions.includes(action)) continue
    if (subjects.keys.has(subjectKey(grant.subject))) return true
  }
  if (resource.visibility === 'PUBLIC' && subjects.permissions.has(action)) return true
  return false
}

/**
 * 能直接用在 `Array.filter` 上的形式：
 *
 * ```ts
 * const list = knowledgeBases.filter(visiblePredicate(S, PERM.knowledgeView))
 * ```
 *
 * `mine` 对应列表接口的可选查询参数 `mine=true`（§六 阶段 1）—— 只看归属在 S 名下的。
 */
export function visiblePredicate(
  subjects: SubjectSet,
  action: string,
  options: { mine?: boolean } = {}
): (resource: ResourceLike) => boolean {
  return (resource) => {
    if (!visibleTo(resource, subjects, action)) return false
    if (!options.mine) return true
    return !!resource.ownerId && subjects.ids.has(resource.ownerId)
  }
}

/**
 * 单条取数**与写操作前置**共用这一条（§3.3 硬规矩 1：写操作也要走，
 * 否则「看不见但能改」这种越权最难发现）。
 *
 * ⚠️ 看不见一律 **404，不是 403**（§3.3 硬规矩 3）—— 403 等于告诉对方"这个 id 存在"。
 * 所以"查不到"与"看不见"在这里合并成同一个应答，连报文都不区分。
 */
export function requireVisible<T extends ResourceLike>(
  resource: T | undefined,
  subjects: SubjectSet,
  action: string,
  label: string,
  id: string
): T {
  if (!resource || !visibleTo(resource, subjects, action)) {
    throw new ApiError(ErrorCode.NOT_FOUND, `${label}「${id}」不存在`)
  }
  return resource
}
