package com.rag.retrieve;

import lombok.Getter;
import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.List;

/**
 * 一次检索的结果。
 *
 * <p><b>关键不变量（整条链路的质量就靠它）</b>：
 * {@link #maxVectorSimilarity} 固定取自「融合前的向量宽池 top-1 余弦相似度」，
 * <b>与 BM25 / RRF / rerank 的分数无关</b>。
 *
 * <p>理由：拒答阈值（{@code rag.answer.reject-threshold}）与宽松兜底门槛
 * （{@code rag.partial-min-score}）的语义都锚定在「向量余弦相似度」上。
 * 如果让它们去读 RRF 或 rerank 的分数，换一种检索模式阈值就会失真
 * —— 同一个 0.55 在 BM25 下与在向量下含义完全不同。把锚点钉死在这里，
 * 四种检索模式下这两个阈值的行为完全一致。
 *
 * <p>副产品：{@code mode=vector} 时本类与改造前的行为**逐字节等价**
 * （宽池取前 topK 就是距离有序列表的前缀），所以它天然是回归基线。
 */
@Getter
public class RetrievalResult {

    /** 最终返回的片段（已按目标顺序排好，长度 ≤ topK） */
    private final List<Document> documents;

    /** 融合前向量池的 top-1 余弦相似度；无向量结果时为 null */
    private final Double maxVectorSimilarity;

    /** 本次使用的检索模式：vector / bm25 / rrf / rerank */
    private final String mode;

    public RetrievalResult(List<Document> documents, Double maxVectorSimilarity, String mode) {
        this.documents = documents == null ? Collections.emptyList() : documents;
        this.maxVectorSimilarity = maxVectorSimilarity;
        this.mode = mode;
    }

    public static RetrievalResult empty(String mode) {
        return new RetrievalResult(Collections.emptyList(), null, mode);
    }

    /** 从 Document 的 metadata 读 chunk_id（数字），无则 null */
    public static Long chunkIdOf(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        Object id = doc.getMetadata().get("chunk_id");
        if (id == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从 Document 的 metadata 读相似度（Spring AI pgvector 给的是 distance，余弦距离 → 1-d） */
    public static Double similarityOf(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        Object distance = doc.getMetadata().get("distance");
        if (distance instanceof Number n) {
            return 1.0 - n.doubleValue();
        }
        Object similarity = doc.getMetadata().get("similarity");
        if (similarity instanceof Number m) {
            return m.doubleValue();
        }
        return null;
    }
}
