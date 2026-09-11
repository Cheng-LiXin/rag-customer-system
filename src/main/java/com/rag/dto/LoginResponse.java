package com.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {

    /** JWT 访问令牌 */
    private String token;

    /** 登录用户名 */
    private String username;
}
