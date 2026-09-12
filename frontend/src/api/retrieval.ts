import http from './http'

/** 检索预览的一条命中 */
export interface RetrievalPreviewItem {
  index: number
  chunkId: string
  title?: string
  category?: string
  /** 融合前的向量余弦相似度；BM25 模式没有该值 */
  score?: number | null
  mode?: string
  contentPreview?: string
}

export const RETRIEVAL_MODES = ['vector', 'bm25', 'rrf', 'rerank'] as const

export const retrievePreview = (q: string, topK = 5, mode?: string) =>
  http.get<RetrievalPreviewItem[]>('/knowledge/chunk/retrieve-preview', {
    params: { q, topK, mode }
  })

/**
 * 四种模式各检索一遍，拼成一张并排表。
 * 逐个串行调用而不是并发：单机演示环境没必要压并发，串行还能让结果顺序与模式顺序一致。
 */
export async function retrievePreviewAll(q: string, topK = 5): Promise<Array<Record<string, unknown>>> {
  const out: Array<Record<string, unknown>> = []
  for (const m of RETRIEVAL_MODES) {
    try {
      const items = await retrievePreview(q, topK, m)
      items.forEach((it, i) => out.push({ ...it, mode: m, rank: i + 1 }))
    } catch {
      out.push({ mode: m, rank: '-', chunkId: '-', title: '该模式不可用', contentPreview: '' })
    }
  }
  return out
}
