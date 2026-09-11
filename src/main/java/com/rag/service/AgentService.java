package com.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.entity.Conversation;
import com.rag.entity.Message;
import com.rag.entity.SysUser;
import com.rag.exception.BizException;
import com.rag.mapper.ConversationMapper;
import com.rag.mapper.MessageMapper;
import com.rag.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 人工客服服务（F04）。
 *
 * <p>协调状态存于 Redis（在线客服、等待队列、会话分配、客服负载），重启不丢、多实例可见；
 * 跨实例消息通过 Redis Pub/Sub（{@link #CHANNEL}）路由到真正持有目标 WebSocket 连接的实例。
 * 每个实例仅在本机内存中维护「本地 WS 会话」与「本地用户会话」两张表。</p>
 *
 * <p>客服分配策略：优先分配给当前会话数最少的在线客服（最少负载）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** Pub/Sub 通道名 */
    public static final String CHANNEL = "rag:cs:channel";

    private static final String KEY_AGENTS = "rag:cs:agents";
    private static final String KEY_QUEUE = "rag:cs:queue";
    private static final String KEY_ASSIGN = "rag:cs:assign";
    private static final String KEY_LOAD = "rag:cs:load";
    private static final String KEY_UNREAD = "rag:cs:unread";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    /** 本机在线客服：agentId -> WebSocketSession */
    private final Map<Long, WebSocketSession> agentSessions = new ConcurrentHashMap<>();
    /** 本机用户会话：conversationId -> WebSocketSession */
    private final Map<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    // ==================== 注册/注销 ====================

    public void registerAgent(Long agentId, WebSocketSession session) {
        agentSessions.put(agentId, session);
        redisTemplate.opsForSet().add(KEY_AGENTS, String.valueOf(agentId));
        assignWaiting();
    }

    public void unregisterAgent(Long agentId) {
        agentSessions.remove(agentId);
        redisTemplate.opsForSet().remove(KEY_AGENTS, String.valueOf(agentId));

        // 把该客服名下会话重新入队
        Map<Object, Object> assign = redisTemplate.opsForHash().entries(KEY_ASSIGN);
        List<String> toRequeue = new ArrayList<>();
        for (Map.Entry<Object, Object> e : assign.entrySet()) {
            if (String.valueOf(agentId).equals(String.valueOf(e.getValue()))) {
                toRequeue.add(String.valueOf(e.getKey()));
            }
        }
        for (String convId : toRequeue) {
            redisTemplate.opsForHash().delete(KEY_ASSIGN, convId);
            redisTemplate.opsForList().rightPush(KEY_QUEUE, convId);
        }
        redisTemplate.opsForHash().delete(KEY_LOAD, String.valueOf(agentId));
        assignWaiting();
    }

    public void registerUser(Long conversationId, WebSocketSession session) {
        userSessions.put(conversationId, session);
    }

    public void unregisterUser(Long conversationId) {
        userSessions.remove(conversationId);
    }

    // ==================== 转人工 / 排队 / 分配 ====================

    public Map<String, Object> transfer(Long conversationId, Long userId, String summary) {
        Long convId = resolveHumanConversation(conversationId, userId, summary);
        String id = String.valueOf(convId);

        // 已在队列 / 已分配 → 不重复入队，直接返回当前状态
        boolean assigned = Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(KEY_ASSIGN, id));
        boolean queued = redisTemplate.opsForList().indexOf(KEY_QUEUE, id) != null;
        if (!assigned && !queued) {
            redisTemplate.opsForList().rightPush(KEY_QUEUE, id);
            assignWaiting();
            assigned = Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(KEY_ASSIGN, id));
        }

        int position = queuePosition(convId);
        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", convId);
        result.put("queuePosition", position);
        result.put("assigned", assigned);
        return result;
    }

    /**
     * 定位/复用人工会话：
     * 1. 传入会话复用（把原 AI 会话升级为人工，保留上下文）；
     * 2. 否则复用该用户最近一条人工会话（含已结束的，重新激活以保留历史）；
     * 3. 都没有则新建人工会话。
     */
    private Long resolveHumanConversation(Long conversationId, Long userId, String summary) {
        if (conversationId != null) {
            Conversation c = conversationMapper.selectById(conversationId);
            if (c != null && isSameUser(c.getUserId(), userId)) {
                c.setSessionType("HUMAN");
                c.setStatus(1);
                conversationMapper.updateById(c);
                return c.getId();
            }
        }

        List<Conversation> recent = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .eq(Conversation::getSessionType, "HUMAN")
                        .orderByDesc(Conversation::getId)
                        .last("LIMIT 1"));
        if (recent != null && !recent.isEmpty()) {
            Conversation c = recent.get(0);
            c.setStatus(1);
            conversationMapper.updateById(c);
            return c.getId();
        }

        Conversation conversation = new Conversation();
        conversation.setUserId(userId == null ? 0L : userId);
        conversation.setTitle(summary == null ? "人工咨询" : truncate(summary, 50));
        conversation.setSessionType("HUMAN");
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    private boolean isSameUser(Long ownerId, Long userId) {
        if (ownerId == null) {
            return userId == null || userId == 0L;
        }
        return ownerId.equals(userId);
    }

    public int queuePosition(Long conversationId) {
        String convId = String.valueOf(conversationId);
        if (Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(KEY_ASSIGN, convId))) {
            return 0;
        }
        Long idx = redisTemplate.opsForList().indexOf(KEY_QUEUE, convId);
        return idx == null ? 0 : idx.intValue() + 1;
    }

    /** 客服已读会话，清空未读数 */
    public void clearUnread(Long conversationId) {
        redisTemplate.opsForHash().delete(KEY_UNREAD, String.valueOf(conversationId));
    }

    private long unreadCount(Long conversationId) {
        Object v = redisTemplate.opsForHash().get(KEY_UNREAD, String.valueOf(conversationId));
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    // ==================== 工作台查询 ====================

    /** 等待中的会话（排队列表） */
    public List<Map<String, Object>> listQueueConversations() {
        List<String> ids = redisTemplate.opsForList().range(KEY_QUEUE, 0, -1);
        return resolveConversations(ids == null ? List.of() : ids);
    }

    /** 已分配给指定客服的会话 */
    public List<Map<String, Object>> listAssignedConversations(Long agentId) {
        Map<Object, Object> assign = redisTemplate.opsForHash().entries(KEY_ASSIGN);
        List<String> ids = new ArrayList<>();
        for (Map.Entry<Object, Object> e : assign.entrySet()) {
            if (String.valueOf(agentId).equals(String.valueOf(e.getValue()))) {
                ids.add(String.valueOf(e.getKey()));
            }
        }
        return resolveConversations(ids);
    }

    /** 在线客服ID列表 */
    public List<Long> onlineAgents() {
        Set<String> members = redisTemplate.opsForSet().members(KEY_AGENTS);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::parseLong).sorted().collect(Collectors.toList());
    }

    private List<Map<String, Object>> resolveConversations(List<String> ids) {
        List<Conversation> conversations = new ArrayList<>();
        for (String id : ids) {
            try {
                Conversation conversation = conversationMapper.selectById(Long.parseLong(id));
                if (conversation != null) {
                    conversations.add(conversation);
                }
            } catch (NumberFormatException ignored) {
                // 跳过非法 ID
            }
        }
        enrichUserNames(conversations);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Conversation c : conversations) {
            result.add(toConversationMap(c));
        }
        return result;
    }

    /** 已结束会话（本客服名下，用于工作台「已结束」列表） */
    public List<Map<String, Object>> listEndedConversations(Long agentId) {
        List<Conversation> list = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getAgentId, agentId)
                        .eq(Conversation::getStatus, 2)
                        .orderByDesc(Conversation::getUpdateTime)
                        .last("LIMIT 50"));
        enrichUserNames(list);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Conversation c : list) {
            result.add(toConversationMap(c));
        }
        return result;
    }

    /** 会话列表回填归属用户昵称（join sys_user 实时取当前昵称，用户改昵称后工作台随即同步） */
    private void enrichUserNames(List<Conversation> conversations) {
        if (conversations == null || conversations.isEmpty()) {
            return;
        }
        List<Long> userIds = conversations.stream()
                .map(Conversation::getUserId)
                .filter(id -> id != null && id != 0L)
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameById = sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId,
                        u -> StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername()));
        for (Conversation c : conversations) {
            if (c.getUserId() != null && nameById.containsKey(c.getUserId())) {
                c.setUserName(nameById.get(c.getUserId()));
            }
        }
    }

    /** 客服重新接待已结束会话：重新激活并直接分配给该客服 */
    public void reopen(Long conversationId, Long agentId) {
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) {
            throw new BizException("会话不存在");
        }
        c.setStatus(1);
        c.setAgentId(agentId);
        conversationMapper.updateById(c);

        String convId = String.valueOf(conversationId);
        redisTemplate.opsForList().remove(KEY_QUEUE, 0, convId);
        Object prev = redisTemplate.opsForHash().get(KEY_ASSIGN, convId);
        if (prev != null) {
            redisTemplate.opsForHash().delete(KEY_ASSIGN, convId);
            redisTemplate.opsForHash().increment(KEY_LOAD, String.valueOf(prev), -1);
        }
        redisTemplate.opsForHash().put(KEY_ASSIGN, convId, String.valueOf(agentId));
        redisTemplate.opsForHash().increment(KEY_LOAD, String.valueOf(agentId), 1);
        publishTo("agent:" + agentId, assignedEvent(conversationId));
    }

    private Map<String, Object> toConversationMap(Conversation conversation) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", conversation.getId());
        m.put("userId", conversation.getUserId());
        m.put("userName", conversation.getUserName());
        m.put("title", conversation.getTitle());
        m.put("sessionType", conversation.getSessionType());
        m.put("status", conversation.getStatus());
        m.put("agentId", conversation.getAgentId());
        m.put("lastReplyTime", conversation.getLastReplyTime());
        m.put("createTime", conversation.getCreateTime());
        m.put("updateTime", conversation.getUpdateTime());
        m.put("unread", unreadCount(conversation.getId()));
        return m;
    }

    private void assignWaiting() {
        while (true) {
            Long agentId = pickAgent();
            if (agentId == null) {
                break;
            }
            String convId = redisTemplate.opsForList().leftPop(KEY_QUEUE);
            if (convId == null) {
                break;
            }
            redisTemplate.opsForHash().put(KEY_ASSIGN, convId, String.valueOf(agentId));
            redisTemplate.opsForHash().increment(KEY_LOAD, String.valueOf(agentId), 1);
            Long cid = Long.parseLong(convId);
            Conversation assigned = conversationMapper.selectById(cid);
            if (assigned != null) {
                assigned.setAgentId(agentId);
                assigned.setStatus(1);
                conversationMapper.updateById(assigned);
            }
            publishTo("agent:" + agentId, assignedEvent(cid));
            // 通知用户：已接入客服
            publishTo("user:" + convId, assignedEvent(cid));
        }
        pushQueuePositions();
    }

    private Long pickAgent() {
        java.util.Set<String> agents = redisTemplate.opsForSet().members(KEY_AGENTS);
        if (agents == null || agents.isEmpty()) {
            return null;
        }
        Long best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (String agent : agents) {
            Object load = redisTemplate.opsForHash().get(KEY_LOAD, agent);
            int l = load == null ? 0 : Integer.parseInt(load.toString());
            if (l < bestLoad) {
                bestLoad = l;
                best = Long.parseLong(agent);
            }
        }
        return best;
    }

    // ==================== 消息路由 ====================

    public void routeUserMessage(Long conversationId, String content) {
        String text = content == null ? "" : content;
        saveMessage(conversationId, "USER", text);
        redisTemplate.opsForHash().increment(KEY_UNREAD, String.valueOf(conversationId), 1);
        Object agentId = redisTemplate.opsForHash().get(KEY_ASSIGN, String.valueOf(conversationId));
        if (agentId == null) {
            log.warn("会话未分配客服，消息未路由: conversationId={}", conversationId);
            return;
        }
        publishTo("agent:" + agentId, messageEvent(conversationId, "USER", text));
    }

    public void routeAgentMessage(Long agentId, Long conversationId, String content) {
        String text = content == null ? "" : content;
        saveMessage(conversationId, "AGENT", text);
        publishTo("user:" + conversationId, messageEvent(conversationId, "AGENT", text));
    }

    /** 客服/用户手动结束会话（reason=manual，兼容原语义） */
    public void closeConversation(Long conversationId) {
        closeConversation(conversationId, "manual");
    }

    /**
     * 结束会话：置 status=2、从 Redis 排队/分配/负载中摘除、清未读、向双方推送 closed 事件。
     *
     * @param reason 结束原因：manual（客服/用户主动结束）/ timeout（超时无对话自动结束，由定时任务触发）
     */
    public void closeConversation(Long conversationId, String reason) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null) {
            conversation.setStatus(2);
            conversationMapper.updateById(conversation);
        }
        String convId = String.valueOf(conversationId);
        Object agentId = redisTemplate.opsForHash().get(KEY_ASSIGN, convId);
        if (agentId != null) {
            redisTemplate.opsForHash().delete(KEY_ASSIGN, convId);
            redisTemplate.opsForHash().increment(KEY_LOAD, String.valueOf(agentId), -1);
            // 通知客服：会话已结束
            publishTo("agent:" + agentId, closedEvent(conversationId, reason));
        }
        redisTemplate.opsForList().remove(KEY_QUEUE, 0, convId);
        redisTemplate.opsForHash().delete(KEY_UNREAD, convId);
        // 通知用户：会话已结束
        publishTo("user:" + convId, closedEvent(conversationId, reason));
        assignWaiting();
    }

    /** Pub/Sub 订阅回调：把路由消息投递到本机真正持有的 WS 会话 */
    public void onRoutedMessage(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String target = node.path("target").asText("");
            JsonNode payload = node.path("payload");
            if (payload.isMissingNode() || payload.isNull()) {
                return;
            }
            String payloadJson = payload.toString();
            if (target.startsWith("agent:")) {
                long agentId = Long.parseLong(target.substring("agent:".length()));
                WebSocketSession session = agentSessions.get(agentId);
                if (session != null) {
                    send(session, payloadJson);
                }
            } else if (target.startsWith("user:")) {
                long convId = Long.parseLong(target.substring("user:".length()));
                WebSocketSession session = userSessions.get(convId);
                if (session != null) {
                    send(session, payloadJson);
                }
            }
        } catch (Exception e) {
            log.warn("路由消息解析失败: {}", e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    private void saveMessage(Long conversationId, String senderType, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setSenderType(senderType);
        message.setContent(content);
        message.setMessageType("TEXT");
        message.setStatus(1);
        messageMapper.insert(message);

        // 更新会话最后回复时间
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv != null) {
            conv.setLastReplyTime(LocalDateTime.now());
            conversationMapper.updateById(conv);
        }
    }

    private void publishTo(String target, Map<String, Object> payload) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("target", target);
        envelope.put("payload", payload);
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("发布路由消息失败", e);
        }
    }

    private Map<String, Object> messageEvent(Long conversationId, String senderType, String content) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "message");
        event.put("conversationId", conversationId);
        event.put("senderType", senderType);
        event.put("content", content);
        return event;
    }

    private Map<String, Object> assignedEvent(Long conversationId) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "assigned");
        event.put("conversationId", conversationId);
        return event;
    }

    private Map<String, Object> closedEvent(Long conversationId, String reason) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "closed");
        event.put("conversationId", conversationId);
        event.put("reason", reason);
        return event;
    }

    private Map<String, Object> positionEvent(Long conversationId, int position) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "position");
        event.put("conversationId", conversationId);
        event.put("position", position);
        return event;
    }

    /** 把最新排队位置推送给所有仍在排队的用户 */
    private void pushQueuePositions() {
        List<String> ids = redisTemplate.opsForList().range(KEY_QUEUE, 0, -1);
        if (ids == null) {
            return;
        }
        int i = 1;
        for (String id : ids) {
            publishTo("user:" + id, positionEvent(Long.parseLong(id), i++));
        }
    }

    private void send(WebSocketSession session, String json) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.warn("WebSocket 消息发送失败", e);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
