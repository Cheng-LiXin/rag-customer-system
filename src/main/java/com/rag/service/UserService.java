package com.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rag.entity.SysUser;

public interface UserService extends IService<SysUser> {

    /** 根据用户名查询用户 */
    SysUser getByUsername(String username);
}
