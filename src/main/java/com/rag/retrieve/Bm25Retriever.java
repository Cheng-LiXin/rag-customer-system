package com.rag.retrieve;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯 BM25 词面检索（对照组②）。
 *
 * <p>注意 {@code maxVectorSimilarity} 恒为 null —— 这条路本来就没有余弦相似度。
 * 后果是拒答阈值在纯 BM25 模式下**不生效**（没有分数可判），这是语义上诚实的：
 * 拿 BM25 分数去比 0.55 的余弦阈值毫无意义。混合与重排模式都能从向量那一路拿到该值，
 * 所以只有这一种模式受影响。
 */
@Component
@RequiredArgsConstructor
public class Bm25Retriever implements Retriever {

    public static final String MODE = "bm25";

    private final ChunkCorpusService corpus;

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public RetrievalResult retrieve(String query, int topK) {
        List<Bm25Index.Hit> hits = corpus.index().search(query, topK);
        List<Document> docs = new ArrayList<>(hits.size());
        for (Bm25Index.Hit h : hits) {
            Document d = corpus.document(h.chunkId());
            if (d != null) {
                docs.add(d);
            }
        }
        return new RetrievalResult(docs, null, MODE);
    }
}
