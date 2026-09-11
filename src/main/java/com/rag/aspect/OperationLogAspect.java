package com.rag.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.annotation.OperationLog;
import com.rag.mapper.OperationLogMapper;
import com.rag.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 操作日志切面（F09）：拦截标注了 {@link OperationLog} 的写接口，
 * 记录操作用户、模块、动作、HTTP 方法、路径、IP、参数、耗时与成功/失败结果。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long start = System.currentTimeMillis();
        HttpServletRequest request = currentRequest();

        com.rag.entity.OperationLog entity = new com.rag.entity.OperationLog();
        entity.setUsername(SecurityUtil.currentUsername());
        entity.setModule(operationLog.module());
        entity.setAction(operationLog.action());
        if (request != null) {
            entity.setMethod(request.getMethod());
            entity.setUri(request.getRequestURI());
            entity.setIp(resolveIp(request));
        }
        entity.setParams(serializeArgs(joinPoint.getArgs()));
        try {
            Object result = joinPoint.proceed();
            entity.setStatus(1);
            return result;
        } catch (Throwable t) {
            entity.setStatus(0);
            entity.setErrorMsg(truncate(t.getMessage(), 500));
            throw t;
        } finally {
            entity.setCostTime(System.currentTimeMillis() - start);
            entity.setCreateTime(LocalDateTime.now());
            try {
                operationLogMapper.insert(entity);
            } catch (Exception e) {
                log.warn("操作日志写入失败: {}", e.getMessage());
            }
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object[] filtered = Arrays.stream(args)
                .filter(a -> !(a instanceof HttpServletRequest)
                        && !(a instanceof HttpServletResponse)
                        && !(a instanceof MultipartFile))
                .toArray();
        if (filtered.length == 0) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(filtered), 2000);
        } catch (Exception e) {
            return truncate(Arrays.stream(filtered)
                    .map(String::valueOf)
                    .collect(Collectors.joining(", ")), 2000);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
