package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.annotation.OperationLog;
import com.rag.dto.SatisfactionRequest;
import com.rag.entity.Satisfaction;
import com.rag.entity.SysUser;
import com.rag.mapper.SatisfactionMapper;
import com.rag.service.UserService;
import com.rag.util.Result;
import com.rag.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 满意度评价（F04）
 *
 * <p>防刷：同一会话最多保留一条评价。已评价时再次提交转为「更新」覆盖（评分/内容），
 * 而不是无限追加新行（旧实现每调一次 insert 一条，会无限堆积并污染 {@code AVG(rating)/COUNT(*)}）。
 * 数据库侧以唯一键 {@code uk_conversation(conversation_id)} 兜底并发首评。
 */
@RestController
@RequestMapping("/api/satisfaction")
@RequiredArgsConstructor
public class SatisfactionController {

    private final SatisfactionMapper satisfactionMapper;
    private final UserService userService;

    @OperationLog(module = "会话", action = "提交满意度评价")
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> submit(@RequestBody @Valid SatisfactionRequest request) {
        // 当前登录用户（本接口需 JWT）：userId 由服务端解析，不信任前端传值
        Long currentUid = currentUserId();
        Long userId = currentUid != null ? currentUid : (request.getUserId() == null ? 0L : request.getUserId());

        Satisfaction existing = findOne(request.getConversationId());
        if (existing != null) {
            existing.setUserId(userId);
            existing.setRating(request.getRating());
            existing.setComment(request.getComment());
            satisfactionMapper.updateById(existing);
            return Result.success();
        }

        Satisfaction satisfaction = new Satisfaction();
        satisfaction.setConversationId(request.getConversationId());
        satisfaction.setUserId(userId);
        satisfaction.setRating(request.getRating());
        satisfaction.setComment(request.getComment());
        try {
            satisfactionMapper.insert(satisfaction);
        } catch (DuplicateKeyException e) {
            // 并发首评：唯一键 uk_conversation 兜底，改更新已入库那条
            Satisfaction winner = findOne(request.getConversationId());
            if (winner != null) {
                winner.setRating(request.getRating());
                winner.setComment(request.getComment());
                satisfactionMapper.updateById(winner);
            }
        }
        return Result.success();
    }

    /** 按会话查已有评价（LIMIT 1：历史脏数据/迁移前可能残留重复行时也不抛异常） */
    private Satisfaction findOne(Long conversationId) {
        return satisfactionMapper.selectOne(new LambdaQueryWrapper<Satisfaction>()
                .eq(Satisfaction::getConversationId, conversationId)
                .last("LIMIT 1"));
    }

    private Long currentUserId() {
        String username = SecurityUtil.currentUsername();
        if (username == null) {
            return null;
        }
        SysUser user = userService.getByUsername(username);
        return user == null ? null : user.getId();
    }
}
