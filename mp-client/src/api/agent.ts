import { post, get } from '@/utils/request'
import type { WorkbenchResult, TransferResult, QueueResult, Message } from '@/types'

// ===== 用户侧 / 公共 =====
export const transfer = (data: {
  conversationId?: number | null
  userId?: number | null
  summary?: string
}) => post<TransferResult>('/agent/transfer', data, { showError: false })

export const queuePosition = (conversationId: number) =>
  get<QueueResult>(`/agent/queue/${conversationId}`)

export const closeConversation = (id: number) =>
  post<void>(`/agent/conversation/${id}/close`)

export const markRead = (id: number) => post<void>(`/agent/conversation/${id}/read`)

export const submitSatisfaction = (data: {
  conversationId: number
  rating: number
  userId?: number | null
  comment?: string
}) => post<void>('/satisfaction', data, false)

// ===== 客服工作台 =====
export const workbench = () => get<WorkbenchResult>('/agent/workbench')

export const reopenConversation = (id: number) =>
  post<void>(`/agent/conversation/${id}/reopen`)

export const conversationMessages = (id: number) =>
  get<Message[]>(`/conversation/${id}/messages`)
