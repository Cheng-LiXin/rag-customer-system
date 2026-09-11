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

    private LocalDateTime createTime;
}
