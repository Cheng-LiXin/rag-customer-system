import http from './http'
import type { PageResult } from '@/types'

export interface OperationLog {
  id: number
  username?: string
  module?: string
  action?: string
  method?: string
  uri?: string
  ip?: string
  params?: string
  status?: number
  errorMsg?: string
  costTime?: number
  createTime?: string
}

export const pageLogs = (params: Record<string, unknown>) =>
  http.get<PageResult<OperationLog>>('/log/page', { params })
