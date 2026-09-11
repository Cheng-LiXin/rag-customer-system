package com.rag.dto;

import lombok.Data;

/**
 * 转人工请求（F04）
 */
@Data
public class TransferRequest {

    /** 已有会话ID（可为空，为空则新建人工会话） */
    private Long conversationId;

    /** 用户ID */
    private Long userId;

    /** 问题摘要（用于会话标题） */
    private String summary;
}
