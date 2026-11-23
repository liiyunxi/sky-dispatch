import { computed, ref } from 'vue'
import { http } from '@/api/client'
import { clearAuth, getToken, getUsername, setAuth } from '@/api/token'

/**
 * 登录态（组合式函数，不引入 Pinia）。
 *
 * 模块级 ref 让 App / 登录页共享同一份状态；
 * token 的持久化与过期判断在 api/token.ts，避免与 client.ts 循环依赖。
 */

interface LoginResponse {
  token: string
  username: string
  /** epoch 秒 */
  expiresAt: number
}

const token = ref(getToken() ?? '')
const username = ref(getUsername())
const authenticated = computed(() => Boolean(token.value))

async function login(user: string, password: string): Promise<void> {
  const data = await http.post<LoginResponse>('/auth/login', { username: user, password })
  setAuth(data.token, data.username, data.expiresAt)
  token.value = data.token
  username.value = data.username
}

function logout(): void {
  clearAuth()
  token.value = ''
  username.value = ''
}

export function useAuth() {
  return { token, username, authenticated, login, logout }
}
