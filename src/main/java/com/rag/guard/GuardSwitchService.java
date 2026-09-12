package com.rag.guard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 注入防护的运行时开关。
 *
 * <p>取值优先级：Redis 键 {@code rag:guard:enabled}（运行时覆盖）→ 配置 {@code rag.security.injection.enabled}（打底）。
 * 选 Redis 而非数据库表：已在技术栈内、读取代价最低、切换即时生效；切换动作本身另由
 * {@code @OperationLog} 落到 {@code operation_log}，不怕这个键丢。
 *
 * <p>带 5 秒本地缓存：问答是热路径，每个请求都打一次 Redis 不值得，
 * 而 5 秒的切换延迟对现场演示完全够用（切换后立刻置脏，实际上无感）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardSwitchService {

    private static final String KEY = "rag:guard:enabled";
    private static final long CACHE_MS = 5000L;

    private final StringRedisTemplate redisTemplate;
    private final GuardProperties props;

    private volatile Boolean cachedValue;
    private volatile long cachedAt;

    /** 当前是否开启防护 */
    public boolean isEnabled() {
        GuardProperties.Injection cfg = props.getInjection();
        if (!cfg.isRuntimeSwitch()) {
            return cfg.isEnabled();
        }
        long now = System.currentTimeMillis();
        Boolean v = cachedValue;
        if (v != null && now - cachedAt < CACHE_MS) {
            return v;
        }
        boolean result;
        try {
            String raw = redisTemplate.opsForValue().get(KEY);
            result = raw == null ? cfg.isEnabled() : Boolean.parseBoolean(raw);
        } catch (Exception e) {
            // Redis 不可用时回退配置值：防护是安全能力，不能因为缓存层抖动就静默失效
            log.warn("读取注入防护开关失败，回退配置值: {}", e.getMessage());
            result = cfg.isEnabled();
        }
        cachedValue = result;
        cachedAt = now;
        return result;
    }

    /** 运行时切换：写 Redis 覆盖键，同时立即刷新本地缓存（不等 5 秒） */
    public void setEnabled(boolean enabled) {
        redisTemplate.opsForValue().set(KEY, Boolean.toString(enabled));
        cachedValue = enabled;
        cachedAt = System.currentTimeMillis();
    }

    /** 清除运行时覆盖，回到配置打底值 */
    public void clearOverride() {
        redisTemplate.delete(KEY);
        cachedValue = null;
        cachedAt = 0L;
    }

    /** 当前取值来源：runtime（Redis 覆盖）/ config（配置打底）—— 供管理端展示与审计 */
    public String source() {
        if (!props.getInjection().isRuntimeSwitch()) {
            return "config";
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY)) ? "runtime" : "config";
        } catch (Exception e) {
            return "config";
        }
    }
}
