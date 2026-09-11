package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.annotation.OperationLog;
import com.rag.dto.PageResult;
import com.rag.dto.UserRequest;
import com.rag.entity.SysUser;
import com.rag.mapper.SysUserMapper;
import com.rag.mapper.SysUserRoleMapper;
import com.rag.service.AccountNoService;
import com.rag.service.BannedWordService;
import com.rag.util.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 用户管理（F08）：仅管理员可用
 */
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SysUserController {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccountNoService accountNoService;
    private final BannedWordService bannedWordService;

    @GetMapping("/page")
    public Result<PageResult<SysUser>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword));
        }
        wrapper.orderByDesc(SysUser::getId);
        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        page.getRecords().forEach(u -> u.setPassword(null)); // 不泄露密码
        return Result.success(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<SysUser> detail(@PathVariable Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user != null) {
            user.setPassword(null);
        }
        return Result.success(user);
    }

    @OperationLog(module = "用户管理", action = "新增用户")
    @PostMapping
    public Result<Void> create(@RequestBody @Valid UserRequest request) {
        long exists = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (exists > 0) {
            return Result.error("用户名已存在");
        }
        String banned = bannedWordService.bannedMessage(request.getNickname(), "昵称");
        if (banned != null) {
            return Result.error(banned);
        }
        SysUser user = new SysUser();
        // 账号号由分配器按最高角色前缀生成：建管理员→13xxxx、建客服→12xxxx、建用户→11xxxx
        user.setId(accountNoService.nextId(AccountNoService.roleIdsToPrefix(request.getRoleIds())));
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword() == null ? "123456" : request.getPassword()));
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        sysUserMapper.insert(user);
        saveRoles(user.getId(), request.getRoleIds());
        return Result.success();
    }

    @OperationLog(module = "用户管理", action = "编辑用户")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid UserRequest request) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }
        // 仅昵称变更时扫违禁词（改密码/状态/角色不改昵称时不重复查词表；admin 改他人昵称不受 30 天限改约束）
        if (!Objects.equals(user.getNickname(), request.getNickname())) {
            String banned = bannedWordService.bannedMessage(request.getNickname(), "昵称");
            if (banned != null) {
                return Result.error(banned);
            }
        }
        if (StringUtils.hasText(request.getPassword())) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        sysUserMapper.updateById(user);
        saveRoles(id, request.getRoleIds());
        return Result.success();
    }

    @OperationLog(module = "用户管理", action = "删除用户")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysUserMapper.deleteById(id);
        sysUserRoleMapper.deleteByUserId(id);
        return Result.success();
    }

    private void saveRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null) {
            return;
        }
        sysUserRoleMapper.deleteByUserId(userId);
        roleIds.forEach(roleId -> sysUserRoleMapper.insert(userId, roleId));
    }
}
