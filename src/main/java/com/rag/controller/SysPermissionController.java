package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.entity.SysPermission;
import com.rag.mapper.SysPermissionMapper;
import com.rag.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限查询（F08）：仅管理员可用
 */
@RestController
@RequestMapping("/api/admin/permission")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SysPermissionController {

    private final SysPermissionMapper sysPermissionMapper;

    @GetMapping("/list")
    public Result<List<SysPermission>> list() {
        return Result.success(sysPermissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getSortOrder)));
    }
}
