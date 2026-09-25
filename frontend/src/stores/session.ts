import { defineStore } from 'pinia'
import { identityApi } from '@/api'
import type { CurrentUser } from '@/api/identity'
import { clearTokens, getToken, setTokens } from '@/lib/token'

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
