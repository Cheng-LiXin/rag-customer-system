package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 知识分类新增/编辑请求（F02）
 */
@Data
public class CategoryRequest {

    @NotBlank(message = "分类名称不能为空")
    private String name;

    /** 父分类ID，0 表示根 */
    private Long parentId;

    private Integer sortOrder;

    private String description;
}
