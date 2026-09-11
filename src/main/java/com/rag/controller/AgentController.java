package com.rag.controller;

import com.rag.annotation.OperationLog;
import com.rag.dto.TransferRequest;
import com.rag.entity.SysUser;
import com.rag.service.AgentService;
import com.rag.service.UserService;
import com.rag.util.Result;
import com.rag.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 人工客服转接（F04）
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final UserService userService;

    /** 客服工作台：在线客服、排队会话、我名下的会话 */
    @GetMapping("/workbench")
    @PreAuthorize("hasAnyRole('ADMIN','AGENT')")
    public Result<Map<String, Object>> workbench() {
        String username = SecurityUtil.currentUsername();
        SysUser user = username == null ? null : userService.getByUsername(username);
        Long agentId = user == null ? 0L : user.getId();

        Map<String, Object> result = new HashMap<>();
        result.put("agentId", agentId);
        result.put("onlineAgents", agentService.onlineAgents());
        result.put("queue", agentService.listQueueConversations());
        result.put("mine", agentService.listAssignedConversations(agentId));
        result.put("ended", agentService.listEndedConversations(agentId));
        return Result.success(result);
    }

    /** 用户点击「转人工客服」 */
    @OperationLog(module = "人工客服", action = "用户转人工")
    @PostMapping("/transfer")
    public Result<Map<String, Object>> transfer(@RequestBody @Valid TransferRequest request) {
        Long userId = request.getUserId();
        if (userId == null) {
            String username = SecurityUtil.currentUsername();
            SysUser user = username == null ? null : userService.getByUsername(username);
            userId = user == null ? 0L : user.getId();
        }
        return Result.success(agentService.transfer(
                request.getConversationId(), userId, request.getSummary()));
    }

    /** 查询排队位置 */
    @GetMapping("/queue/{conversationId}")
    public Result<Map<String, Object>> queue(@PathVariable Long conversationId) {
        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", conversationId);
        result.put("position", agentService.queuePosition(conversationId));
        return Result.success(result);
    }

    /** 客服关闭会话（触发满意度评价） */
    @OperationLog(module = "人工客服", action = "结束会话")
    @PostMapping("/conversation/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        agentService.closeConversation(id);
        return Result.success();
    }

    /** 客服已读会话，清空未读数 */
    @PostMapping("/conversation/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        agentService.clearUnread(id);
        return Result.success();
    }

    /** 客服重新接待已结束会话 */
    @OperationLog(module = "人工客服", action = "重新接待会话")
    @PostMapping("/conversation/{id}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','AGENT')")
    public Result<Void> reopen(@PathVariable Long id) {
        String username = SecurityUtil.currentUsername();
        SysUser user = username == null ? null : userService.getByUsername(username);
        Long agentId = user == null ? 0L : user.getId();
        agentService.reopen(id, agentId);
        return Result.success();
    }
}
