import { post, get } from '@/utils/request'
import type { ChatAnswer, PageResult, Conversation, Message } from '@/types'

/** 非流式问答（小程序无 SSE；一次性返回 + 前端打字机） */
export const ask = (message: string, conversationId?: number | null) =>
  post<ChatAnswer>('/chat/ask', { message, conversationId })

/** 已登录用户的历史会话列表 */
export const getHistory = (pageNum = 1, pageSize = 20) =>
  get<PageResult<Conversation>>('/chat/history', { pageNum, pageSize })

/** 查看指定会话的消息列表 */
export const getHistoryMessages = (conversationId: number) =>
  get<Message[]>(`/chat/history/${conversationId}/messages`)
