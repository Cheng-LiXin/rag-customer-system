package com.rag.service;

import com.rag.mapper.IdSeqMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账号号分配：6 位账号号 = 角色前缀(11用户/12客服/13管理员) × 10000 + 同前缀4位序号。
 * 取号在事务内先对 sys_id_seq 行做 UPDATE +1（行锁），并发注册/建号串行安全。
 * 纪律：sys_user 一切新增必须先经 {@link #nextId(int)} 拿到账号号再 insert，不留字面量。
 */
@Service
@RequiredArgsConstructor
public class AccountNoService {

    /** 角色前缀：普通用户 */
    public static final int PREFIX_USER = 11;
    /** 角色前缀：人工客服 */
    public static final int PREFIX_AGENT = 12;
    /** 角色前缀：管理员 */
    public static final int PREFIX_ADMIN = 13;

    /** 角色ID → 账号号前缀：1-管理员/2-客服/3-用户（角色表 id，见 schema.sql 种子） */
    public static int roleIdToPrefix(Long roleId) {
        if (roleId == null) {
            return PREFIX_USER;
        }
        if (roleId == 1L) {
            return PREFIX_ADMIN;
        }
        if (roleId == 2L) {
            return PREFIX_AGENT;
        }
        return PREFIX_USER;
    }

    /**
     * 多角色取「最高权限角色」对应的前缀：含管理员(1)→13、否则含客服(2)→12、否则用户(11)。
     * 角色 id 越小权限越高（见 schema.sql 种子），故取数值最小者。id 只反映建号时最高角色，后续改角色不重写。
     */
    public static int roleIdsToPrefix(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return PREFIX_USER;
        }
        return roleIdToPrefix(roleIds.stream().min(Long::compareTo).orElse(3L));
    }

    private final IdSeqMapper idSeqMapper;

    /** 取该前缀下一个 6 位账号号（事务内行锁自增） */
    @Transactional
    public long nextId(int prefix) {
        int bumped = idSeqMapper.bump(prefix);
        if (bumped != 1) {
            throw new IllegalStateException("账号号分配器缺少前缀记录 role_prefix=" + prefix);
        }
        Integer curr = idSeqMapper.curr(prefix);
        if (curr == null) {
            throw new IllegalStateException("账号号分配器读取失败 role_prefix=" + prefix);
        }
        return prefix * 10000L + curr;
    }
}
