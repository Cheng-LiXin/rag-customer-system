import { post, get, put } from '@/utils/request'
import type {
  LoginResult,
  MeResult,
  RegisterResult,
  ProfileResult
} from '@/types'

export const login = (username: string, password: string) =>
  post<LoginResult>('/auth/login', { username, password }, { showError: false })

export const register = (data: {
  phone: string
  nickname?: string
  password: string
  confirmPassword: string
}) => post<RegisterResult>('/auth/register', data, { showError: false })

export const me = () => get<MeResult>('/auth/me')

export const getProfile = () => get<ProfileResult>('/auth/profile', undefined, false)

export const updateProfile = (data: Record<string, unknown>) =>
  put<void>('/auth/profile', data, { showError: false })

export const changePassword = (data: { oldPassword: string; newPassword: string }) =>
  put<void>('/auth/password', data, { showError: false })
