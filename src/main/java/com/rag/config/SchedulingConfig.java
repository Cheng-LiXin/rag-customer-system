package com.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring 定时任务（用于自动结束超时无消息的自动会话，见 ConversationAutoCloseTask）
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
