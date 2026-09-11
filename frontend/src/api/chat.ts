import http from './http'
import type { ChatAnswer, PageResult, Conversation, Message } from '@/types'

export const ask = (message: string, conversationId?: number | null) =>
  http.post<ChatAnswer>('/chat/ask', { message, conversationId })

export const send = (message: string, conversationId?: number | null) =>
  http.post<ChatAnswer>('/chat/send', { message, conversationId })

/** 构造 SSE 流式问答地址（用于 fetch 消费） */
export const streamUrl = (message: string, conversationId?: number | null) => {
  const params = new URLSearchParams({ message })
  if (conversationId != null) {
    params.set('conversationId', String(conversationId))
  }
  return `/api/chat/stream?${params.toString()}`
}

/** 已登录用户的历史会话列表 */
export const getHistory = (pageNum = 1, pageSize = 20) =>
  http.get<PageResult<Conversation>>('/chat/history', { params: { pageNum, pageSize } })

/** 查看指定会话的消息列表 */
export const getHistoryMessages = (conversationId: number) =>
  http.get<Message[]>(`/chat/history/${conversationId}/messages`)
