package com.rag.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.entity.SysUser;
import com.rag.mapper.SysUserMapper;
import com.rag.mapper.SysUserRoleMapper;
import com.rag.service.AccountNoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时初始化默认账号（密码以 BCrypt 加密写入，保证开箱即用），并确保角色绑定。
 *
 * <p><b>是否重置已有账号的密码由 {@code rag.demo.reset-passwords} 控制</b>：
 * 本地开发开着（改坏了重启即恢复）；<b>部署环境必须关掉</b> ——
 * 否则你在后台改过的密码会在下次重启被悄悄改回默认值，等于白改，
 * 而且默认口令写在仓库里，等于没有认证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccountNoService accountNoService;

    /** 是否在每次启动时把已存在的 demo 账号密码重置为下面的默认值 */
    @Value("${rag.demo.reset-passwords:true}")
    private boolean resetPasswords;

    /** 以下三个是 application.yml 缺失对应 key 时的兜底值：改演示口令时须与 yml 里的 ${DEMO_*_PASSWORD:...} 同步 */
    @Value("${rag.demo.admin-password:Admin@Ysu2026}")
    private String adminPassword;

    @Value("${rag.demo.agent-password:Agent@Ysu2026}")
    private String agentPassword;

    @Value("${rag.demo.user-password:User@Ysu2026}")
    private String userPassword;

    /** demo 账号元数据：用户名 / 明文密码 / 角色ID（1-管理员 2-客服 3-普通用户） */
    private record DemoAccount(String username, String password, Long roleId) {
    }

    private List<DemoAccount> demoAccounts() {
        return List.of(
                new DemoAccount("admin", adminPassword, 1L),
                new DemoAccount("agent", agentPassword, 2L),
                new DemoAccount("user", userPassword, 3L)
        );
    }

    @Override
    public void run(String... args) {
        if (!resetPasswords) {
            log.info("rag.demo.reset-passwords=false —— 只补建缺失账号与角色绑定，不改动已有密码");
        }
        for (DemoAccount acc : demoAccounts()) {
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
            } else if (resetPasswords) {
                // 本地开发：重置为默认密码，确保演示可登录
                user.setPassword(passwordEncoder.encode(acc.password()));
                sysUserMapper.updateById(user);
            }
            // 确保角色绑定（与密码无关，始终执行）
            List<Long> roleIds = sysUserRoleMapper.selectRoleIds(user.getId());
            if (roleId != null && !roleIds.contains(roleId)) {
                sysUserRoleMapper.insert(user.getId(), roleId);
            }
        }
    }
}
