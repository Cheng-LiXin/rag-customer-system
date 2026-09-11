package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自助注册请求：手机号作账号（暂不接短信验证码），密码二次确认由前端校验并在后端复核。
 */
@Data
public class RegisterRequest {

    /** 手机号即登录账号 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 昵称（可选，缺省自动生成；含违禁词会被拒） */
    @Size(max = 20, message = "昵称不能超过20个字")
    private String nickname;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度须在6-20位之间")
    private String password;

    /** 确认密码（须与 password 一致） */
    @NotBlank(message = "请再次输入密码")
    private String confirmPassword;
}
