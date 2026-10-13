import { ApiError, ErrorCode } from '@/api/types'

/**
 * 未登录公开页的**失效/无权限态**文案（唯一出口）。
 *
 * ★ 为什么按 `code` 分四态，而不是直接用后端那句话：
 *   后端把四种原因**分开**报（`40401` 认不出 / `40402` 已撤销 / `40403` 已过期 /
 *   `40404` 已停用），四态的**下一步动作完全不同**。页面只显示后端原文（"访问凭证已
 *   被撤销"）时，访问者能读到的只有"坏了"，读不到"该怎么办"；而四态合并成一句
 *   "链接无效"更糟 —— 已停用是可以恢复的，已撤销不能，让人按错的假设去等，是
 *   本项目头号病（`silent-failure`）的界面版。
 *
 * ★ `code` 是**契约里已有的数值**（`ApiError.code`），读它不新增任何契约字段。
 *   ★★ 2026-10-13（第 16 周末）：`40401`–`40404` 这四个码**已补进契约与后端枚举两侧**
 *   （`ErrorCode.PUBLIC_TOKEN_*`，由 `frontend/check_error_codes.py` 每轮比对），所以下面
 *   **只引用契约常量、不再各处写一遍数字**。第 17 周公开面端点落地时后端必须 emit 这四个码。
 *   ★ `40405` **不在契约里**（按 `13 §5.3` 的 (a) 口径不需要它）⇒ 只有它仍是本文件的字面量，且**只预留给 mock 夹具**。
 */
export interface PublicGateError {
  /** 状态标签：用户一眼看到这是"哪一态" */
  status: string
  /** 一句"为什么" */
  reason: string
  /** 一句"怎么办"（给访问者；括号里给管理员） */
  hint: string
  /** 同一码在应用侧的下一步（凭证面板复用同一张表，避免两处文案漂移） */
  action: string
}

/**
 * 访问凭证 / 分享链接的四态 + 认不出。key = 后端业务码。
 *
 * ★ 前四个来自**契约**（`ErrorCode.PUBLIC_TOKEN_*`）—— 不再在这里写第二遍数字：
 *   写两遍的后果是"改了一处、另一处照旧"，而它不会报错（`#41` 那一族）。
 * ★ `CEILING_RAISED`（`40405`）**不在契约里**：按 `13 §5.3` 的 (a) 口径，
 *   升密后凭证仍有效、链接仍打得开，只是内容被同一个谓词滤掉 ⇒ 不需要第五个码。
 *   它只被 mock 的升密夹具用（`mock/handlers/conversations.ts` 从这里取，避免两处字面量）。
 */
export const PUBLIC_GATE_CODES = {
  /** 认不出这个 token */
  UNKNOWN: ErrorCode.PUBLIC_TOKEN_UNKNOWN,
  /** 已撤销（终态） */
  REVOKED: ErrorCode.PUBLIC_TOKEN_REVOKED,
  /** 已过期 */
  EXPIRED: ErrorCode.PUBLIC_TOKEN_EXPIRED,
  /** 已停用（可再启用） */
  DISABLED: ErrorCode.PUBLIC_TOKEN_DISABLED,
  /** 内容升密 ⇒ 原链接立即失效（`13 §5.3`）。★ **不在契约里、后端也不 emit**（(a) 口径不需要它）；
   *  mock 的升密夹具用它演示，页面这一支必须先在 */
  CEILING_RAISED: 40405
} as const

/** 同族：目标（应用 / 会话）已经不存在了 */
const TARGET_MISSING: PublicGateError = {
  status: '链接已作废',
  reason: '链接本身还是对的，但它指向的应用或会话已经不存在了。',
  hint: '请向分享者索取一条新的链接（管理员可在「应用管理 → 访问凭证」里核对这条凭证还在不在）。',
  action: '目标应用或会话已不存在，这条链接永远打不开了；请重新生成一条给到对方。'
}

const GATE_TEXT: Record<number, PublicGateError> = {
  [PUBLIC_GATE_CODES.UNKNOWN]: {
    status: '链接无法识别',
    reason: '这条公开链接不存在，或链接不完整（复制时被截断、被聊天工具改了字符）。',
    hint: '请向分享者**重新索取**一条完整链接 —— 这类链接无法靠重试恢复。',
    action: '这条凭证不在本应用的记录里，无法启用或撤销；请让访问者拿新链接。'
  },
  [PUBLIC_GATE_CODES.REVOKED]: {
    status: '链接已被撤销',
    reason: '分享者或管理员撤销了这条链接。撤销是终态，不能重新启用。',
    hint: '请向分享者索取一条新链接（管理员可到「应用管理 → 访问凭证」重新生成）。',
    action: '撤销是终态，不能重新启用；只能重新生成一条'
  },
  [PUBLIC_GATE_CODES.EXPIRED]: {
    status: '链接已过期',
    reason: '这条链接超过了生成时设定的有效期；有效期在生成时定下，之后不能改。',
    hint: '请向分享者索取一条新链接（管理员可到「应用管理 → 访问凭证」重新生成）。',
    action: '有效期不能改；只能重新生成一条，并按需要的期限设有效期'
  },
  [PUBLIC_GATE_CODES.DISABLED]: {
    status: '链接已停用',
    reason: '管理员停用了这条链接对应的凭证。凭证还在，链接暂停访问。',
    hint: '请向分享者反馈；确认还需要它的话，管理员可以在「应用管理 → 访问凭证」里重新启用。',
    action: '确认还需使用就重新启用；不再需要则直接撤销。'
  },
  [PUBLIC_GATE_CODES.CEILING_RAISED]: {
    status: '链接已失效（内容已升密）',
    reason: '被分享的内容提升了密级，超出了这条链接冻结的密级上限，原链接按规则立即失效。',
    hint: '请向分享者索取一条新链接；原链接不会因为时间过去而自动恢复。',
    action: '原链接已因升密失效，只能重新生成；新链接的密级上限会按你那一刻的权限重新冻结'
  }
}

/**
 * 把失败的请求翻成可读的三段（状态 / 为什么 / 怎么办）。
 *
 * ★ 兜底不是"链接无效"：那正是本项目被点名的"把不同原因合并成一句话"。读不到码时宁可
 *   照抄后端给的原话（它至少是**这一条**故障的原因），再补一句能做的事。
 */
export function publicGateError(error: unknown): PublicGateError {
  if (error instanceof ApiError) {
    const byCode = GATE_TEXT[error.code]
    if (byCode) return byCode
    // 应用 / 会话被删：与 401/403/404 同族的通用 404，不是凭证四态里的任何一态
    if (error.code === 404) return TARGET_MISSING
    return {
      status: '打不开这条链接',
      reason: error.message || '服务没有说明原因。',
      hint: error.hint || '请把这条链接连同上面的原因反馈给分享者。',
      action: error.hint || '请核对这条凭证的状态后再决定启用或重新生成。'
    }
  }
  if (error instanceof Error && error.message) {
    return {
      status: '打不开这条链接',
      reason: error.message,
      hint: '请稍后重试；若一直打不开，请把这句话反馈给分享者。',
      action: '这次失败不是凭证状态引起的，请先确认服务可用。'
    }
  }
  return {
    status: '打不开这条链接',
    reason: '没能连上服务，也没有拿到失败原因。',
    hint: '请检查网络后重试；若一直打不开，请把这一步反馈给分享者。',
    action: '这次失败不是凭证状态引起的，请先确认服务可用。'
  }
}
