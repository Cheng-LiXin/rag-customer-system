package com.rag.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.entity.SysUser;
import com.rag.mapper.SysUserMapper;
import com.rag.mapper.SysUserRoleMapper;
import com.rag.service.AccountNoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时初始化默认账号（密码以 BCrypt 加密写入，保证开箱即用）。
 * 每次启动会重置为默认密码，并确保角色绑定，便于演示登录与 RBAC。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccountNoService accountNoService;

    /** demo 账号元数据：用户名 / 明文密码 / 角色ID（1-管理员 2-客服 3-普通用户） */
    private record DemoAccount(String username, String password, Long roleId) {
    }

    private static final List<DemoAccount> DEFAULT_ACCOUNTS = List.of(
            new DemoAccount("admin", "admin123", 1L),
            new DemoAccount("agent", "agent123", 2L),
            new DemoAccount("user", "user123", 3L)
    );

    @Override
    public void run(String... args) {
        for (DemoAccount acc : DEFAULT_ACCOUNTS) {
            String username = acc.username();
            Long roleId = acc.roleId();
            SysUser user = sysUserMapper.selectOne(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
            if (user == null) {
                user = new SysUser();
                // 账号号经分配器取得：admin=130001 agent=120001 user=110001（sys_id_seq 首启从 0 起）
                user.setId(accountNoService.nextId(AccountNoService.roleIdToPrefix(roleId)));
                user.setUsername(username);
                user.setNickname(username);
                user.setStatus(1);
                user.setPassword(passwordEncoder.encode(acc.password()));
                sysUserMapper.insert(user);
                log.info("初始化默认账号: {} (id={})", username, user.getId());
            } else {
                // 重置为默认密码，确保演示可登录
                user.setPassword(passwordEncoder.encode(acc.password()));
                sysUserMapper.updateById(user);
            }
            // 确保角色绑定
            List<Long> roleIds = sysUserRoleMapper.selectRoleIds(user.getId());
            if (roleId != null && !roleIds.contains(roleId)) {
                sysUserRoleMapper.insert(user.getId(), roleId);
            }
        }
    }
}
