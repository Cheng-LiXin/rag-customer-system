import http from './http'
import type { PageResult, Conversation, Message } from '@/types'

export const pageConversations = (params: Record<string, unknown>) =>
  http.get<PageResult<Conversation>>('/conversation/page', { params })

export const conversationDetail = (id: number) =>
  http.get<Conversation>(`/conversation/${id}`)

export const conversationMessages = (id: number) =>
  http.get<Message[]>(`/conversation/${id}/messages`)

/** 会话归属客户的详细资料（客服名片；游客会话返回 { guest: true }） */
export interface CustomerProfile {
  guest?: boolean
  userId: number
  username?: string
  nickname?: string
  avatar?: string
  email?: string
  phone?: string
  province?: string
  city?: string
  identity?: string
  status?: number
  createTime?: string
}

export const conversationCustomer = (id: number) =>
  http.get<CustomerProfile>(`/conversation/${id}/customer`)
