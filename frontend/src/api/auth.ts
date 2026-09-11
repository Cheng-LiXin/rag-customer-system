import http from './http'

export interface LoginResult {
  token: string
  username: string
}

export interface MeResult {
  username: string
  roles: string[]
  id?: number
  nickname?: string
  avatar?: string
}

export interface ProfileResult {
  id: number
  username: string
  nickname?: string
  email?: string
  phone?: string
  province?: string
  city?: string
  identity?: string
  avatar?: string
  createTime?: string
  status?: number
  roles: string[]
  /** 昵称最近修改时间（null=从未改过昵称，随时可改） */
  nicknameUpdatedAt?: string | null
  /** 昵称当前是否可改（30 天冷却期内为 false） */
  nicknameEditable?: boolean
  /** 距下次可改昵称的剩余天数（0=可改） */
  nicknameCooldownDays?: number
}

export interface RegisterResult {
  id: number
  username: string
}

export const login = (username: string, password: string) =>
  http.post<LoginResult>('/auth/login', { username, password })

export const register = (data: {
  phone: string
  nickname?: string
  password: string
  confirmPassword: string
}) => http.post<RegisterResult>('/auth/register', data)

export const me = () => http.get<MeResult>('/auth/me')

export const getProfile = () => http.get<ProfileResult>('/auth/profile')

export const updateProfile = (data: {
  nickname?: string
  email?: string
  phone?: string
  province?: string
  city?: string
  identity?: string
}) => http.put<void>('/auth/profile', data)

export const changePassword = (data: { oldPassword: string; newPassword: string }) =>
  http.put<void>('/auth/password', data)
