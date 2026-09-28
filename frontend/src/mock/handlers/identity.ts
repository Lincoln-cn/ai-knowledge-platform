import { ApiError, ErrorCode } from '@/api/types'
import type { CurrentUser, LoginRequest, User } from '@/api/identity'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { permissions, roles, userFromToken, users } from '../fixtures/identity'

/**
 * 未传口令时的默认初始口令。真源在后端 `UserService.DEFAULT_INITIAL_PASSWORD` ——
 * mock 必须与之对齐：否则 mock 模式下管理者按界面提示告知新人的口令会登录失败，
 * 这类前后端口径差异会被 mock 掩盖，直到联调才炸。
 */
const DEFAULT_INITIAL_PASSWORD = 'ChangeMe@2026'

/**
 * 口令强度校验 —— **真源是后端** `identity/domain/PasswordPolicy.java`（`violationOf`）。
 *
 * ★ mock 必须严格到与后端同一口径：若 mock 只查「至少 8 位」，弱口令会在 mock
 *   模式下被放过、直到真联调才被后端拒绝 —— mock 就掩盖了策略差异，而不是提前
 *   暴露它。两端的失败原因文案也逐字对齐，排查时不会面对两套话术。
 *
 * 规则（与 PasswordPolicy 一一对应）：
 *   1. 非空
 *   2. 长度 8–64（上限是 BCrypt 72 字节静默截断的诚实性口子）
 *   3. 大写 / 小写 / 数字 / 特殊四类中至少命中 3 类
 *   4. 不含账号名（忽略大小写）
 *
 * @returns 违规原因；合格时 null。只在「写入口令」的端点调用（改密 / 新建 / 重置）——
 *   登录比对的是已存口令，不走强度校验（演示账号 `123456` 因此仍可登录）。
 */
function passwordPolicyViolation(
  rawPassword: string | null | undefined,
  username: string | null | undefined
): string | null {
  if (rawPassword == null || rawPassword.trim() === '') {
    return '密码不能为空'
  }
  if (rawPassword.length < 8) {
    return '密码长度至少 8 位'
  }
  if (rawPassword.length > 64) {
    return '密码长度不能超过 64 位'
  }
  if (characterKinds(rawPassword) < 3) {
    return '密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类'
  }
  if (
    username != null &&
    username.trim() !== '' &&
    rawPassword.toLowerCase().includes(username.toLowerCase())
  ) {
    return '密码不能包含账号名'
  }
  return null
}

/** 命中的字符类别数（大写 / 小写 / 数字 / 特殊）—— 与 PasswordPolicy.characterKinds 同口径 */
function characterKinds(rawPassword: string): number {
  let upper = false
  let lower = false
  let digit = false
  let other = false
  for (const c of rawPassword) {
    if (/\p{Lu}/u.test(c)) upper = true
    else if (/\p{Ll}/u.test(c)) lower = true
    else if (/\p{Nd}/u.test(c)) digit = true
    else other = true
  }
  return (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0)
}

/**
 * 不合格就抛 —— 码值与提示也与后端 `BusinessException(PASSWORD_TOO_WEAK, reason, hint)` 同口径。
 */
function assertPasswordPolicy(rawPassword: string, username: string): void {
  const reason = passwordPolicyViolation(rawPassword, username)
  if (reason) {
    throw new ApiError(
      ErrorCode.PASSWORD_TOO_WEAK,
      reason,
      '密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类，且不含账号名'
    )
  }
}

/**
 * 「令牌 → 用户」的约定只有一处实现（夹具层），数据权限判定（`mock/policy.ts`）也用它 ——
 * 两处各写一遍迟早会漂移。
 */
const toCurrentUser = (user: (typeof users)[number]): CurrentUser => {
  const role = roles.find((r) => r.id === user.roleId)
  return {
    id: user.id,
    username: user.username,
    displayName: user.displayName,
    roleName: user.roleName,
    permissions: role?.permissions ?? [],
    dataScope: role?.dataScope
  }
}

const stripPassword = (user: (typeof users)[number]): User => {
  const { password: _password, ...rest } = user
  return rest
}

