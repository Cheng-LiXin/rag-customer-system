import { get } from '@/utils/request'
import type { CustomerProfile } from '@/types'

/** 会话归属客户资料（客服名片；游客会话返回 { guest:true }） */
export const conversationCustomer = (id: number) =>
  get<CustomerProfile>(`/conversation/${id}/customer`)
