package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.dto.PageResult;
import com.rag.entity.Conversation;
import com.rag.entity.Message;
import com.rag.entity.SysUser;
import com.rag.mapper.ConversationMapper;
import com.rag.mapper.MessageMapper;
import com.rag.mapper.SysUserMapper;
import com.rag.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话管理（F05）：管理员/客服可查看会话记录
 */
@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','AGENT')")
public class ConversationController {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final SysUserMapper sysUserMapper;

    @GetMapping("/page")
    public Result<PageResult<Conversation>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "10") long pageSize,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(required = false) Long agentId,
                                                 @RequestParam(required = false) String sortBy,
                                                 @RequestParam(defaultValue = "desc") String sortOrder) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(type)) {
            wrapper.eq(Conversation::getSessionType, type);
        }
        if (status != null) {
            wrapper.eq(Conversation::getStatus, status);
        }
        if (agentId != null) {
            wrapper.eq(Conversation::getAgentId, agentId);
        }
        // 排序：支持 createTime / lastReplyTime
        if ("createTime".equals(sortBy) || "lastReplyTime".equals(sortBy)) {
            boolean asc = "asc".equalsIgnoreCase(sortOrder);
            if ("createTime".equals(sortBy)) {
                if (asc) wrapper.orderByAsc(Conversation::getCreateTime);
                else wrapper.orderByDesc(Conversation::getCreateTime);
            } else {
                if (asc) wrapper.orderByAsc(Conversation::getLastReplyTime);
                else wrapper.orderByDesc(Conversation::getLastReplyTime);
            }
        } else {
            wrapper.orderByDesc(Conversation::getId);
        }
        Page<Conversation> page = conversationMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        // 回填归属用户昵称，便于后台展示（游客 userId=0 留空，前端显示「游客」）
        fillUserName(page.getRecords());
        return Result.success(PageResult.of(page));
    }

    /** 按 userId 批量回填会话归属用户的昵称/用户名（非数据库列，仅供展示） */
    private void fillUserName(List<Conversation> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> userIds = records.stream()
                .map(Conversation::getUserId)
                .filter(id -> id != null && id != 0L)
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return;
        }
        List<SysUser> users = sysUserMapper.selectBatchIds(userIds);
        Map<Long, String> nameById = users.stream().collect(Collectors.toMap(
                SysUser::getId,
                u -> StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername()));
        for (Conversation c : records) {
            if (c.getUserId() != null && nameById.containsKey(c.getUserId())) {
                c.setUserName(nameById.get(c.getUserId()));
            }
        }
    }

    @GetMapping("/{id}")
    public Result<Conversation> detail(@PathVariable Long id) {
        return Result.success(conversationMapper.selectById(id));
    }

    @GetMapping("/{id}/messages")
    public Result<List<Message>> messages(@PathVariable Long id) {
        return Result.success(messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, id)
                        .orderByAsc(Message::getId)));
    }

    /** 会话归属客户的详细资料（客服工作台「名片」用；游客 userId=0 返回 guest 标记） */
    @GetMapping("/{id}/customer")
    public Result<Map<String, Object>> customer(@PathVariable Long id) {
        Conversation conv = conversationMapper.selectById(id);
        if (conv == null) {
            return Result.error("会话不存在");
        }
        if (conv.getUserId() == null || conv.getUserId() == 0L) {
            Map<String, Object> guest = new HashMap<>();
            guest.put("userId", 0L);
            guest.put("guest", true);
            return Result.success(guest);
        }
        SysUser user = sysUserMapper.selectById(conv.getUserId());
        if (user == null) {
            return Result.error("会话归属用户不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("avatar", user.getAvatar());
        data.put("email", user.getEmail());
        data.put("phone", user.getPhone());
        data.put("province", user.getProvince());
        data.put("city", user.getCity());
        data.put("identity", user.getIdentity());
        data.put("status", user.getStatus());
        data.put("createTime", user.getCreateTime());
        return Result.success(data);
    }
}
