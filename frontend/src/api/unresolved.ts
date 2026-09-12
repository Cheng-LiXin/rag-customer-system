import http from './http'
import type { PageResult } from '@/types'

/** 未解决问题池里的一条记录 */
export interface UnresolvedQuestion {
  id: number
  question: string
  /** 来源：1-兜底 2-点踩 3-拒答 4-防护拦截 */
  source: number
  hitCount: number
  topScore?: number | null
  conversationId?: number | null
  status: number
  handlerId?: number | null
  handleTime?: string | null
  knowledgeChunkId?: number | null
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface UnresolvedStats {
  total: number
  pending: number
  bySource: Record<string, number>
  byStatus: Record<string, number>
}

export const pageUnresolved = (params: {
  pageNum?: number
  pageSize?: number
  status?: number
  source?: number
  keyword?: string
}) => http.get<PageResult<UnresolvedQuestion>>('/admin/unresolved/page', { params })

export const unresolvedStats = () => http.get<UnresolvedStats>('/admin/unresolved/stats')

export const handleUnresolved = (
  id: number,
  body: { status?: number; knowledgeChunkId?: number; remark?: string }
) => http.post<void>(`/admin/unresolved/${id}/handle`, body)

/** AI 消息级反馈：1-有帮助 2-没帮助 */
export const submitMessageFeedback = (messageId: number, feedback: 1 | 2, comment?: string) =>
  http.post<void>(`/message/${messageId}/feedback`, { feedback, comment })
