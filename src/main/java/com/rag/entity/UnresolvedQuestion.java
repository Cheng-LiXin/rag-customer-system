package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 未解决问题池（批次 E 数据飞轮入口）。
 *
 * <p>四个入口：机器人回兜底文案、用户点踩、相似度过低被拒答、命中注入防护拦截。
 * 同一个问题（按归一化后的 MD5 判定）被反复问到时收敛成一行并累加 {@code hitCount} ——
 * 池子里要看的是「哪些问题最常答不上来」，而不是一屏重复行。
 */
@Data
@TableName("unresolved_question")
public class UnresolvedQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 问题原文（首次入库的那一版） */
    private String question;

    /** 归一化后的 MD5，唯一键，用于收敛重复提问 */
    private String questionHash;

    /** 来源：1-兜底 2-点踩 3-拒答 4-防护拦截 */
    private Integer source;

    /** 被问到的次数 */
    private Integer hitCount;

    /** 最近一次的最高相似度：用来区分「根本没召回」与「召回了但没答上」 */
    private BigDecimal topScore;

    private Long conversationId;

    private Long messageId;

    private Long userId;

    /** 处理状态：1-待处理 2-已补知识 3-已忽略 */
    private Integer status;

    private Long handlerId;

    private LocalDateTime handleTime;

    /** 补充的知识片段 ID（闭环落点） */
    private Long knowledgeChunkId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
