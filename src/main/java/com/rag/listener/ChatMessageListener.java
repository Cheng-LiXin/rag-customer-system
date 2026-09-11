package com.rag.listener;

import com.rag.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 人工客服 Pub/Sub 订阅监听：收到路由消息后交给 AgentService 投递到本机 WS 会话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageListener implements MessageListener {

    private final AgentService agentService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        agentService.onRoutedMessage(body);
    }
}
