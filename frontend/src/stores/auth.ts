import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, me as apiMe, type MeResult } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const username = ref<string>(localStorage.getItem('username') || '')
  const profile = ref<MeResult | null>(null)

  const isLoggedIn = computed(() => !!token.value)
  const roles = computed(() => profile.value?.roles || [])
  const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
  const isAgent = computed(() => roles.value.includes('ROLE_AGENT'))
  const displayName = computed(
    () => profile.value?.nickname || username.value || '访客'
  )

  async function login(usernameInput: string, password: string) {
    const res = await apiLogin(usernameInput, password)
    token.value = res.token
    username.value = res.username
    localStorage.setItem('token', res.token)
    localStorage.setItem('username', res.username)
    await fetchProfile()
  }

  async function fetchProfile() {
    if (!token.value) return
    try {
      profile.value = await apiMe()
    } catch {
      profile.value = null
    }
  }

  function logout() {
    token.value = ''
    username.value = ''
    profile.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('username')
  }

  return {
    token,
    username,
    profile,
    isLoggedIn,
    roles,
    isAdmin,
    isAgent,
    displayName,
    login,
    fetchProfile,
    logout
  }
})
