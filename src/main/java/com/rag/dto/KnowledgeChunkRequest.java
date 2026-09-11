package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 知识片段新增/编辑请求（F02）
 */
@Data
public class KnowledgeChunkRequest {

    /** 分类ID */
    private Long categoryId;

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    /** 关键词（逗号分隔） */
    private String keywords;

    /** 来源链接 */
    private String sourceUrl;

    /** 源标题（片段所属源文的干净标题） */
    private String sourceTitle;
}
