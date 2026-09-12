package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息
 */
@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID */
    private Long conversationId;

    /** 发送方：USER-用户 AI-机器人 AGENT-客服 */
    private String senderType;

    /** 消息内容 */
    private String content;

    /** 引用知识片段（JSON 数组） */
    private String citations;

    /** 意图分类（F03 持久化） */
    private String intentCategory;

    /** 是否来自缓存：0-否 1-是 */
    private Integer fromCache;

    /** 消息类型：TEXT/IMAGE */
    private String messageType;

    /** 状态：1-正常 0-撤回 */
    private Integer status;

    /** AI 消息的用户反馈：0-未评 1-有帮助 2-没帮助（批次 E 数据飞轮入口） */
    private Integer feedback;

    /** 反馈时间 */
    private LocalDateTime feedbackTime;

    /** 点踩时可选填的原因（用于判断是"没召回"还是"答偏了"） */
    private String feedbackComment;

    private LocalDateTime createTime;
}
