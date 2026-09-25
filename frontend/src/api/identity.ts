import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResult {
  accessToken: string
  refreshToken: string
  /** 首次登录必须改密，前端据此强制跳改密页 */
  mustChangePassword: boolean
}

export interface User {
  id: string
  username: string
  displayName: string
  roleId: string
  roleName: string
  status: '正常' | '停用' | '待审核'
  lastLoginAt: string | null
}

export interface CurrentUser {
  id: string
  username: string
  displayName: string
  roleName: string
  /** 权限点。后端在登录后一次性下发，前端不再据此渲染，只用于按钮显隐 */
  permissions: string[]
}

export interface Role {
  id: string
  name: string
  code: string
  memberCount: number
  permissions: string[]
}

/** 权限点自描述，供「角色管理」页直接渲染分组勾选 */
export interface PermissionItem {
  code: string
  label: string
  /** 归属模块，用于分组展示 */
  module: string
}

export interface UserQuery extends PageQuery {
  roleId?: string
  status?: User['status']
}

export interface UpsertUserBody {
  username: string
  displayName: string
  roleId: string
  password?: string
}

/* ────────────────────────── 接口 ────────────────────────── */

export const login = (body: LoginRequest) =>
  request<LoginResult>({ method: 'POST', url: '/auth/login', body, skipAuthRetry: true })

export const logout = () => request<void>({ method: 'POST', url: '/auth/logout' })

export const getCurrentUser = () => request<CurrentUser>({ method: 'GET', url: '/users/me' })

export const changePassword = (body: { oldPassword: string; newPassword: string }) =>
  request<void>({ method: 'PUT', url: '/users/me/password', body })

export const listUsers = (query: UserQuery = {}) =>
  request<PageResult<User>>({ method: 'GET', url: '/users', query })

export const createUser = (body: UpsertUserBody) =>
  request<User>({ method: 'POST', url: '/users', body })

export const updateUser = (id: string, body: UpsertUserBody) =>
  request<User>({ method: 'PUT', url: '/users/:id', path: { id }, body })

export const deleteUser = (id: string) =>
  request<void>({ method: 'DELETE', url: '/users/:id', path: { id } })

export const listRoles = () => request<Role[]>({ method: 'GET', url: '/roles' })

export const updateRolePermissions = (id: string, permissions: string[]) =>
  request<Role>({ method: 'PUT', url: '/roles/:id/permissions', path: { id }, body: { permissions } })

/** 权限点全量清单。新增功能时后端自动多出一项，前端无需改代码 */
export const listPermissions = () => request<PermissionItem[]>({ method: 'GET', url: '/permissions' })
