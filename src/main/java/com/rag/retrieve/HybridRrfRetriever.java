package com.rag.retrieve;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 混合检索：向量 + BM25 两路并用 RRF 融合（对照组③）。
 *
 * <p><b>为什么选 RRF 而不是线性加权：</b>
 * RRF 只用**排名**不用分数，而 BM25 分数无上界、且跨 query 不可比 ——
 * 线性加权前必须做 min-max 归一化，而归一化本身引入超参且对异常值敏感。
 * RRF 公式 {@code score(d) = Σ w_i / (k + rank_i(d))}，k 取 Cormack 的经典值 60，
 * 鲁棒、可解释、好写进报告。
 */
@Component
@RequiredArgsConstructor
public class HybridRrfRetriever implements Retriever {

    public static final String MODE = "rrf";

    private final VectorRetriever vectorRetriever;
    private final Bm25Retriever bm25Retriever;
    private final ChunkCorpusService corpus;

    @Value("${rag.retrieve.candidate-k:20}")
    private int candidateK;

    @Value("${rag.retrieve.rrf.k:60}")
    private int rrfK;

    @Value("${rag.retrieve.rrf.weight-vector:1.0}")
    private double weightVector;

    @Value("${rag.retrieve.rrf.weight-bm25:1.0}")
    private double weightBm25;

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public RetrievalResult retrieve(String query, int topK) {
        int cand = Math.max(topK, candidateK);

        // 向量路（宽池）—— 它同时提供 maxVectorSimilarity 这个阈值锚点
        RetrievalResult vectorResult = vectorRetriever.retrieve(query, cand);
        // BM25 路
        List<Bm25Index.Hit> bm25Hits = corpus.index().search(query, cand);

        Map<Long, Double> fused = new LinkedHashMap<>();
        Map<Long, Document> docById = new LinkedHashMap<>();

        List<Document> vDocs = vectorResult.getDocuments();
        for (int i = 0; i < vDocs.size(); i++) {
            Long id = RetrievalResult.chunkIdOf(vDocs.get(i));
            if (id == null) {
                continue;
            }
            docById.putIfAbsent(id, vDocs.get(i));
            fused.merge(id, weightVector / (rrfK + i + 1.0), Double::sum);
        }
        for (int i = 0; i < bm25Hits.size(); i++) {
            Long id = bm25Hits.get(i).chunkId();
            if (!docById.containsKey(id)) {
                Document d = corpus.document(id);
                if (d == null) {
                    continue;
                }
                docById.put(id, d);
            }
            fused.merge(id, weightBm25 / (rrfK + i + 1.0), Double::sum);
        }

        List<Document> out = new ArrayList<>(topK);
        fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> docById.get(e.getKey()))
                .filter(Objects::nonNull)
                .forEach(out::add);

        // 锚点仍取「融合前的向量 top-1」—— 这是四模式阈值语义一致的保证
        return new RetrievalResult(out, vectorResult.getMaxVectorSimilarity(), MODE);
    }
}
