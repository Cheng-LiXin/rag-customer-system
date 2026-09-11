import { post, get, put } from '@/utils/request'
import type { PageResult, Ticket } from '@/types'

export const createTicket = (data: Record<string, unknown>) =>
  post<Ticket>('/ticket', data, { showError: false })

/** 客服「我的工单」：status 多选筛选用 status 参数 */
export const myTickets = (params: Record<string, unknown> = {}) =>
  get<PageResult<Ticket>>('/ticket/page', params)

export const ticketDetail = (id: number) => get<Ticket>(`/ticket/${id}`)

/** 状态流转：待处理→处理中→已解决→已关闭（status 走 query） */
export const updateTicketStatus = (id: number, status: number) =>
  put<void>(`/ticket/${id}/status`, undefined, { query: { status } })
