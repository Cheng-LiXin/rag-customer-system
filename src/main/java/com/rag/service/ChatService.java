package com.rag.service;

import com.rag.dto.ChatAnswer;
import reactor.core.publisher.Flux;

/**
 * 智能问答服务（F01，RAG 核心）
 */
public interface ChatService {

    /**
     * 普通问答：缓存优先 → 向量检索 → Prompt 构造 → LLM 生成 → 事务持久化 → 异步缓存
     *
     * @param conversationId 会话ID（可为空，为空则新建会话）
     * @param question       用户问题
     */
    ChatAnswer ask(Long conversationId, String question);

    /**
     * SSE 流式问答：逐 Token 推送，结束后返回 end 事件（含引用来源）
     */
    Flux<String> stream(Long conversationId, String question);
}
