package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    /** 会话ID（未接入会话时可传 null） */
    private Long conversationId;

    @NotBlank(message = "消息内容不能为空")
    private String message;
}
