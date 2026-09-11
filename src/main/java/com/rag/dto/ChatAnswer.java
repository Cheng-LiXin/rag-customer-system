package com.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能问答结果（同时作为 Redis 缓存值序列化对象）
 */
@Data
public class ChatAnswer {

    /** 生成的回答 */
    private String answer;

    /** 是否来自缓存 */
    private boolean fromCache;

    /** 会话ID */
    private Long conversationId;

    /** 引用来源 */
    private List<Source> sources;

    /** 识别到的意图分类（F03） */
    private String intentCategory;

    /** 生成时间 */
    private LocalDateTime createdAt;
}
