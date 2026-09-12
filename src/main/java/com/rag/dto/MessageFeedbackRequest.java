package com.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * AI 消息反馈请求：1-有帮助 2-没帮助
 */
@Data
public class MessageFeedbackRequest {

    @Min(value = 1, message = "反馈值只能是 1（有帮助）或 2（没帮助）")
    @Max(value = 2, message = "反馈值只能是 1（有帮助）或 2（没帮助）")
    private Integer feedback;

    /** 点踩时可选填的原因 */
    private String comment;
}
