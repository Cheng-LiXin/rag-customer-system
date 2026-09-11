package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 角色新增/编辑请求（F08 角色管理）
 */
@Data
public class RoleRequest {

    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    private String description;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    /** 分配的权限ID列表 */
    private List<Long> permissionIds;
}
