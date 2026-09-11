package com.rag.task;

import com.rag.mapper.ConversationMapper;
import com.rag.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话超时自动结束：
 * <ul>
 *   <li>AUTO（机器人）会话：连续 N 分钟无消息直接置 status=2（{@link #timeoutMinutes}，默认 30）；</li>
 *   <li>HUMAN（人工）会话：已分配客服且双方连续 N 分钟无对话，复用 {@link AgentService#closeConversation}
 *       走完整收尾（Redis 排队/分配摘除、WS 通知双方），原因记为 timeout。</li>
 * </ul>
 * 阈值/间隔见 application.yml 的 rag.session.* 与 rag.human.timeout-minutes。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationAutoCloseTask {

    private final ConversationMapper conversationMapper;
    private final AgentService agentService;

    /** 机器人会话：多少分钟无消息判定为已结束（默认 30） */
    @Value("${rag.session.timeout-minutes:30}")
    private int timeoutMinutes;

    /** 人工会话：已分配客服且双方多少分钟无对话自动结束（默认 10） */
    @Value("${rag.human.timeout-minutes:10}")
    private int humanTimeoutMinutes;

    /** 定时扫描：默认每 60 秒执行一次 */
    @Scheduled(fixedDelayString = "${rag.session.close-interval-ms:60000}")
    public void closeStaleAutoConversations() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(timeoutMinutes);
        int closed = conversationMapper.closeStaleAuto(since);
        if (closed > 0) {
            log.info("自动结束超时自动会话 {} 条（阈值 {} 分钟）", closed, timeoutMinutes);
        }
    }

    /** 已分配客服的人工会话：双方连续超时无对话 → 自动结束（完整收尾 + 通知双方） */
    @Scheduled(fixedDelayString = "${rag.session.close-interval-ms:60000}")
    public void closeStaleHumanConversations() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(humanTimeoutMinutes);
        List<Long> staleIds = conversationMapper.selectStaleHuman(since);
        if (staleIds.isEmpty()) {
            return;
        }
        int closed = 0;
        for (Long conversationId : staleIds) {
            try {
                agentService.closeConversation(conversationId, "timeout");
                closed++;
            } catch (Exception e) {
                log.warn("自动结束超时人工会话失败 conversationId={}: {}", conversationId, e.getMessage());
            }
        }
        log.info("自动结束超时人工会话 {} 条（阈值 {} 分钟）", closed, humanTimeoutMinutes);
    }
}
