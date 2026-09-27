import { request } from '@/lib/http'
import type { ClassificationLevel, DataScope, PageQuery, PageResult } from './types'

/* ────────────────────────── 契约类型 ────────────────────────── */

export interface LoginRequest {
  username: string
  password: string
  /**
   * 验证码 id 与用户填写的字符。等保要求登录必须带验证码，所以后端默认强制校验。
   *
   * ★ 字段保持**可选**：冻结契约只加可选字段，而且"是否强制"是后端开关
   * （`app.security.captcha.enabled`）—— 关掉时这两个字段可以不传。
   */
  captchaId?: string
  captchaCode?: string
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
  /** 角色声明的数据范围（07 §3.2）。可选 —— 后端未下发时前端按权限点降级 */
  dataScope?: DataScope
}

export interface Role {
  id: string
  name: string
  code: string
  memberCount: number
  permissions: string[]
  /**
   * 数据范围（07 §3.2）。"能不能做这个动作"是权限点，"能看到哪些数据"是这个。
   * 可选 —— 冻结契约下只加可选字段，前端不读也照常工作。
   */
  dataScope?: DataScope
  /** 范围规则的逃生口（07 §8.3）：结构化规则字符串，MVP 留空且不解析 */
  dataScopeRule?: string
  /**
   * ★ 这个角色**能看到**的最高密级（clearance，13 §8.3）。
   *
   * ⚠️ 注意与 `KnowledgeBase.maxClassification` **正交**：那个是"这个库允许装多密的"（ceiling，
   * 入库校验用），这个是"这个人能看多密的"。两者混成一个字段会让"库允许装但我不该看"无处表达。
   *
   * 可选 —— 冻结契约下只加可选字段；后端未下发时前端不该据此做安全判断。
   */
  maxClassification?: ClassificationLevel
  /** ★ 这个角色可涉的密级标签（无序，如 财务 / 人事） */
  labels?: string[]
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

/** 图形验证码。`image` 是 data URL，前端直接塞进 `<img :src="captcha.image">` */
export interface Captcha {
  id: string
  image: string
}

/** 取一张新验证码。★ 每次调用都会作废此前发出的验证码（避免囤积一批再暴力试） */
export const getCaptcha = () => request<Captcha>({ method: 'GET', url: '/auth/captcha' })

export const login = (body: LoginRequest) =>
  request<LoginResult>({ method: 'POST', url: '/auth/login', body, skipAuthRetry: true })

export const logout = () => request<void>({ method: 'POST', url: '/auth/logout' })

export const getCurrentUser = () => request<CurrentUser>({ method: 'GET', url: '/users/me' })

export interface ChangePasswordBody {
  oldPassword: string
  newPassword: string
}

export const changePassword = (body: ChangePasswordBody) =>
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

/** 改角色的数据范围（07 §3.2 三档）。与权限点分开保存 —— 两者语义不同、受众不同 */
export const updateRoleDataScope = (id: string, dataScope: DataScope, dataScopeRule?: string) =>
  request<Role>({
    method: 'PUT',
    url: '/roles/:id/data-scope',
    path: { id },
    body: { dataScope, dataScopeRule }
  })

/** 权限点全量清单。新增功能时后端自动多出一项，前端无需改代码 */
export const listPermissions = () => request<PermissionItem[]>({ method: 'GET', url: '/permissions' })
