package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.annotation.OperationLog;
import com.rag.dto.GuardToggleRequest;
import com.rag.dto.PageResult;
import com.rag.entity.GuardEvent;
import com.rag.guard.GuardProperties;
import com.rag.guard.GuardSwitchService;
import com.rag.mapper.GuardEventMapper;
import com.rag.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注入防护管理端：开关状态 / 运行时切换 / 审计事件查询。仅管理员。
 *
 * <p>注意：URL 前缀是 {@code /api/admin/**}，不在 SecurityConfig 的 permitAll 白名单里，
 * 但仍加类级 {@code @PreAuthorize} 双保险（与 BannedWordController 同风格）。
 */
@RestController
@RequestMapping("/api/admin/guard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class GuardController {

    private final GuardSwitchService switchService;
    private final GuardProperties props;
    private final GuardEventMapper guardEventMapper;

    /** 当前防护状态：是否开启、取值来源、各层动作 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        GuardProperties.Injection cfg = props.getInjection();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", switchService.isEnabled());
        data.put("source", switchService.source());
        data.put("configEnabled", cfg.isEnabled());
        data.put("runtimeSwitch", cfg.isRuntimeSwitch());
        data.put("inputLevel", cfg.getInputLevel());
        data.put("contextLevel", cfg.getContextLevel());
        data.put("outputLevel", cfg.getOutputLevel());
        return Result.success(data);
    }

    /**
     * 运行时切换防护开关（答辩演示用：关 → 复现漏洞 → 开 → 拦截）。
     * 切换动作本身由 @OperationLog 落到 operation_log，形成独立于 Redis 的审计痕迹。
     */
    @OperationLog(module = "安全防护", action = "切换注入防护")
    @PostMapping("/toggle")
    public Result<Map<String, Object>> toggle(@RequestBody GuardToggleRequest request) {
        if (request == null || request.getEnabled() == null) {
            switchService.clearOverride();
        } else {
            switchService.setEnabled(request.getEnabled());
        }
        return status();
    }

    /** 审计事件分页查询 */
    @GetMapping("/events")
    public Result<PageResult<GuardEvent>> events(@RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "10") long pageSize,
                                                 @RequestParam(required = false) String layer) {
        LambdaQueryWrapper<GuardEvent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(layer)) {
            wrapper.eq(GuardEvent::getLayer, layer);
        }
        wrapper.orderByDesc(GuardEvent::getId);
        Page<GuardEvent> page = guardEventMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.success(PageResult.of(page));
    }
}
