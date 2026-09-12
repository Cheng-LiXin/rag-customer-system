package com.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 引用来源：回答所依据的知识片段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Source {

    /** 引用序号（1 基）：答案正文中的行内标记 [n] 即指向 sources[n-1] */
    private Integer index;

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

    /** 来源文件的干净标题（knowledge_chunk.source_title，无小标题前缀） */
    private String sourceTitle;

    /** 来源文件的官方链接（knowledge_chunk.source_url） */
    private String sourceUrl;

    /** 知识片段最近更新时间（供「点击展开原条目」展示时效性） */
    private LocalDateTime updateTime;
}
