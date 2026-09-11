import http from './http'
import type { PageResult, SysUser, SysRole, SysPermission, BannedWord } from '@/types'

// ===== 用户管理 =====
export const pageUsers = (params: Record<string, unknown>) =>
  http.get<PageResult<SysUser>>('/admin/user/page', { params })

export const createUser = (data: Record<string, unknown>) =>
  http.post<void>('/admin/user', data)

export const updateUser = (id: number, data: Record<string, unknown>) =>
  http.put<void>(`/admin/user/${id}`, data)

export const deleteUser = (id: number) => http.delete<void>(`/admin/user/${id}`)

// ===== 角色管理 =====
export const listRoles = () => http.get<SysRole[]>('/admin/role/list')

export const createRole = (data: Record<string, unknown>) =>
  http.post<void>('/admin/role', data)

export const updateRole = (id: number, data: Record<string, unknown>) =>
  http.put<void>(`/admin/role/${id}`, data)

export const deleteRole = (id: number) => http.delete<void>(`/admin/role/${id}`)

// ===== 权限查询 =====
export const listPermissions = () => http.get<SysPermission[]>('/admin/permission/list')

// ===== 违禁词管理 =====
export const pageBannedWords = (params: Record<string, unknown>) =>
  http.get<PageResult<BannedWord>>('/admin/banned-word/page', { params })

export const createBannedWord = (data: { word: string; status?: number }) =>
  http.post<void>('/admin/banned-word', data)

export const updateBannedWord = (id: number, data: { word?: string; status?: number }) =>
  http.put<void>(`/admin/banned-word/${id}`, data)

export const deleteBannedWord = (id: number) =>
  http.delete<void>(`/admin/banned-word/${id}`)
