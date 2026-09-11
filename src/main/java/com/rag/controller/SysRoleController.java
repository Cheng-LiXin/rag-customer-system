package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.annotation.OperationLog;
import com.rag.dto.RoleRequest;
import com.rag.entity.SysRole;
import com.rag.mapper.SysRoleMapper;
import com.rag.mapper.SysRolePermissionMapper;
import com.rag.util.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理（F08）：仅管理员可用
 */
@RestController
@RequestMapping("/api/admin/role")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SysRoleController {

    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;

    @GetMapping("/list")
    public Result<List<SysRole>> list() {
        return Result.success(sysRoleMapper.selectList(
                new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId)));
    }

    @OperationLog(module = "角色管理", action = "新增角色")
    @PostMapping
    public Result<Void> create(@RequestBody @Valid RoleRequest request) {
        SysRole role = new SysRole();
        role.setRoleName(request.getRoleName());
        role.setRoleCode(request.getRoleCode());
        role.setDescription(request.getDescription());
        role.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        sysRoleMapper.insert(role);
        savePermissions(role.getId(), request.getPermissionIds());
        return Result.success();
    }

    @OperationLog(module = "角色管理", action = "编辑角色")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid RoleRequest request) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            return Result.error("角色不存在");
        }
        role.setRoleName(request.getRoleName());
        role.setDescription(request.getDescription());
        if (request.getStatus() != null) {
            role.setStatus(request.getStatus());
        }
        sysRoleMapper.updateById(role);
        savePermissions(id, request.getPermissionIds());
        return Result.success();
    }

    @OperationLog(module = "角色管理", action = "删除角色")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysRoleMapper.deleteById(id);
        sysRolePermissionMapper.deleteByRoleId(id);
        return Result.success();
    }

    private void savePermissions(Long roleId, List<Long> permissionIds) {
        if (permissionIds == null) {
            return;
        }
        sysRolePermissionMapper.deleteByRoleId(roleId);
        permissionIds.forEach(pid -> sysRolePermissionMapper.insert(roleId, pid));
    }
}
