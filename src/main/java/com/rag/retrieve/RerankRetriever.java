package com.rag.retrieve;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排检索（对照组④）：先由基础检索器取一个宽候选池，再交给 bge-reranker 精排。
 *
 * <p><b>降级是硬要求</b>：重排是增强项，网络抖动、限流、超时都可能发生。
 * 任何失败都退回基础检索器的原序（只截到 topK），并打 WARN ——
 * **绝不因为重排失败让问答失败**。
 *
 * <p>{@code maxVectorSimilarity} 一路透传自基础检索器，阈值语义不受重排影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankRetriever implements Retriever {

    public static final String MODE = "rerank";

    private final VectorRetriever vectorRetriever;
    private final Bm25Retriever bm25Retriever;
    private final HybridRrfRetriever rrfRetriever;
    private final SiliconFlowRerankClient rerankClient;

    /** 重排前的候选池来自哪种检索：vector / bm25 / rrf（默认 rrf，混合召回更全） */
    @Value("${rag.retrieve.rerank.base-mode:rrf}")
    private String baseMode;

    @Value("${rag.retrieve.candidate-k:20}")
    private int candidateK;

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public RetrievalResult retrieve(String query, int topK) {
        Retriever base = switch (baseMode == null ? "rrf" : baseMode) {
            case VectorRetriever.MODE -> vectorRetriever;
            case Bm25Retriever.MODE -> bm25Retriever;
            default -> rrfRetriever;
        };

        int cand = Math.max(topK, candidateK);
        RetrievalResult baseResult = base.retrieve(query, cand);
        List<Document> candidates = baseResult.getDocuments();
        if (candidates.size() <= 1) {
            return new RetrievalResult(candidates, baseResult.getMaxVectorSimilarity(), MODE);
        }

        try {
            List<String> texts = new ArrayList<>(candidates.size());
            for (Document d : candidates) {
                texts.add(d.getContent() == null ? "" : d.getContent());
            }
            List<Integer> order = rerankClient.rerank(query, texts, topK);

            List<Document> reranked = new ArrayList<>(topK);
            for (int idx : order) {
                if (idx >= 0 && idx < candidates.size()) {
                    Document d = candidates.get(idx);
                    if (!reranked.contains(d)) {
                        reranked.add(d);
                    }
                }
            }
            // 兜底补齐：重排返回条数不足时用原序补上，保证不因重排反而少给片段
            for (Document d : candidates) {
                if (reranked.size() >= topK) {
                    break;
                }
                if (!reranked.contains(d)) {
                    reranked.add(d);
                }
            }
            return new RetrievalResult(reranked, baseResult.getMaxVectorSimilarity(), MODE);
        } catch (Exception e) {
            log.warn("重排失败，降级为 {} 原序: {}", baseMode, e.getMessage());
            List<Document> fallback = candidates.subList(0, Math.min(topK, candidates.size()));
            return new RetrievalResult(new ArrayList<>(fallback), baseResult.getMaxVectorSimilarity(), MODE);
        }
    }
}
