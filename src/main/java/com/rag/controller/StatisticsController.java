package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.entity.Message;
import com.rag.mapper.ConversationMapper;
import com.rag.mapper.KnowledgeChunkMapper;
import com.rag.mapper.MessageMapper;
import com.rag.mapper.SatisfactionMapper;
import com.rag.mapper.StatisticsMapper;
import com.rag.mapper.SysUserMapper;
import com.rag.mapper.TicketMapper;
import com.rag.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据统计（F07）：管理员/客服可查看
 */
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','AGENT')")
public class StatisticsController {

    private final SysUserMapper sysUserMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final TicketMapper ticketMapper;
    private final SatisfactionMapper satisfactionMapper;
    private final StatisticsMapper statisticsMapper;

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        Map<String, Object> data = new HashMap<>();

        // 总量指标
        data.put("userCount", sysUserMapper.selectCount(null));
        data.put("conversationCount", conversationMapper.selectCount(null));
        data.put("messageCount", messageMapper.selectCount(null));
        data.put("chunkCount", knowledgeChunkMapper.selectCount(null));
        data.put("ticketCount", ticketMapper.selectCount(null));
        data.put("satisfactionCount", satisfactionMapper.selectCount(null));

        // 今日消息数 / AI 回复数
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        data.put("todayMessageCount", messageMapper.selectCount(
                new LambdaQueryWrapper<Message>().ge(Message::getCreateTime, todayStart)));
        data.put("aiMessageCount", messageMapper.selectCount(
                new LambdaQueryWrapper<Message>().eq(Message::getSenderType, "AI")));

        // 分布 / 趋势
        data.put("ticketStatus", statisticsMapper.ticketStatusDist());
        data.put("conversationType", statisticsMapper.conversationTypeDist());
        data.put("satisfaction", statisticsMapper.satisfactionStat());
        data.put("chunkVector", statisticsMapper.chunkVectorDist());
        data.put("messageTrend", statisticsMapper.messageTrend(
                LocalDate.now().minusDays(6).atStartOfDay()));
        data.put("hotKnowledge", statisticsMapper.hotKnowledge(10));
        data.put("intentDist", statisticsMapper.intentDist());

        return Result.success(data);
    }
}
