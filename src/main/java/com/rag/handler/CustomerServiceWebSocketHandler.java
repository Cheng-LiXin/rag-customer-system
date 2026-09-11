package com.rag.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 人工客服 WebSocket 处理器（F04）。
 * 消息协议（JSON）：
 *   注册客服：{"type":"register","role":"agent","agentId":1}
 *   注册用户：{"type":"register","role":"user","conversationId":123}
 *   发送消息：{"type":"message","conversationId":123,"content":"..."}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerServiceWebSocketHandler extends TextWebSocketHandler {

    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 已连接: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("非 JSON 消息，忽略: {}", message.getPayload());
            return;
        }

        String type = node.path("type").asText("");
        if ("register".equals(type)) {
            handleRegister(session, node);
        } else if ("message".equals(type)) {
            handleMessage(session, node);
        } else {
            log.warn("未知消息类型: {}", type);
        }
    }

    private void handleRegister(WebSocketSession session, JsonNode node) {
        String role = node.path("role").asText("");
        if ("agent".equals(role)) {
            long agentId = node.path("agentId").asLong(0);
            if (agentId > 0) {
                session.getAttributes().put("role", "agent");
                session.getAttributes().put("agentId", agentId);
                agentService.registerAgent(agentId, session);
                log.info("客服上线: agentId={}", agentId);
            }
        } else if ("user".equals(role)) {
            long conversationId = node.path("conversationId").asLong(0);
            if (conversationId > 0) {
                session.getAttributes().put("role", "user");
                session.getAttributes().put("conversationId", conversationId);
                agentService.registerUser(conversationId, session);
                log.info("用户接入会话: conversationId={}", conversationId);
            }
        }
    }

    private void handleMessage(WebSocketSession session, JsonNode node) {
        Object role = session.getAttributes().get("role");
        long conversationId = node.path("conversationId").asLong(0);
        String content = node.path("content").asText("");
        if ("agent".equals(role)) {
            Object agentIdObj = session.getAttributes().get("agentId");
            long agentId = agentIdObj instanceof Number n ? n.longValue() : 0L;
            agentService.routeAgentMessage(agentId, conversationId, content);
        } else if ("user".equals(role)) {
            agentService.routeUserMessage(conversationId, content);
        } else {
            log.warn("未注册的会话发送消息");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object role = session.getAttributes().get("role");
        if ("agent".equals(role)) {
            Object agentIdObj = session.getAttributes().get("agentId");
            if (agentIdObj instanceof Number n) {
                agentService.unregisterAgent(n.longValue());
            }
        } else if ("user".equals(role)) {
            Object convIdObj = session.getAttributes().get("conversationId");
            if (convIdObj instanceof Number n) {
                agentService.unregisterUser(n.longValue());
            }
        }
        log.info("WebSocket 已断开: sessionId={}", session.getId());
    }
}
