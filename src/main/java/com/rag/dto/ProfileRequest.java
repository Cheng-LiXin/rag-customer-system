package com.rag.dto;

import lombok.Data;

/**
 * 个人资料修改请求
 */
@Data
public class ProfileRequest {

    private String nickname;

    private String email;

    private String phone;

    /** 所在省份 */
    private String province;

    /** 所在城市 */
    private String city;

    /** 身份：考生/本科生/硕士生 */
    private String identity;
}
