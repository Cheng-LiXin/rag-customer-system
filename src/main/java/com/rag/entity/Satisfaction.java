package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 满意度评价
 */
@Data
@TableName("satisfaction")
public class Satisfaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID */
    private Long conversationId;

    /** 评价用户ID */
    private Long userId;

    /** 关联工单ID */
    private Long ticketId;

    /** 评分：1-5 */
    private Integer rating;

    /** 评价内容 */
    private String comment;

    private LocalDateTime createTime;
}
