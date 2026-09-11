package com.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.dto.ChatAnswer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 热门问答缓存（SRS 4.1.5）：
 * Key 格式 qa:cache:{MD5(问题文本)}，值 JSON 序列化的完整回复对象，TTL 默认 3600s。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private static final String KEY_PREFIX = "qa:cache:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 缓存过期时间（秒） */
    @Value("${rag.cache.ttl-seconds:3600}")
    private long ttlSeconds;

    /** 命中缓存返回回复，未命中返回 empty */
    public Optional<ChatAnswer> get(String question) {
        try {
            String json = redisTemplate.opsForValue().get(keyOf(question));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ChatAnswer.class));
        } catch (Exception e) {
            log.warn("读取问答缓存失败, question={}, err={}", question, e.getMessage());
            return Optional.empty();
        }
    }

    /** 异步写入缓存（LLM 生成新回复后调用） */
    @Async
    public void put(String question, ChatAnswer answer) {
        try {
            String json = objectMapper.writeValueAsString(answer);
            redisTemplate.opsForValue().set(keyOf(question), json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("写入问答缓存失败, question={}, err={}", question, e.getMessage());
        }
    }

    private String keyOf(String question) {
        return KEY_PREFIX + DigestUtils.md5DigestAsHex(question.getBytes(StandardCharsets.UTF_8));
    }
}
