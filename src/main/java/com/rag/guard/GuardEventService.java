package com.rag.guard;

import com.rag.entity.GuardEvent;
import com.rag.mapper.GuardEventMapper;
import com.rag.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 注入防护审计：把每一次命中写成 {@code guard_event} 行。
 *
 * <p>审计写入失败绝不能影响问答本身（写库异常时只打 WARN），
 * 所以这里整体 try/catch 兜住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardEventService {

    private static final int MAX_Q = 500;
    private static final int MAX_HIT = 500;

    private final GuardEventMapper guardEventMapper;

    /** 记录一次命中（BLOCK 与 LOG 都记，靠 action 字段区分） */
    public void record(GuardDecision decision, String question) {
        if (decision == null || !decision.isHit()) {
            return;
        }
        try {
            GuardEvent event = new GuardEvent();
            event.setLayer(decision.getLayer());
            event.setRuleId(decision.getRuleId());
            event.setAction(decision.getAction());
            event.setQuestion(truncate(question, MAX_Q));
            event.setChunkId(decision.getChunkId());
            event.setHitText(truncate(decision.getHitText(), MAX_HIT));
            event.setUsername(SecurityUtil.currentUsername());
            event.setIp(currentIp());
            event.setCreateTime(LocalDateTime.now());
            guardEventMapper.insert(event);
        } catch (Exception e) {
            log.warn("记录注入防护事件失败: {}", e.getMessage());
        }
    }

    /**
     * 取客户端 IP。问答接口在 /api/chat/** 下，属 permitAll，但仍在 servlet 请求线程上执行
     * （检索与生成都是同步调用，Flux 之前），因此 RequestContextHolder 可用。
     */
    private String currentIp() {
        try {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
