package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.annotation.OperationLog;
import com.rag.dto.PageResult;
import com.rag.dto.TicketRequest;
import com.rag.entity.SysUser;
import com.rag.entity.Ticket;
import com.rag.mapper.TicketMapper;
import com.rag.service.UserService;
import com.rag.util.Result;
import com.rag.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 工单管理（F06）：创建、列表、详情、状态流转、分配
 */
@RestController
@RequestMapping("/api/ticket")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','AGENT')")
public class TicketController {

    private final TicketMapper ticketMapper;
    private final UserService userService;

    @OperationLog(module = "工单管理", action = "创建工单")
    @PostMapping
    public Result<Ticket> create(@RequestBody @Valid TicketRequest request) {
        Ticket ticket = new Ticket();
        ticket.setUserId(request.getUserId() == null ? 0L : request.getUserId());
        ticket.setConversationId(request.getConversationId());
        ticket.setTitle(request.getTitle());
        ticket.setDescription(request.getDescription());
        ticket.setCategory(request.getCategory());
        ticket.setPriority(request.getPriority() == null ? 2 : request.getPriority());
        ticket.setStatus(1); // 待处理
        // 客服创建工单时自动分配给自己
        if (request.getAssigneeId() != null) {
            ticket.setAssigneeId(request.getAssigneeId());
        } else {
            String username = SecurityUtil.currentUsername();
            SysUser user = username == null ? null : userService.getByUsername(username);
            if (user != null) {
                ticket.setAssigneeId(user.getId());
                ticket.setStatus(2); // 客服自建工单直接进入处理中
            }
        }
        ticketMapper.insert(ticket);
        return Result.success(ticket);
    }

    @GetMapping("/page")
    public Result<PageResult<Ticket>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) Integer priority,
                                           @RequestParam(required = false) Long assigneeId) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Ticket::getStatus, status);
        }
        if (priority != null) {
            wrapper.eq(Ticket::getPriority, priority);
        }
        if (assigneeId != null) {
            wrapper.eq(Ticket::getAssigneeId, assigneeId);
        }
        wrapper.orderByDesc(Ticket::getId);
        Page<Ticket> page = ticketMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.success(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<Ticket> detail(@PathVariable Long id) {
        return Result.success(ticketMapper.selectById(id));
    }

    /** 状态流转：1-待处理 2-处理中 3-已解决 4-已关闭 */
    @OperationLog(module = "工单管理", action = "流转工单状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            return Result.error("工单不存在");
        }
        ticket.setStatus(status);
        if (status != null && status == 4) {
            ticket.setCloseTime(LocalDateTime.now());
        }
        ticketMapper.updateById(ticket);
        return Result.success();
    }

    /** 分配给指定客服（进入处理中状态） */
    @OperationLog(module = "工单管理", action = "分配工单")
    @PutMapping("/{id}/assign")
    public Result<Void> assign(@PathVariable Long id, @RequestParam Long agentId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            return Result.error("工单不存在");
        }
        ticket.setAssigneeId(agentId);
        ticket.setStatus(2);
        ticketMapper.updateById(ticket);
        return Result.success();
    }
}
