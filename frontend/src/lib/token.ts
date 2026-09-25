/**
 * 令牌存取。
 *
 * 原项目把 access/refresh 令牌散在 vuex、localStorage、cookie 三处，
 * 退出时漏清一处就会带出脏登录态。这里只留一个出口。
 */
const ACCESS = 'access_token'
const REFRESH = 'refresh_token'

export const getToken = () => localStorage.getItem(ACCESS) || ''

export const getRefreshToken = () => localStorage.getItem(REFRESH) || ''

export function setTokens(access: string, refresh?: string) {
  localStorage.setItem(ACCESS, access)
  if (refresh) localStorage.setItem(REFRESH, refresh)
}

export function clearTokens() {
  localStorage.removeItem(ACCESS)
  localStorage.removeItem(REFRESH)
}