/**
 * mock 用的验证码图片。
 *
 * ★ mock 里**不校验**验证码（它只是一张能显示出来的图），目的是让页面在
 * {@code VITE_USE_MOCK=true} 下与真实后端长得一样 —— 有输入框、有可点的图。
 * "点了换一张"这件事在 mock 下也是真的会换（每次返回的图略有不同）。
 * 真正的校验逻辑由后端的 {@code ComplianceTest} 对着真后端验。
 */
function mockCaptchaImage(): string {
  const code = 'A7K3'
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40">` +
    `<rect width="120" height="40" fill="#f7f7f5"/>` +
    `<path d="M4 30 L116 12 M8 10 L112 32" stroke="#d8d8d4" stroke-width="1" fill="none"/>` +
    `<text x="14" y="29" font-family="monospace" font-size="24" font-weight="bold" ` +
    `fill="#3a3a3a" letter-spacing="5">${code}</text>` +
    `</svg>`
  return `data:image/svg+xml;base64,${btoa(svg)}`
}

export const identityRoutes: MockRoute[] = [
  // 登录验证码（等保要求）。真后端出的是 Java2D 渲染的 PNG，形状一致。
  // `required` 恒 true：mock 模式要与「验证码开启」的真后端长得一样。
  ['GET', '/auth/captcha', () => ({ id: `mock_captcha_${Date.now()}`, image: mockCaptchaImage(), required: true })],
  [
    'POST',
    '/auth/login',
    ({ body }) => {
      const { username, password } = body as LoginRequest
      const user = users.find((u) => u.username === username)

      // 失败原因要可读，不返回「用户名或密码错误」这种无法行动的话
      if (!user) throw new ApiError(ErrorCode.UNAUTHORIZED, `账号「${username}」不存在`)
      if (user.password !== password) throw new ApiError(ErrorCode.UNAUTHORIZED, '密码不正确')
      if (user.status === '停用') throw new ApiError(ErrorCode.FORBIDDEN, '该账号已停用，请联系管理员')
      if (user.status === '待审核') throw new ApiError(ErrorCode.FORBIDDEN, '该账号待审核，尚未开通')

      user.lastLoginAt = new Date().toISOString().slice(0, 16).replace('T', ' ')

      return {
        accessToken: `mock_access_${user.id}`,
        refreshToken: `mock_refresh_${user.id}`,
        mustChangePassword: user.mustChangePassword
      }
    }
  ],

  ['POST', '/auth/logout', () => undefined],

  [
    'GET',
    '/users/me',
    ({ token }) => {
      const user = userFromToken(token)
      if (!user) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      return toCurrentUser(user)
    }
  ],

  [
    'PUT',
    '/users/me/password',
    ({ token, body }) => {
      const user = userFromToken(token)
      if (!user) throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
      const { oldPassword, newPassword } = body as { oldPassword: string; newPassword: string }
      if (user.password !== oldPassword) throw new ApiError(4001, '原密码不正确')
      // 与后端 PasswordPolicy 同口径 —— 不是「至少 8 位」就完事
      assertPasswordPolicy(newPassword, user.username)
      user.password = newPassword
      user.mustChangePassword = false
      return undefined
    }
  ],

  [
    'GET',
    '/users',
    ({ query }) => {
      const keyword = String(query.keyword ?? '')
      const roleId = query.roleId ? String(query.roleId) : ''
      const status = query.status ? String(query.status) : ''

      const list = users
        .filter((u) => (!keyword || u.username.includes(keyword) || u.displayName.includes(keyword)))
        .filter((u) => (!roleId || u.roleId === roleId))
        .filter((u) => (!status || u.status === status))
        .map(stripPassword)

      return paginate(list, query)
    }
  ],

  [
    'POST',
    '/users',
    ({ body }) => {
      const input = body as { username: string; displayName: string; roleId: string; password?: string }
      if (users.some((u) => u.username === input.username)) {
        throw new ApiError(4002, `账号「${input.username}」已存在`)
      }
      const role = roles.find((r) => r.id === input.roleId)
      if (!role) throw new ApiError(4002, '请选择一个有效的角色')

      // 指定口令时才校验强度；未指定走 DEFAULT_INITIAL_PASSWORD（它本身已满足策略）
      if (input.password) assertPasswordPolicy(input.password, input.username)

      const created = {
        id: `u${Date.now()}`,
        username: input.username,
        password: input.password || DEFAULT_INITIAL_PASSWORD,
        displayName: input.displayName,
        roleId: role.id,
        roleName: role.name,
        status: '正常' as const,
        lastLoginAt: null,
        // 用默认密码建的号必须改密 —— 契约里 `mustChangePassword` 的语义就是这个
        mustChangePassword: !input.password
      }
      users.push(created)
      const result = stripPassword(created)
      // 契约（`User.initialPassword`）：仅「创建且未指定 password」时回一次，其余路径不下发
      return input.password ? result : { ...result, initialPassword: DEFAULT_INITIAL_PASSWORD }
    }
  ],

  [
    'PUT',
    '/users/:id',
    ({ path, body, token }) => {
      const user = users.find((u) => u.id === path.id)
      if (!user) throw new ApiError(ErrorCode.NOT_FOUND, '用户不存在')
      const input = body as { displayName: string; roleId: string; password?: string }

      const role = roles.find((r) => r.id === input.roleId)
      if (!role) throw new ApiError(4002, '请选择一个有效的角色')

      // 危险操作不可自毁：改自己的角色 = 把自己踢出管理位（删自己已挡，改自己也要挡）
      const self = userFromToken(token)
      if (self && user.id === self.id && role.id !== user.roleId) {
        throw new ApiError(4005, '不能修改自己的角色 —— 请让另一位管理员操作，避免自锁')
      }

      user.displayName = input.displayName
      user.roleId = role.id
      user.roleName = role.name
      if (input.password) {
        // 重置口令与改密同一套策略（真源 PasswordPolicy），否则弱口令会被 mock 放行
        assertPasswordPolicy(input.password, user.username)
        user.password = input.password
        user.mustChangePassword = false
      }
      return stripPassword(user)
    }
  ],

  [
    'DELETE',
    '/users/:id',
    ({ path, token }) => {
      const self = userFromToken(token)
      if (self && path.id === self.id) throw new ApiError(4003, '不能删除当前登录的账号')
      const index = users.findIndex((u) => u.id === path.id)
      if (index < 0) throw new ApiError(ErrorCode.NOT_FOUND, '用户不存在')
      if (users[index].username === 'admin') throw new ApiError(4003, '内置管理员账号不可删除')
      users.splice(index, 1)
      return undefined
    }
  ],

  ['GET', '/roles', () => roles],

  [
    'PUT',
    '/roles/:id/permissions',
    ({ path, body, token }) => {
      const role = roles.find((r) => r.id === path.id)
      if (!role) throw new ApiError(ErrorCode.NOT_FOUND, '角色不存在')
      if (role.code === 'ADMIN') throw new ApiError(4004, '管理员角色权限不可裁剪')

      const next = (body as { permissions: string[] }).permissions

      // 危险操作不可自毁：不许把「当前用户所属角色」的 system:manage 裁掉
      const self = userFromToken(token)
      const selfRole = self ? users.find((u) => u.id === self.id)?.roleId : undefined
      const had = role.permissions.includes('system:manage')
      const keeps = next.includes('system:manage')
      if (selfRole === role.id && had && !keeps) {
        throw new ApiError(4005, '不能裁掉自己所属角色的「账号与权限」权限 —— 避免自锁')
      }

      role.permissions = next
      return role
    }
  ],

  [
    'PUT',
    '/roles/:id/data-scope',
    ({ path, body }) => {
      const role = roles.find((r) => r.id === path.id)
      if (!role) throw new ApiError(ErrorCode.NOT_FOUND, '角色不存在')
      if (role.code === 'ADMIN') throw new ApiError(4004, '管理员角色的数据范围固定为全平台，不可修改')

      const { dataScope, dataScopeRule } = body as { dataScope?: string; dataScopeRule?: string }
      if (dataScope !== 'own' && dataScope !== 'shared' && dataScope !== 'all') {
        throw new ApiError(4006, '数据范围只能是 own / shared / all')
      }
      role.dataScope = dataScope
      role.dataScopeRule = dataScopeRule ?? ''
      return role
    }
  ],

  ['GET', '/permissions', () => permissions]
]
