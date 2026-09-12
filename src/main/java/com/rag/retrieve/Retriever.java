package com.rag.retrieve;

/**
 * 检索器。四种实现对应四组对比实验：
 * {@code vector}（纯向量）/ {@code bm25}（词面）/ {@code rrf}（混合融合）/ {@code rerank}（重排）。
 *
 * <p>为什么要自己写这一层：本地 m2 里的 Spring AI 0.8.1 只有 {@code DocumentRetriever} 一个空接口，
 * 没有 {@code DocumentJoiner} / {@code ReRanker} / RRF —— 那些属于 Spring AI 1.0 的
 * {@code spring-ai-rag} 模块，0.8.1 里不存在。
 */
public interface Retriever {

    /** 模式标识，与配置值、接口参数一致 */
    String mode();

    /**
     * 检索 topK 个片段。
     *
     * @param query 用户问题原文（各实现自行决定是否做归一化）
     * @param topK  期望返回条数
     */
    RetrievalResult retrieve(String query, int topK);
}
