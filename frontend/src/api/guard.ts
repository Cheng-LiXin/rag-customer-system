import http from './http'
import type { PageResult } from '@/types'

/** 注入防护运行状态 */
export interface GuardStatus {
  /** 当前是否开启（已考虑 Redis 运行时覆盖） */
  enabled: boolean
  /** 取值来源：runtime（管理端切换过）/ config（用配置打底值） */
  source: string
  configEnabled: boolean
  runtimeSwitch: boolean
  inputLevel: string
  contextLevel: string
  outputLevel: string
}

/** 注入防护审计事件 */
export interface GuardEvent {
  id: number
  /** 命中层：input / context / output */
  layer: string
  ruleId?: string
  /** BLOCK 拦截 / LOG 仅记录 */
  action: string
  question?: string
  chunkId?: string
  hitText?: string
  username?: string
  ip?: string
  createTime?: string
}

export const guardStatus = () => http.get<GuardStatus>('/admin/guard/status')

/** 切换防护开关；enabled 传 null/undefined 表示清除运行时覆盖、回到配置值 */
export const toggleGuard = (enabled?: boolean | null) =>
  http.post<GuardStatus>('/admin/guard/toggle', { enabled })

export const pageGuardEvents = (params: {
  pageNum?: number
  pageSize?: number
  layer?: string
}) => http.get<PageResult<GuardEvent>>('/admin/guard/events', { params })
