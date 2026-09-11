package com.rag.config;

import com.rag.listener.ChatMessageListener;
import com.rag.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 配置：人工客服 Pub/Sub 消息监听容器（跨实例消息路由）。
 */
@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    private final ChatMessageListener chatMessageListener;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(chatMessageListener, new ChannelTopic(AgentService.CHANNEL));
        return container;
    }
}
