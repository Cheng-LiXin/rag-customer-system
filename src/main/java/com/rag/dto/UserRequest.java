package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 用户新增/编辑请求（F08 用户管理）
 */
@Data
public class UserRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 新增时必填，编辑时留空表示不修改 */
    private String password;

    private String nickname;

    private String email;

    private String phone;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    /** 分配的角色ID列表 */
    private List<Long> roleIds;
}
