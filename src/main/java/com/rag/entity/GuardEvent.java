package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 注入防护审计事件：每一次命中（拦截或仅记录）都落一行，供管理端复核与规则调优。
 *
 * <p>与 {@code operation_log} 的分工：后者记录「谁调了哪个写接口」，前者记录
 * 「哪一次问答、在哪一层、被哪条规则命中、命中了什么原文」—— 攻击面是问答热路径，
 * 不在 @OperationLog 的切点范围内。
 */
@Data
@TableName("guard_event")
public class GuardEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 命中层：input 输入层 / context 上下文层（间接注入）/ output 输出层 */
    private String layer;

    /** 命中的规则 ID */
    private String ruleId;

    /** 动作：BLOCK 拦截 / LOG 仅记录 */
    private String action;

    /** 触发本次问答的用户问题 */
    private String question;

    /** 上下文层命中时：被污染的知识片段 chunk_id */
    private String chunkId;

    /** 命中处上下文（前后各约 40 字，已截断），供人工复核而非只存一个词 */
    private String hitText;

    /** 当前登录用户名（游客为 null） */
    private String username;

    /** 客户端 IP */
    private String ip;

    private LocalDateTime createTime;
}
