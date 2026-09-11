package com.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.entity.SysUser;
import com.rag.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security 用户加载器：基于 sys_user 表认证，并从 RBAC 关联表加载角色与权限。
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper sysUserMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getStatus, 1));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在或已禁用");
        }

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();

        // 角色 → ROLE_ 前缀（供 @PreAuthorize("hasRole('ADMIN')") 使用）
        List<String> roleCodes = sysUserMapper.selectRoleCodes(user.getId());
        for (String code : roleCodes) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + code));
        }

        // 权限编码 → 原样（供 @PreAuthorize("hasAuthority('sys:user')") 使用）
        List<String> permissionCodes = sysUserMapper.selectPermissionCodes(user.getId());
        for (String code : permissionCodes) {
            authorities.add(new SimpleGrantedAuthority(code));
        }

        return User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .authorities(authorities)
                .build();
    }
}
