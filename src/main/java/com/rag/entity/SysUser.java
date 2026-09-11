package com.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 账号ID：6 位 = 角色前缀(11用户/12客服/13管理员) + 4 位序号；非自增，由 AccountNoService 分配 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 用户名（自助注册=手机号） */
    private String username;

    /** 密码（BCrypt 加密） */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 昵称最近修改时间（昵称 30 天内仅可改 1 次，管理端代改不刷新此时间戳） */
    private LocalDateTime nicknameUpdatedAt;

    /** 头像地址 */
    private String avatar;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 所在省份 */
    private String province;

    /** 所在城市 */
    private String city;

    /** 身份：考生/本科生/硕士生 */
    private String identity;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    /** 逻辑删除：0-未删除 1-已删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
