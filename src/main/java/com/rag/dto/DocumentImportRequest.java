package com.rag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 文档上传导入请求：前端预览确认后，把选中的切块提交入库（F02）。
 */
@Data
public class DocumentImportRequest {

    /** 归属分类ID（0 或 null 表示不分类） */
    private Long categoryId;

    /** 源标题（通常取文件名），落库到 source_title，供热门知识按源文聚合 */
    private String sourceTitle;

    /** 来源链接（可选） */
    private String sourceUrl;

    /** 选中的切块列表 */
    @NotEmpty(message = "请至少选择一条切块")
    @Valid
    private List<Item> chunks;

    @Data
    public static class Item {

        /** 切块标题（为空时回退到 sourceTitle / 「文档片段」） */
        private String title;

        @NotBlank(message = "切块内容不能为空")
        private String content;
    }
}
