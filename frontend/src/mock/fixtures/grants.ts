import { PERM } from '@/constants/permissions'
import type { Grant } from '@/api/types'

/**
 * 共享授权样本（07 §8.1 的 `Grant` 形状：主体 + 动作，不是 `{ userIds, roleCodes }`）。
 *
 * **为什么单独一个文件**：`verify_refs.py` 会把夹具文件里的 `id: 'xxx'` 当作"本模块定义的
 * 实体 id"收集，而主体 id（`u4` / `r4`）写在知识库/应用夹具里会被误读成知识库 id、应用 id。
 * 主体与资源放两个文件，两侧的 id 空间就不互相污染了。
 *
 * MVP 只填 `user` / `role` 两种主体 —— 加"共享给部门"只需多一个 `kind`（07 §8.1）。
 * 演示账号：admin=u1 · zhangyun=u2（知识运营）· liwei=u3（应用配置）· chenjun=u4（普通用户）
 */

/** 共享给「普通用户」角色（r4）：知识库只读 —— 该角色下所有人（含 chenjun）可见 */
export const kbSharedWithUserRole: Grant[] = [
  { subject: { kind: 'ROLE', id: 'r4' }, actions: [PERM.knowledgeView] }
]

/** 共享给 chenjun 本人：知识库只读 —— 演示 `user` 主体与 `role` 主体并存 */
export const kbSharedWithChenjun: Grant[] = [
  { subject: { kind: 'USER', id: 'u4' }, actions: [PERM.knowledgeView] }
]

/** 共享给「普通用户」角色（r4）：应用可用（只读配置，故只授予 `assistant:view`） */
export const assistantSharedWithUserRole: Grant[] = [
  { subject: { kind: 'ROLE', id: 'r4' }, actions: [PERM.assistantView] }
]
