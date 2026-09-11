import http from './http'

export interface CountPair {
  status?: number
  type?: string
  count: number
}

export interface StatisticsSummary {
  userCount: number
  conversationCount: number
  messageCount: number
  chunkCount: number
  ticketCount: number
  satisfactionCount: number
  todayMessageCount: number
  aiMessageCount: number
  ticketStatus: CountPair[]
  conversationType: CountPair[]
  satisfaction: { avg?: number; count?: number }
  chunkVector: CountPair[]
  messageTrend: { d: string; count: number }[]
  hotKnowledge: { id: number; title?: string; hitCount?: number }[]
  intentDist: { category?: string; count: number }[]
}

export const getSummary = () => http.get<StatisticsSummary>('/statistics/summary')
