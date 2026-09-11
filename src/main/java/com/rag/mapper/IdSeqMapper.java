package com.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 账号号分配器（sys_id_seq）：每前缀一行序号，取号用行锁 UPDATE 串行化并发。
 */
@Mapper
public interface IdSeqMapper {

    /** 将指定前缀序号 +1（行锁），返回受影响行数（前缀不存在为 0） */
    @Update("UPDATE sys_id_seq SET curr = curr + 1 WHERE role_prefix = #{prefix}")
    int bump(@Param("prefix") int prefix);

    /** 读当前前缀已发出的最大序号 */
    @Select("SELECT curr FROM sys_id_seq WHERE role_prefix = #{prefix}")
    Integer curr(@Param("prefix") int prefix);
}
