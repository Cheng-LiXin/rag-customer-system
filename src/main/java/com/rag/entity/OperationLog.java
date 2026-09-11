package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志（F09）：记录用户对系统的写操作，用于审计追溯。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作用户名 */
    private String username;

    /** 操作模块 */
    private String module;

    /** 操作描述 */
    private String action;

    /** HTTP 方法 */
    private String method;

    /** 请求路径 */
    private String uri;

    /** 客户端 IP */
    private String ip;

    /** 请求参数（JSON） */
    private String params;

    /** 状态：1-成功 0-失败 */
    private Integer status;

    /** 失败原因 */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long costTime;

    private LocalDateTime createTime;
}
