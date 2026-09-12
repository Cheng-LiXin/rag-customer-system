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
     * 普通问答（可旁路缓存）。
     *
     * @param bypassCache true 时跳过 qa:cache 的读与写，强制走完整检索+生成链路。
     *                    供离线评估使用（评估必须每次都真实检索，否则重复问题会命中缓存导致指标失真）。
     *                    生产流量一律走 2 参重载（bypassCache=false，行为与旧版一致）。
     */
    ChatAnswer ask(Long conversationId, String question, boolean bypassCache);

    /**
     * SSE 流式问答：逐 Token 推送，结束后返回 end 事件（含引用来源）
     */
    Flux<String> stream(Long conversationId, String question);

    /**
     * SSE 流式问答（可旁路缓存），语义同 {@link #ask(Long, String, boolean)}。
     */
    Flux<String> stream(Long conversationId, String question, boolean bypassCache);
}
