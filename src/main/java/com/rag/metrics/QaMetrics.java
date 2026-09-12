package com.rag.metrics;

import com.rag.retrieve.ChunkCorpusService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 问答链路的业务指标（批次 F），由 Prometheus 抓取、Grafana 展示。
 *
 * <p>暴露的指标：
 * <ul>
 *   <li>{@code rag.qa.answers{result}} —— 一次问答的结局分布：
 *       answered 正常作答 / fallback 兜底 / refused 相似度过低拒答 / blocked 注入拦截 / cached 缓存命中。
 *       这条曲线能直接看出「防护是不是误杀了」与「命中率有没有退化」。</li>
 *   <li>{@code rag.qa.ttft} —— 首片 token 耗时。注意本项目的 SSE 是**伪流式**
 *       （答案在 Flux 创建前已算完），所以它就是全链路耗时，报告口径里如实说明。</li>
 *   <li>{@code rag.guard.blocks{layer,rule}} —— 注入防护分层命中次数。</li>
 *   <li>{@code rag.corpus.size} —— BM25 语料条数，与知识库对拍用。</li>
 * </ul>
 *
 * <p>指标埋点一律**不影响主流程**：传 null 也能安全记录（内部做了兜底标签）。
 */
@Component
@RequiredArgsConstructor
public class QaMetrics {

    public static final String ANSWERED = "answered";
    public static final String FALLBACK = "fallback";
    public static final String REFUSED = "refused";
    public static final String BLOCKED = "blocked";
    public static final String CACHED = "cached";

    private final MeterRegistry registry;
    private final ChunkCorpusService corpusService;

    @PostConstruct
    void init() {
        Gauge.builder("rag.corpus.size", corpusService, ChunkCorpusService::size)
                .description("BM25 语料（参与检索的知识片段）条数")
                .register(registry);
    }

    /** 缓存命中（既算进结局分布，也单独计一次命中） */
    public void recordCacheHit() {
        registry.counter("rag.cache.hits").increment();
        registry.counter("rag.qa.answers", "result", CACHED).increment();
    }

    /** 记录一次实时问答的结局与耗时（毫秒）。ttftMs < 0 表示不记录耗时。 */
    public void recordAnswer(String result, long ttftMs) {
        String tag = result == null ? ANSWERED : result;
        registry.counter("rag.qa.answers", "result", tag).increment();
        if (ttftMs >= 0) {
            Timer.builder("rag.qa.ttft")
                    .tag("result", tag)
                    .publishPercentiles(0.5, 0.95)
                    .register(registry)
                    .record(ttftMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 注入防护命中（按层与规则分开统计，便于看出是哪一层在起作用） */
    public void recordGuardBlock(String layer, String ruleId) {
        registry.counter("rag.guard.blocks",
                "layer", layer == null ? "unknown" : layer,
                "rule", ruleId == null ? "unknown" : ruleId).increment();
    }
}
