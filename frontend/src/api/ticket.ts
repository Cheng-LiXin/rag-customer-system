import http from './http'
import type { PageResult, Ticket } from '@/types'

export const createTicket = (data: Record<string, unknown>) =>
  http.post<Ticket>('/ticket', data)

export const pageTickets = (params: Record<string, unknown>) =>
  http.get<PageResult<Ticket>>('/ticket/page', { params })

export const ticketDetail = (id: number) => http.get<Ticket>(`/ticket/${id}`)

export const updateTicketStatus = (id: number, status: number) =>
  http.put<void>(`/ticket/${id}/status`, null, { params: { status } })

export const assignTicket = (id: number, agentId: number) =>
  http.put<void>(`/ticket/${id}/assign`, null, { params: { agentId } })

export const myTickets = (params: Record<string, unknown>) =>
  http.get<PageResult<Ticket>>('/ticket/page', { params })
