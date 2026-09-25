import { defineStore } from 'pinia'
import { identityApi } from '@/api'
import type { CurrentUser } from '@/api/identity'
import type { DataScope } from '@/api/types'
import { PERM } from '@/constants/permissions'
import { clearTokens, getToken, setTokens } from '@/lib/token'

/**
 * 角色声明的数据范围（`Role.dataScope`，07 §3.2）。
 *
 * 契约那边在加这个可选字段，而 `CurrentUser` 是否一并下发由后端决定 ——
 * 所以这里按**可选**读：字段没落地时返回 `undefined`，交给下面的降级，
 * 而不是在前端自己编一个默认值（编一个就等于把真值永远盖住）。
 */
type WithDeclaredScope = { dataScope?: DataScope }

const declaredScope = (user: CurrentUser | null): DataScope | undefined => {
  const raw = (user as WithDeclaredScope | null)?.dataScope
  return raw === 'own' || raw === 'shared' || raw === 'all' ? raw : undefined
}

/**
 * 登录态。
 *
 * 只存「当前是谁 + 有哪些权限点」，不存任何业务数据 ——
 * 原项目把字典、标签页、上传临时文件都塞进全局 store，
 * 结果是任何一次退出登录都要靠「记得清干净」。
 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    user: null as CurrentUser | null,
    loading: false
  }),

  getters: {
    loggedIn: (state) => state.user !== null,
    displayName: (state) => state.user?.displayName ?? '',
    roleName: (state) => state.user?.roleName ?? '',

    /**
     * 当前角色的**数据范围**（07 §二/§三）：只有自己的 / 自己+被共享的 / 全平台。
     *
     * 来源只有一个：角色自带的 `dataScope`。它还没下发时按权限点降级 ——
     * 能管全平台资产（知识库治理 / 应用配置）的角色是 `all`，其余是 `shared`
     * （对应 07 §2.2 的「消费者」）。降级是**同一个判据的替代品**，两者不会同时生效，
     * 也就不会出现「角色说 shared、权限点说 all」这种没人知道该信谁的场面。
     *
     * ★ 前端的数据范围**只是 UX**，不是安全边界 —— 真正的边界在后端（07 §3.3）：
     * 这里少渲染一个模块 ≠ 后端会拒绝直连接口，可见性判定只在后端实现一次。
     */
    dataScope(state): DataScope {
      const declared = declaredScope(state.user)
      if (declared) return declared
      const permissions = state.user?.permissions ?? []
      const platformWide =
        permissions.includes(PERM.knowledgeManage) || permissions.includes(PERM.assistantEdit)
      return platformWide ? 'all' : 'shared'
    },

    /** 权限不足时按钮不渲染，而不是渲染后报错 */
    can: (state) => (code: string) => state.user?.permissions.includes(code) ?? false
  },

  actions: {
    async login(username: string, password: string) {
      this.loading = true
      try {
        const result = await identityApi.login({ username, password })
        setTokens(result.accessToken, result.refreshToken)
        await this.loadProfile()
        return result
      } finally {
        this.loading = false
      }
    },

    /** 刷新页面后用它把登录态找回来 */
    async loadProfile() {
      if (!getToken()) return null
      this.user = await identityApi.getCurrentUser()
      return this.user
    },

    async logout() {
      try {
        await identityApi.logout()
      } finally {
        clearTokens()
        this.user = null
      }
    }
  }
})
