package com.rag.controller;

import com.rag.annotation.OperationLog;
import com.rag.dto.MessageFeedbackRequest;
import com.rag.entity.Conversation;
import com.rag.entity.Message;
import com.rag.entity.SysUser;
import com.rag.mapper.ConversationMapper;
import com.rag.mapper.MessageMapper;
import com.rag.service.UnresolvedQuestionService;
import com.rag.service.UserService;
import com.rag.util.Result;
import com.rag.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * AI 消息级反馈（批次 E 数据飞轮入口）。
 *
 * <p><b>路径刻意不放在 {@code /api/chat/**} 下</b>：那个前缀在 SecurityConfig 里是 permitAll，
 * 挂在那儿就得在方法内自证归属、且任何人都能匿名刷反馈。
 * 放在 {@code /api/message/**} 走认证，天然拿到当前用户，归属校验就只剩一句比对。
 *
 * <p>代价是游客（未登录）不能反馈 —— 前端对未登录用户隐藏 👍/👎 按钮。
 *
 * <p>「点踩」除了写 message.feedback，还会把该问题沉淀进未解决问题池（source=2）。
 */
@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
public class MessageFeedbackController {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final UserService userService;
    private final UnresolvedQuestionService unresolvedService;

    @OperationLog(module = "问答反馈", action = "提交消息反馈")
    @PostMapping("/{id}/feedback")
    public Result<Void> feedback(@PathVariable Long id, @RequestBody @Valid MessageFeedbackRequest request) {
        String username = SecurityUtil.currentUsername();
        if (username == null) {
            return Result.error("请先登录后再评价");
        }
        SysUser user = userService.getByUsername(username);
        if (user == null) {
            return Result.error("用户不存在");
        }
        Message message = messageMapper.selectById(id);
        if (message == null) {
            return Result.error("消息不存在");
        }
        if (!"AI".equals(message.getSenderType())) {
            return Result.error("只能评价机器人的回答");
        }
        Conversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null || !user.getId().equals(conversation.getUserId())) {
            return Result.error("无权评价该消息");
        }

        message.setFeedback(request.getFeedback());
        message.setFeedbackTime(LocalDateTime.now());
        message.setFeedbackComment(truncate(request.getComment(), 500));
        messageMapper.updateById(message);

        // 点踩 → 沉淀进未解决问题池；点赞是正向信号，不入池
        if (Integer.valueOf(2).equals(request.getFeedback())) {
            unresolvedService.record(
                    lastUserQuestion(message),
                    UnresolvedQuestionService.SOURCE_DISLIKE,
                    null,
                    message.getConversationId(),
                    message.getId(),
                    user.getId());
        }
        return Result.success();
    }

    /**
     * 取该 AI 回答前面最近的一条用户提问，作为入池的问题原文。
     * 池子里要的是「用户问了什么」，而不是「机器人答了什么」。
     */
    private String lastUserQuestion(Message aiMessage) {
        try {
            Message lastUser = messageMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, aiMessage.getConversationId())
                            .eq(Message::getSenderType, "USER")
                            .lt(Message::getId, aiMessage.getId())
                            .orderByDesc(Message::getId)
                            .last("LIMIT 1"));
            return lastUser == null ? aiMessage.getContent() : lastUser.getContent();
        } catch (Exception e) {
            return aiMessage.getContent();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
