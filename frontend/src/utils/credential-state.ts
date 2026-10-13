import type { AccessCredential } from '@/api/assistants'

/**
 * ★ **一条访问凭证的"为什么不能用"**——四态分开报，这是本文件的全部职责。
 *
 * 后端把四种原因分开给（撤销 `40402` / 过期 `40403` / 停用 `40404` / 认不出 `40401`），
 * 所以界面**不许**把它们合并成一句"链接无效"：四态的**下一步动作完全不同**。
 *
 * | 态 | 还能不能救 | 下一步 |
 * |---|---|---|
 * | `REVOKED` | ★ **不能（终态）** | 只能重新生成一条 |
 * | `DISABLED` | 能（可再启用） | 请管理员启用 |
 * | `EXPIRED` | 不能（有效期是创建时定的） | 重新生成一条 |
 * | `ACTIVE` | — | 正常 |
 *
 * ★ 判定优先级 = **不可逆优先**（`REVOKED` > `DISABLED` > `EXPIRED`）。
 *   一条"既被停用又已过期"的凭证，先要回答的是"还能不能启用回来" ——
 *   说"已过期"会让管理员去等一个永远不会到来的时间点。
 */
export type CredentialStateKind = 'ACTIVE' | 'REVOKED' | 'DISABLED' | 'EXPIRED'

export interface CredentialState {
  kind: CredentialStateKind
  /** 状态标签（界面唯一文案出口：徽标、筛选、公开页标题都用它） */
  label: string
  /** 一句"为什么" */
  reason: string
  /** 一句"怎么办" */
  action: string
}

const STATE_TEXT: Record<CredentialStateKind, { label: string; reason: string; action: string }> = {
  ACTIVE: {
    label: '启用',
    reason: '凭证有效，持有链接的人现在可以免登录访问。',
    action: '不需要处理；不再需要时停用或撤销。'
  },
  REVOKED: {
    label: '已撤销',
    reason: '撤销是终态：撤销后不能重新启用。',
    action: '只能重新生成一条新凭证，并把新链接给到对方。'
  },
  DISABLED: {
    label: '已停用',
    reason: '凭证被人为停用，链接现在打不开；凭证本身还在。',
    action: '确认还需要它，就重新启用。'
  },
  EXPIRED: {
    label: '已过期',
    reason: '已超过创建时设定的有效期。',
    action: '只能重新生成一条新凭证（有效期不能改）。'
  }
}

/** 判断是否已过期。`now` 显式传入：列表渲染与公开面校验要用同一个"现在" */
export const isCredentialExpired = (
  cred: Pick<AccessCredential, 'expiresAt'>,
  now: number = Date.now()
): boolean => !!cred.expiresAt && new Date(cred.expiresAt).getTime() < now

/**
 * 由凭证自身推导四态。
 * ★ 前端**不新增字段**来记状态 —— 状态是 `revokedAt` / `enabled` / `expiresAt` 的函数，
 *   多存一份就会与后端不一致（这正是"契约里有一个域 ≠ 这个域被接线了"的同族错误）。
 */
export function credentialState(
  cred: Pick<AccessCredential, 'enabled' | 'expiresAt' | 'revokedAt'>,
  now: number = Date.now()
): CredentialState {
  if (cred.revokedAt) return { kind: 'REVOKED', ...STATE_TEXT.REVOKED }
  if (!cred.enabled) return { kind: 'DISABLED', ...STATE_TEXT.DISABLED }
  if (isCredentialExpired(cred, now)) return { kind: 'EXPIRED', ...STATE_TEXT.EXPIRED }
  return { kind: 'ACTIVE', ...STATE_TEXT.ACTIVE }
}

/** `el-tag` 的语义色。四态各自一色，别让"已撤销"和"已停用"长得一样 */
export const credentialTagType = (
  kind: CredentialStateKind
): 'success' | 'info' | 'warning' | 'danger' =>
  kind === 'ACTIVE' ? 'success' : kind === 'DISABLED' ? 'info' : kind === 'EXPIRED' ? 'warning' : 'danger'

/**
 * 一句说清**冻结语义**（`13 §5.2`）。
 *
 * ★ 措辞照 §5.2：① **创建时冻结** ② **每次访问实时再滤**；⚠️ 不得因为"链接创建过"就放行。
 *   这不是我们自己发明的解释 —— 用户会照着这句话理解安全边界，所以它必须与设计册同义。
 *
 * ★ 上限未知时**不说「不高于 X」**：生成前那一刻还没有冻结值，写一个占位字母会让人
 *   以为上限可能就是"X"。此时改成"不高于它的密级上限"，并让紧邻的一句写明上限怎么来。
 *
 * @param ceilingLabel 该链接创建时冻结的密级上限（已知时传）
 */
export function freezeNote(ceilingLabel?: string): string {
  const subject = ceilingLabel ? `不高于「${ceilingLabel}」密级` : '不高于它的密级上限'
  return `这条链接只能看到${subject}的内容；被分享内容升密后，该链接立即失效。`
}

/**
 * 一句说清**上限为什么改不了**（`13 §5.2` 的入参口径：冻结值由后端按分享者当时的
 * clearance 自己取，**不接受前端传**）。
 */
export function ceilingFrozenNote(ceilingLabel?: string): string {
  const head = ceilingLabel
    ? `这条链接的密级上限已冻结为「${ceilingLabel}」`
    : '这条链接的密级上限在生成时冻结'
  return `${head} —— 它等于你的密级上限，由系统按你此刻的权限自动记录，创建后不可更改。`
}
