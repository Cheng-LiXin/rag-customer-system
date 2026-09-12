package com.rag.dto;

import lombok.Data;

/**
 * 未解决问题处理请求：标记「已补知识 / 已忽略」并记录补充的知识片段。
 */
@Data
public class UnresolvedHandleRequest {

    /** 2-已补知识 3-已忽略 */
    private Integer status;

    /** 补充的知识片段 ID（闭环落点） */
    private Long knowledgeChunkId;

    private String remark;
}
