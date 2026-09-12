package com.rag.dto;

import lombok.Data;

/**
 * 注入防护运行时开关的切换请求。
 * {@code enabled} 为 null 表示清除运行时覆盖、回到配置打底值。
 */
@Data
public class GuardToggleRequest {

    private Boolean enabled;
}
