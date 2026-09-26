import { ApiError, ErrorCode } from '@/api/types'
import type { CurrentUser, LoginRequest, User } from '@/api/identity'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { permissions, roles, userFromToken, users } from '../fixtures/identity'

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

export const identityRoutes: MockRoute[] = [
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
      if (newPassword.length < 8) throw new ApiError(4001, '新密码至少 8 位')
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

      const created = {
        id: `u${Date.now()}`,
        username: input.username,
        password: input.password || '123456',
        displayName: input.displayName,
        roleId: role.id,
        roleName: role.name,
        status: '正常' as const,
        lastLoginAt: null,
        // 用默认密码建的号必须改密 —— 契约里 `mustChangePassword` 的语义就是这个
        mustChangePassword: !input.password
      }
      users.push(created)
      return stripPassword(created)
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
