package com.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.entity.UnresolvedQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 未解决问题池 Mapper
 */
@Mapper
public interface UnresolvedQuestionMapper extends BaseMapper<UnresolvedQuestion> {

    /**
     * 命中已有行时累加次数并刷新最近一次上下文。
     * 用 SQL 原子自增而不是「查出来 +1 再写回」，避免并发下丢计数。
     */
    @Update("UPDATE unresolved_question SET hit_count = hit_count + 1, "
            + "source = #{source}, top_score = #{topScore}, "
            + "conversation_id = #{conversationId}, message_id = #{messageId}, "
            + "user_id = #{userId}, update_time = NOW() "
            + "WHERE question_hash = #{questionHash}")
    int bump(@Param("questionHash") String questionHash,
             @Param("source") Integer source,
             @Param("topScore") java.math.BigDecimal topScore,
             @Param("conversationId") Long conversationId,
             @Param("messageId") Long messageId,
             @Param("userId") Long userId);
}
