package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限名称 */
    private String permissionName;

    /** 权限编码（如 sys:user） */
    private String permissionCode;

    /** 类型：1-菜单 2-按钮 */
    private Integer type;

    /** 父权限ID，0 表示根 */
    private Long parentId;

    /** 路由路径 */
    private String path;

    /** 图标 */
    private String icon;

    /** 排序号 */
    private Integer sortOrder;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
