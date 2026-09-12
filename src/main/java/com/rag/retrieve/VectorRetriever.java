package com.rag.retrieve;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 纯向量检索（对照组①，也是改造前的唯一路径）。
 *
 * <p>与改造前的 {@code vectorStore.similaritySearch(...withTopK(topK))} **逐字节等价**，
 * 因此它是其余三种模式的回归基线：任何新模式只要让兜底率高于它，就该被否决。
 */
@Component
@RequiredArgsConstructor
public class VectorRetriever implements Retriever {

    public static final String MODE = "vector";

    private final VectorStore vectorStore;

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public RetrievalResult retrieve(String query, int topK) {
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.query(query).withTopK(topK));
        Double top1 = (docs == null || docs.isEmpty()) ? null : RetrievalResult.similarityOf(docs.get(0));
        return new RetrievalResult(docs, top1, MODE);
    }
}
