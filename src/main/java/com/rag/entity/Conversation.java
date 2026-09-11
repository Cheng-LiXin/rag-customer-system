package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话
 */
@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 归属用户昵称/用户名（展示用，非数据库字段，由会话管理查询时回填；userId=0 表示游客） */
    @TableField(exist = false)
    private String userName;

    /** 会话标题 */
    private String title;

    /** 会话类型：AUTO-机器人 HUMAN-人工 */
    private String sessionType;

    /** 状态：1-进行中 2-已结束 */
    private Integer status;

    /** 处理客服ID（会话结束后保留归属） */
    private Long agentId;

    /** 最后回复时间（消息写入时更新） */
    private LocalDateTime lastReplyTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
