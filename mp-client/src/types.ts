// 与后端 DTO / Entity 对应（镜像 frontend/src/types/index.ts 的子集 + api 层自身类型）

export interface PageResult<T> {
  total: number
  records: T[]
}

export interface Source {
  chunkId?: string
  title?: string
  category?: string
  content?: string
  score?: number | null
}

export interface ChatAnswer {
  answer: string
  fromCache: boolean
  conversationId?: number | null
  sources?: Source[]
  intentCategory?: string
  createdAt?: string
}

export interface Conversation {
  id: number
  userId: number
  userName?: string
  title?: string
  sessionType?: string
  status?: number
  agentId?: number
  lastReplyTime?: string
  createTime?: string
  updateTime?: string
}

export interface Message {
  id: number
  conversationId: number
  senderType: string // USER | AI | AGENT
  content: string
  citations?: string
  fromCache?: number
  messageType?: string
  status?: number
  createTime?: string
}

export interface Ticket {
  id: number
  userId: number
  conversationId?: number
  title: string
  description?: string
  category?: string
  priority?: number
  status?: number
  assigneeId?: number
  createTime?: string
  updateTime?: string
  closeTime?: string
}

// ===== auth =====
export interface LoginResult {
  token: string
  username: string
}
export interface MeResult {
  username: string
  roles: string[]
  id?: number
  nickname?: string
  avatar?: string
}
export interface ProfileResult {
  id: number
  username: string
  nickname?: string
  email?: string
  phone?: string
  province?: string
  city?: string
  identity?: string
  avatar?: string
  createTime?: string
  status?: number
  roles: string[]
  nicknameUpdatedAt?: string | null
  nicknameEditable?: boolean
  nicknameCooldownDays?: number
}
export interface RegisterResult {
  id: number
  username: string
}

// ===== agent workbench =====
export interface WorkbenchConversation {
  id: number
  userId: number
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

export interface TransferResult {
  conversationId: number
  queuePosition: number
  assigned: boolean
}
export interface QueueResult {
  conversationId: number
  position: number
}

export interface CustomerProfile {
  guest?: boolean
  userId: number
  username?: string
  nickname?: string
  avatar?: string
  email?: string
  phone?: string
  province?: string
  city?: string
  identity?: string
  status?: number
  createTime?: string
}
