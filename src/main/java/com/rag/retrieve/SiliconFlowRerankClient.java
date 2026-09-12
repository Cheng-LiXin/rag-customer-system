package com.rag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 硅基流动重排接口客户端（{@code POST /v1/rerank}，模型 {@code BAAI/bge-reranker-v2-m3}）。
 *
 * <p>用 JDK 自带的 {@code java.net.http.HttpClient}，不引任何新依赖；
 * API Key 复用 embedding 那把（同一个 SiliconFlow 账号，见 application.yml 的
 * {@code spring.ai.openai.embedding.api-key}）。
 *
 * <p>刻意**只做一件事**：发请求、解析出「按相关性降序的原始下标」。
 * 失败就抛异常，由 {@link RerankRetriever} 决定降级 —— 重排是增强项，
 * 绝不能因为它失败就让整个问答失败。
 */
@Slf4j
@Component
public class SiliconFlowRerankClient {

    @Value("${rag.retrieve.rerank.url:https://api.siliconflow.cn/v1/rerank}")
    private String url;

    @Value("${rag.retrieve.rerank.model:BAAI/bge-reranker-v2-m3}")
    private String model;

    @Value("${rag.retrieve.rerank.timeout-ms:3000}")
    private long timeoutMs;

    /** 复用 SiliconFlow 的 key（与 embedding 同一账号） */
    @Value("${rag.retrieve.rerank.api-key:${spring.ai.openai.embedding.api-key:}}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile HttpClient httpClient;

    private HttpClient client() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(Math.max(500L, timeoutMs)))
                            .build();
                }
                c = httpClient;
            }
        }
        return c;
    }

    /**
     * 对 documents 重排，返回**原始下标**的降序列表（前 topN 个）。
     *
     * @throws Exception 网络/鉴权/解析任一失败；调用方必须降级而不是把异常抛给用户
     */
    public List<Integer> rerank(String query, List<String> documents, int topN) throws Exception {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("未配置 SiliconFlow API Key（rag.retrieve.rerank.api-key）");
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", Math.min(topN, documents.size()));
        body.put("return_documents", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(500L, timeoutMs)))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("rerank HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }

        JsonNode results = objectMapper.readTree(response.body()).get("results");
        List<Integer> order = new ArrayList<>();
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(-1);
                if (idx >= 0) {
                    order.add(idx);
                }
            }
        }
        return order;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
