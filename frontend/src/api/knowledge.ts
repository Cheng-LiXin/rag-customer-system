import http from './http'
import type {
  PageResult,
  KnowledgeChunk,
  KnowledgeCategory,
  DocumentParseResult,
  DocumentImportPayload,
  DocumentImportResult
} from '@/types'

// ===== 知识片段 =====
export const pageChunks = (params: Record<string, unknown>) =>
  http.get<PageResult<KnowledgeChunk>>('/knowledge/chunk/page', { params })

export const addChunk = (data: Record<string, unknown>) =>
  http.post<KnowledgeChunk>('/knowledge/chunk', data)

export const updateChunk = (id: number, data: Record<string, unknown>) =>
  http.put<KnowledgeChunk>(`/knowledge/chunk/${id}`, data)

export const deleteChunk = (id: number) => http.delete<void>(`/knowledge/chunk/${id}`)

export const reindexChunk = (id: number) =>
  http.post<KnowledgeChunk>(`/knowledge/chunk/${id}/reindex`)

// 导出地址（直接跳转/下载，需带 JWT）
export const exportUrl = '/api/knowledge/chunk/export'

export const importChunks = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return http.post<{ success: number; fail: number; errors: string[] }>(
    '/knowledge/chunk/import',
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  )
}

// ===== 文档上传导入 =====
/** 上传文档 → 后端解析并自动切块（仅返回预览，不落库） */
export const parseDocument = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return http.post<DocumentParseResult>('/knowledge/document/parse', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 确认导入预览中选中的切块（落库 + 向量化） */
export const importDocumentChunks = (data: DocumentImportPayload) =>
  http.post<DocumentImportResult>('/knowledge/document/import', data)

// ===== 分类管理 =====
export const listCategories = () =>
  http.get<KnowledgeCategory[]>('/knowledge/category/list')

export const addCategory = (data: Record<string, unknown>) =>
  http.post<KnowledgeCategory>('/knowledge/category', data)

export const updateCategory = (id: number, data: Record<string, unknown>) =>
  http.put<KnowledgeCategory>(`/knowledge/category/${id}`, data)

export const deleteCategory = (id: number) =>
  http.delete<void>(`/knowledge/category/${id}`)
