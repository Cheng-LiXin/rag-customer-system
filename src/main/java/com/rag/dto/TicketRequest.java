package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 工单创建请求（F06）
 */
@Data
public class TicketRequest {

    /** 关联会话ID */
    private Long conversationId;

    /** 提交用户ID */
    private Long userId;

    @NotBlank(message = "工单标题不能为空")
    private String title;

    private String description;

    private String category;

    /** 优先级：1-低 2-中 3-高 */
    private Integer priority;

    /** 指定处理人（客服ID），为空则自动分配当前用户 */
    private Long assigneeId;
}
