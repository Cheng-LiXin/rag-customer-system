import http from './http'

export interface TransferResult {
  conversationId: number
  queuePosition: number
  assigned: boolean
}

export interface QueueResult {
  conversationId: number
  position: number
}

export const transfer = (data: {
  conversationId?: number | null
  userId?: number | null
  summary?: string
}) => http.post<TransferResult>('/agent/transfer', data)

export const queuePosition = (conversationId: number) =>
  http.get<QueueResult>(`/agent/queue/${conversationId}`)

export const closeConversation = (id: number) =>
  http.post<void>(`/agent/conversation/${id}/close`)

export const markRead = (id: number) =>
  http.post<void>(`/agent/conversation/${id}/read`)

export const submitSatisfaction = (data: {
  conversationId: number
  rating: number
  userId?: number | null
  comment?: string
}) => http.post<void>('/satisfaction', data)

// ===== 客服工作台 =====
export interface WorkbenchConversation {
  id: number
  userId: number
  /** 归属用户昵称/用户名（服务端实时回填，userId=0 游客时为空） */
  userName?: string
  title?: string
  sessionType?: string
  status?: number
  agentId?: number
  createTime?: string
  updateTime?: string
  unread?: number
}

export interface WorkbenchResult {
  agentId: number
  onlineAgents: number[]
  queue: WorkbenchConversation[]
  mine: WorkbenchConversation[]
  ended: WorkbenchConversation[]
}

export const workbench = () => http.get<WorkbenchResult>('/agent/workbench')

export const reopenConversation = (id: number) =>
  http.post<void>(`/agent/conversation/${id}/reopen`)
