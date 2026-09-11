import { reactive, computed } from 'vue'
import { login as apiLogin, me as apiMe, type MeResult } from '@/api/auth'
import { TOKEN_KEY, USERNAME_KEY, CONV_KEY, REOPEN_KEY } from '@/utils/config'

/**
 * 轻量全局登录态（非 Pinia：工程不额外引依赖，用 module 单例 + reactive 即可满足跨页共享）
 * 存储键以 mp_ 前缀，与 Web 端 localStorage 隔离。
 */
interface AuthState {
  token: string
  username: string
  profile: MeResult | null
}

export const auth = reactive<AuthState>({
  token: (uni.getStorageSync(TOKEN_KEY) as string) || '',
  username: (uni.getStorageSync(USERNAME_KEY) as string) || '',
  profile: null
})

export const isLoggedIn = computed(() => !!auth.token)
export const roles = computed(() => auth.profile?.roles || [])
export const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
export const isAgent = computed(() => roles.value.includes('ROLE_AGENT'))
export const displayName = computed(() => auth.profile?.nickname || auth.username || '访客')

export async function login(username: string, password: string) {
  const res = await apiLogin(username, password)
  auth.token = res.token
  auth.username = res.username
  uni.setStorageSync(TOKEN_KEY, res.token)
  uni.setStorageSync(USERNAME_KEY, res.username)
  await fetchProfile()
}

export async function fetchProfile() {
  if (!auth.token) return
  try {
    auth.profile = await apiMe()
  } catch {
    auth.profile = null
  }
}

/** 登出：清 token/用户名 + 会话 id + 重开会话标记（沿用 Web 语义） */
export function logout() {
  auth.token = ''
  auth.username = ''
  auth.profile = null
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(USERNAME_KEY)
  uni.removeStorageSync(CONV_KEY)
  uni.removeStorageSync(REOPEN_KEY)
}
