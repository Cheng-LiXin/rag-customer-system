package com.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据统计（F07）聚合查询
 */
@Mapper
public interface StatisticsMapper {

    /** 工单状态分布 */
    @Select("SELECT status, COUNT(*) AS count FROM ticket GROUP BY status")
    List<Map<String, Object>> ticketStatusDist();

    /** 会话类型分布（AUTO/HUMAN） */
    @Select("SELECT session_type AS type, COUNT(*) AS count FROM conversation GROUP BY session_type")
    List<Map<String, Object>> conversationTypeDist();

    /** 满意度平均分与评价数 */
    @Select("SELECT AVG(rating) AS avg, COUNT(*) AS count FROM satisfaction")
    Map<String, Object> satisfactionStat();

    /** 近 7 天消息趋势 */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, COUNT(*) AS count " +
            "FROM message WHERE create_time >= #{since} " +
            "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d') ORDER BY d")
    List<Map<String, Object>> messageTrend(@Param("since") LocalDateTime since);

    /** 知识片段向量化状态分布 */
    @Select("SELECT vector_status AS status, COUNT(*) AS count FROM knowledge_chunk WHERE deleted = 0 GROUP BY vector_status")
    List<Map<String, Object>> chunkVectorDist();

    /**
     * 热门知识 Top-N（按命中数降序）。
     * 一篇源文导入时会被切成多个知识片段；若按片段(id)一行条统计，同名标题会占多个名额刷屏。
     * 因此按「源文」聚合：优先按 source_url（来源链接，导入时应写入），为空时回退按 title 归并；
     * 每行 = 一篇源文，hitCount=其全部片段命中合计，chunkCount=由几片组成。
     */
    @Select("SELECT COALESCE(NULLIF(source_url, ''), MD5(COALESCE(NULLIF(source_title, ''), title))) AS src_key, " +
            "MIN(id) AS id, MAX(COALESCE(NULLIF(source_title, ''), title)) AS title, " +
            "SUM(hit_count) AS hitCount, COUNT(*) AS chunkCount " +
            "FROM knowledge_chunk WHERE deleted = 0 " +
            "GROUP BY src_key ORDER BY hitCount DESC, MIN(id) ASC LIMIT #{limit}")
    List<Map<String, Object>> hotKnowledge(@Param("limit") int limit);

    /** 意图分布（基于用户消息持久化的意图分类） */
    @Select("SELECT intent_category AS category, COUNT(*) AS count FROM message " +
            "WHERE sender_type = 'USER' AND intent_category IS NOT NULL AND intent_category <> '' " +
            "GROUP BY intent_category ORDER BY count DESC")
    List<Map<String, Object>> intentDist();
}
