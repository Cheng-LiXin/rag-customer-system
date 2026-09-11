package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单
 */
@Data
@TableName("ticket")
public class Ticket {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提交用户ID */
    private Long userId;

    /** 关联会话ID */
    private Long conversationId;

    /** 工单标题 */
    private String title;

    /** 问题描述 */
    private String description;

    /** 问题分类 */
    private String category;

    /** 优先级：1-低 2-中 3-高 */
    private Integer priority;

    /** 状态：1-待处理 2-处理中 3-已解决 4-已关闭 */
    private Integer status;

    /** 处理人（客服）ID */
    private Long assigneeId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 关闭时间 */
    private LocalDateTime closeTime;
}
