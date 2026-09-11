package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识切片（对应 PGVector 中的向量文档）
 */
@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识分类ID */
    private Long categoryId;

    /** 标题 */
    private String title;

    /** 切片内容 */
    private String content;

    /** 来源：MANUAL/WEB/DOC */
    private String sourceType;

    /** 来源链接 */
    private String sourceUrl;

    /** 源标题（该片段所属源文的干净标题；用于热门知识按源文聚合时取标题，与带小标题的 title 分离） */
    private String sourceTitle;

    /** 切片序号 */
    private Integer chunkIndex;

    /** PGVector 中对应文档ID */
    private String vectorId;

    /** 向量化状态：0-待向量化 1-已向量化 2-失败 */
    private Integer vectorStatus;

    /** 关键词（逗号分隔） */
    private String keywords;

    /** 被检索命中次数 */
    private Integer hitCount;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    /** 逻辑删除：0-未删除 1-已删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
