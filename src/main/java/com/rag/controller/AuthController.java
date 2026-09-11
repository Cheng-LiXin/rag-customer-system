package com.rag.controller;

import com.rag.dto.LoginRequest;
import com.rag.dto.LoginResponse;
import com.rag.dto.ProfileRequest;
import com.rag.dto.PasswordRequest;
import com.rag.dto.RegisterRequest;
import com.rag.entity.SysUser;
import com.rag.mapper.SysUserRoleMapper;
import com.rag.service.AccountNoService;
import com.rag.service.BannedWordService;
import com.rag.service.UserService;
import com.rag.util.JwtUtil;
import com.rag.util.Result;
import com.rag.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 认证控制器：登录后签发 JWT
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final AccountNoService accountNoService;
    private final BannedWordService bannedWordService;

    /** 昵称 30 天内仅可修改 1 次的间隔（天） */
    private static final int NICKNAME_CHANGE_INTERVAL_DAYS = 30;

    /** 用户角色的角色ID（sys_role 种子：3=USER） */
    private static final long ROLE_USER_ID = 3L;

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        String token = jwtUtil.generateToken(authentication.getName());
        return Result.success(new LoginResponse(token, authentication.getName()));
    }

    /**
     * 自助注册：手机号即登录账号（暂不接短信验证码），仅开放普通用户（前缀 11）。
     * 密码以 BCrypt 加密落库；昵称可选，含违禁词拒绝，填了昵称即开始 30 天改一次计时。
     */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody @Valid RegisterRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            return Result.error("两次输入的密码不一致");
        }
        if (userService.getByUsername(request.getPhone()) != null) {
            return Result.error("该手机号已注册，请直接登录");
        }
        // 昵称：可选；填了则先过违禁词（providedNickname 同时决定是否启动 30 天改昵称计时）
        String nickname = request.getNickname();
        boolean providedNickname = StringUtils.hasText(nickname);
        if (providedNickname) {
            String msg = bannedWordService.bannedMessage(nickname, "昵称");
            if (msg != null) {
                return Result.error(msg);
            }
        } else {
            // 缺省昵称：用户 + 手机尾号4位（不启动 30 天计时，便于立刻改成喜欢的昵称）
            String tail = request.getPhone().length() >= 4 ? request.getPhone().substring(request.getPhone().length() - 4) : request.getPhone();
            nickname = "用户" + tail;
        }
        SysUser user = new SysUser();
        user.setId(accountNoService.nextId(AccountNoService.roleIdToPrefix(ROLE_USER_ID)));
        user.setUsername(request.getPhone());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(nickname);
        user.setPhone(request.getPhone());
        if (providedNickname) {
            user.setNicknameUpdatedAt(LocalDateTime.now());
        }
        user.setStatus(1);
        userService.save(user);
        sysUserRoleMapper.insert(user.getId(), ROLE_USER_ID);
        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        return Result.success(result);
    }

    /** 当前登录用户（未登录返回 null），复用 {@link SecurityUtil} 解析用户名 */
    private SysUser currentUser() {
        String username = SecurityUtil.currentUsername();
        return username == null ? null : userService.getByUsername(username);
    }

    /** 距下次可改昵称的剩余天数：从未改过(updatedAt=null)或已过间隔返回 0（即可改） */
    private long nicknameCooldownDays(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return 0;
        }
        long elapsed = ChronoUnit.DAYS.between(updatedAt, LocalDateTime.now());
        return elapsed >= NICKNAME_CHANGE_INTERVAL_DAYS ? 0 : NICKNAME_CHANGE_INTERVAL_DAYS - elapsed;
    }

    /** 当前登录用户信息（供前端权限路由使用） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        List<String> roles = authentication == null ? List.of()
                : authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("username", authentication == null ? null : authentication.getName());
        result.put("roles", roles);
        if (authentication != null) {
            SysUser user = userService.getByUsername(authentication.getName());
            if (user != null) {
                result.put("id", user.getId());
                result.put("nickname", user.getNickname());
                result.put("avatar", user.getAvatar());
            }
        }
        return Result.success(result);
    }

    /** 获取当前登录用户详细资料 */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Result.error("未登录");
        SysUser user = userService.getByUsername(auth.getName());
        if (user == null) return Result.error("用户不存在");
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("email", user.getEmail());
        data.put("phone", user.getPhone());
        data.put("province", user.getProvince());
        data.put("city", user.getCity());
        data.put("identity", user.getIdentity());
        data.put("avatar", user.getAvatar());
        data.put("createTime", user.getCreateTime());
        data.put("status", user.getStatus());
        // 昵称 30 天限改：返回最近修改时间 + 是否可改 + 距可改剩余天数（0=可改）
        data.put("nicknameUpdatedAt", user.getNicknameUpdatedAt());
        long cooldownDays = nicknameCooldownDays(user.getNicknameUpdatedAt());
        data.put("nicknameEditable", cooldownDays == 0);
        data.put("nicknameCooldownDays", cooldownDays);
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        data.put("roles", roles);
        return Result.success(data);
    }

    /** 修改当前登录用户资料（昵称：30 天内仅可改 1 次 + 违禁词校验） */
    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody @Valid ProfileRequest request) {
        SysUser user = currentUser();
        if (user == null) return Result.error("未登录");
        // 昵称变更时才做 30 天限改与违禁词校验（其他字段随时可改）
        boolean nicknameChanged = !Objects.equals(user.getNickname(), request.getNickname());
        if (nicknameChanged) {
            String msg = bannedWordService.bannedMessage(request.getNickname(), "昵称");
            if (msg != null) {
                return Result.error(msg);
            }
            long remaining = nicknameCooldownDays(user.getNicknameUpdatedAt());
            if (remaining > 0) {
                return Result.error("昵称30天内仅可修改1次，距下次可改还有 " + remaining + " 天");
            }
            user.setNicknameUpdatedAt(LocalDateTime.now());
        }
        // 邮箱变更时才做违禁词校验（没改邮箱不重复扫词表）
        boolean emailChanged = !Objects.equals(user.getEmail(), request.getEmail());
        if (emailChanged) {
            String msg = bannedWordService.bannedMessage(request.getEmail(), "邮箱");
            if (msg != null) {
                return Result.error(msg);
            }
        }
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setProvince(request.getProvince());
        user.setCity(request.getCity());
        user.setIdentity(request.getIdentity());
        userService.updateById(user);
        return Result.success();
    }

    /** 修改密码 */
    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody @Valid PasswordRequest request) {
        SysUser user = currentUser();
        if (user == null) return Result.error("未登录");
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            return Result.error("原密码错误");
        }
        if (request.getNewPassword() == null || request.getNewPassword().length() < 6) {
            return Result.error("新密码长度不能少于6位");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userService.updateById(user);
        return Result.success();
    }
}
