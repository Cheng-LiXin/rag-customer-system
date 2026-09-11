package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.dto.ChatAnswer;
import com.rag.dto.ChatRequest;
import com.rag.dto.PageResult;
import com.rag.entity.Conversation;
import com.rag.entity.Message;
import com.rag.entity.SysUser;
import com.rag.mapper.ConversationMapper;
import com.rag.mapper.MessageMapper;
import com.rag.service.ChatService;
import com.rag.service.UserService;
import com.rag.util.Result;
import com.rag.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 问答控制器：普通问答（RAG）+ SSE 流式问答（SRS 4.1.4）+ 历史会话
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final UserService userService;

    /** 普通问答（SRS 接口：POST /api/chat/ask） */
    @PostMapping("/ask")
    public Result<ChatAnswer> ask(@RequestBody @Valid ChatRequest request) {
        return Result.success(chatService.ask(request.getConversationId(), request.getMessage()));
    }

    /** 兼容旧接口别名 */
    @PostMapping("/send")
    public Result<ChatAnswer> send(@RequestBody @Valid ChatRequest request) {
        return Result.success(chatService.ask(request.getConversationId(), request.getMessage()));
    }

    /** SSE 流式问答（浏览器 EventSource 可直接消费） */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message,
                               @RequestParam(required = false) Long conversationId) {
        return chatService.stream(conversationId, message);
    }

    /** 已登录用户的历史会话列表 */
    @GetMapping("/history")
    public Result<PageResult<Conversation>> history(@RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "20") long pageSize) {
        String username = SecurityUtil.currentUsername();
        if (username == null) {
            return Result.success(PageResult.of(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>()));
        }
        SysUser user = userService.getByUsername(username);
        if (user == null) {
            return Result.success(PageResult.of(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>()));
        }
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, user.getId())
                .orderByDesc(Conversation::getId);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Conversation> page =
                conversationMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize), wrapper);
        return Result.success(PageResult.of(page));
    }

    /** 查看指定会话的消息列表（需登录且为会话主人） */
    @GetMapping("/history/{conversationId}/messages")
    public Result<List<Message>> historyMessages(@PathVariable Long conversationId) {
        String username = SecurityUtil.currentUsername();
        if (username == null) {
            return Result.error("请先登录");
        }
        SysUser user = userService.getByUsername(username);
        if (user == null) {
            return Result.error("用户不存在");
        }
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(user.getId())) {
            return Result.error("会话不存在或无权访问");
        }
        List<Message> messages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getId));
        return Result.success(messages);
    }
}
