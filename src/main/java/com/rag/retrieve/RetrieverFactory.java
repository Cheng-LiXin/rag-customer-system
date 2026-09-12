package com.rag.retrieve;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索器选择器：决定本次检索走哪一种。
 *
 * <p>取值优先级：
 * <ol>
 *   <li>请求级覆盖 {@link RetrievalModeContext}（评估脚本用 {@code X-Retrieval-Mode} 头指定，
 *       一次能跑完四组而不必切全局状态）</li>
 *   <li>Redis 键 {@code rag:retrieve:mode}（管理端运行时切换，演示用）</li>
 *   <li>配置 {@code rag.retrieve.mode}（打底）</li>
 * </ol>
 *
 * <p>任何一环给出未知模式都回落到 {@code vector} —— 那是改造前的唯一路径，
 * 也就是最安全的默认值。
 */
@Slf4j
@Component
public class RetrieverFactory {

    public static final String KEY = "rag:retrieve:mode";
    private static final long CACHE_MS = 5000L;

    private final Map<String, Retriever> retrievers;
    private final StringRedisTemplate redisTemplate;

    @Value("${rag.retrieve.mode:vector}")
    private String configMode;

    private volatile String cachedMode;
    private volatile long cachedAt;

    public RetrieverFactory(List<Retriever> retrieverBeans, StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        Map<String, Retriever> map = new HashMap<>();
        for (Retriever r : retrieverBeans) {
            map.put(r.mode(), r);
        }
        this.retrievers = Map.copyOf(map);
        log.info("已装配检索器: {}", this.retrievers.keySet());
    }

    public Set<String> modes() {
        return retrievers.keySet();
    }

    public Retriever current() {
        return byMode(activeMode());
    }

    /** 按模式名取检索器；未知模式回落 vector */
    public Retriever byMode(String mode) {
        Retriever r = mode == null ? null : retrievers.get(mode);
        return r != null ? r : retrievers.get(VectorRetriever.MODE);
    }

    /** 当前生效的模式名 */
    public String activeMode() {
        String override = RetrievalModeContext.get();
        if (override != null && retrievers.containsKey(override)) {
            return override;
        }
        long now = System.currentTimeMillis();
        String cached = cachedMode;
        if (cached != null && now - cachedAt < CACHE_MS) {
            return cached;
        }
        String result;
        try {
            String raw = redisTemplate.opsForValue().get(KEY);
            result = raw == null ? configMode : raw;
        } catch (Exception e) {
            log.warn("读取检索模式失败，回退配置值: {}", e.getMessage());
            result = configMode;
        }
        if (result == null || !retrievers.containsKey(result)) {
            result = VectorRetriever.MODE;
        }
        cachedMode = result;
        cachedAt = now;
        return result;
    }

    /** 运行时切换（管理端调用），写 Redis 覆盖键并立即刷新本地缓存 */
    public void setRuntimeMode(String mode) {
        if (mode == null || !retrievers.containsKey(mode)) {
            throw new IllegalArgumentException("未知检索模式: " + mode);
        }
        redisTemplate.opsForValue().set(KEY, mode);
        cachedMode = mode;
        cachedAt = System.currentTimeMillis();
    }

    /** 清除运行时覆盖，回到配置打底值 */
    public void clearRuntimeMode() {
        redisTemplate.delete(KEY);
        cachedMode = null;
        cachedAt = 0L;
    }

    /** 取值来源：request（单次请求覆盖）/ runtime（Redis）/ config（配置打底） */
    public String source() {
        String override = RetrievalModeContext.get();
        if (override != null && retrievers.containsKey(override)) {
            return "request";
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY)) ? "runtime" : "config";
        } catch (Exception e) {
            return "config";
        }
    }
}
