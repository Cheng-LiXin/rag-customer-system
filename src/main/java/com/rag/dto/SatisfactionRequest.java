package com.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 满意度评价请求（F04）
 */
@Data
public class SatisfactionRequest {

    @NotNull(message = "会话ID不能为空")
    private Long conversationId;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分范围 1-5")
    @Max(value = 5, message = "评分范围 1-5")
    private Integer rating;

    /** 评价用户ID（可为空） */
    private Long userId;

    private String comment;
}
