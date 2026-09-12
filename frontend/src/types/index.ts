// 与后端 DTO / Entity 对应的类型定义

export interface PageResult<T> {
  total: number
  records: T[]
}

export interface Source {
  /** 引用序号（1 基）：正文中的行内标记 [n] 指向 sources[n-1] */
  index?: number
  chunkId?: string
  title?: string
  category?: string
  content?: string
  score?: number | null
  /** 来源文件的干净标题（后端 knowledge_chunk.source_title） */
  sourceTitle?: string
  /** 来源文件的官方链接 */
  sourceUrl?: string
  /** 知识条目最近更新时间 */
  updateTime?: string
}

export interface ChatAnswer {
  answer: string
  fromCache: boolean
  conversationId?: number | null
  /** 本次机器人回答在 message 表的 id（提交 👍/👎 用） */
  messageId?: number | null
  sources?: Source[]
  intentCategory?: string
  createdAt?: string
  /** 是否被拒答（相似度过低或命中注入防护）—— 前端据此展示「转人工客服」入口 */
  rejected?: boolean
  rejectReason?: string
  /** 低置信：答案照给，但提示可转人工核实 */
  lowConfidence?: boolean
  /** 本次检索最高相似度（融合前向量池 top-1 余弦） */
  maxScore?: number | null
  retrievalMode?: string
  /** 分阶段耗时（毫秒）：retrieveMs / generateMs / totalMs / ttftMs */
  timings?: Record<string, number>
}

export interface KnowledgeCategory {
  id: number
  name: string
  parentId: number
  sortOrder?: number
  description?: string
  status?: number
  createTime?: string
  updateTime?: string
}

export interface KnowledgeChunk {
  id: number
  categoryId: number
  title: string
  content: string
  sourceType?: string
  sourceUrl?: string
  chunkIndex?: number
  vectorId?: string
  vectorStatus?: number
  keywords?: string
  hitCount?: number
  status?: number
  deleted?: number
  createTime?: string
  updateTime?: string
}

/** 上传文档解析出的切块（仅预览，未落库） */
export interface ParsedChunk {
  title: string
  content: string
  charCount: number
}

/** 文档解析（预览）结果 */
export interface DocumentParseResult {
  fileName: string
  total: number
  totalChars: number
  chunks: ParsedChunk[]
}

/** 文档导入提交载荷（确认预览后提交选中的切块） */
export interface DocumentImportPayload {
  categoryId?: number
  sourceTitle?: string
  sourceUrl?: string
  chunks: Array<{ title: string; content: string }>
}

/** 文档导入结果 */
export interface DocumentImportResult {
  success: number
  fail: number
  vectorFail: number
  errors?: string[]
}

export interface Conversation {
  id: number
  userId: number
  /** 归属用户昵称/用户名（后台会话管理回填；userId=0 为游客，无该值） */
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
  senderType: string
  content: string
  citations?: string
  fromCache?: number
  messageType?: string
  status?: number
  /** AI 消息反馈：0-未评 1-有帮助 2-没帮助 */
  feedback?: number
  feedbackTime?: string
  feedbackComment?: string
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

export interface SysUser {
  id: number
  username: string
  password?: string
  nickname?: string
  avatar?: string
  email?: string
  phone?: string
  status?: number
  createTime?: string
  updateTime?: string
}

export interface BannedWord {
  id: number
  word: string
  status?: number
  createTime?: string
}

export interface SysRole {
  id: number
  roleName: string
  roleCode: string
  description?: string
  status?: number
  createTime?: string
  updateTime?: string
}

export interface SysPermission {
  id: number
  permissionName: string
  permissionCode: string
  type?: number
  parentId?: number
  path?: string
  icon?: string
  sortOrder?: number
  status?: number
}
