package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 违禁词（昵称/个人资料屏蔽，admin 后台维护）
 */
@Data
@TableName("sys_banned_word")
public class BannedWord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 违禁词 */
    private String word;

    /** 状态：1-启用 0-停用 */
    private Integer status;

    private LocalDateTime createTime;
}
