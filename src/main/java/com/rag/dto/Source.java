package com.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用来源：回答所依据的知识片段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Source {

    /** 知识片段ID（knowledge_chunk.id） */
    private String chunkId;

    /** 片段标题 */
    private String title;

    /** 分类名称 */
    private String category;

    /** 片段内容摘要 */
    private String content;

    /** 相似度分数（0~1，越接近 1 越相似） */
    private Double score;
}
