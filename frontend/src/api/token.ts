/**
 * 登录态存储（与 Vue 解耦，供 API 客户端、路由守卫、WebSocket 共用）。
 *
 * 为什么单独一个文件而不是放在 store 里：
 * client.ts 需要在 401 时清理 token，router 需要在守卫里判断登录态；
 * 如果它们都 import store，而 store 又 import client 发起登录请求，就会形成循环依赖。
 * 这里只做 localStorage 读写，不依赖任何业务模块。
 */

const TOKEN_KEY = 'sky-dispatch-token'
const USER_KEY = 'sky-dispatch-user'
const EXPIRES_KEY = 'sky-dispatch-token-exp'

/** 取 token；已过期则顺手清理并返回 null。 */
export function getToken(): string | null {
  const token = localStorage.getItem(TOKEN_KEY)
  if (!token) return null
  const expiresAt = Number(localStorage.getItem(EXPIRES_KEY) || 0)
  if (expiresAt > 0 && expiresAt * 1000 <= Date.now()) {
    clearAuth()
    return null
  }
  return token
}

export function getUsername(): string {
  return localStorage.getItem(USER_KEY) || ''
}

/** 是否已登录（含过期判断）。路由守卫用。 */
export function isAuthenticated(): boolean {
  return getToken() !== null
}

/**
 * 保存登录结果。
 *
 * @param expiresAt 后端返回的过期时间（epoch 秒）
 */
export function setAuth(token: string, username: string, expiresAt: number): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, username)
  localStorage.setItem(EXPIRES_KEY, String(expiresAt))
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(EXPIRES_KEY)
}
