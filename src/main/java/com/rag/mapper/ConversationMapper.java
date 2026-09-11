package com.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 把「超时无消息」的自动会话置为已结束（status=2）。
     * 活跃判据用 message 表的最后一条消息时间（MAX(create_time)），无需改 conversation 表。
     * 只处理 AUTO 会话；HUMAN 会话的超时结束走 {@link #selectStaleHuman}。
     */
    @Update("UPDATE conversation c SET c.status = 2 " +
            "WHERE c.status = 1 AND c.session_type = 'AUTO' " +
            "AND (SELECT MAX(m.create_time) FROM message m WHERE m.conversation_id = c.id) < #{since}")
    int closeStaleAuto(@Param("since") LocalDateTime since);

    /**
     * 找出「已分配客服（agent_id 非空）且连续无消息」的超时人工会话 ID。
     * 活跃判据：双方最后一条消息时间（无任何消息则退化为会话创建时间）。
     * 排队未接（agent_id 为空）的会话不在其中，避免误杀仍在等待的用户。
     */
    @Select("SELECT c.id FROM conversation c " +
            "WHERE c.status = 1 AND c.session_type = 'HUMAN' AND c.agent_id IS NOT NULL " +
            "AND COALESCE((SELECT MAX(m.create_time) FROM message m WHERE m.conversation_id = c.id), c.create_time) < #{since}")
    List<Long> selectStaleHuman(@Param("since") LocalDateTime since);
}
