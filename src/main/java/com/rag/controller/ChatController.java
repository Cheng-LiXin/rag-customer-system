package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.dto.ChatAnswer;
import com.rag.dto.ChatRequest;
import com.rag.dto.PageResult;
import com.rag.entity.Conversation;
import com.rag.entity.Message;
import com.rag.entity.SysUser;
import com.rag.mapper.ConversationMapper;
import com.rag.mapper.MessageMapper;
import com.rag.retrieve.RetrievalModeContext;
import com.rag.service.ChatService;
import com.rag.service.UserService;
import com.rag.util.Result;
import com.rag.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 问答控制器：普通问答（RAG）+ SSE 流式问答（SRS 4.1.4）+ 历史会话
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final UserService userService;

    /**
     * 离线评估用的缓存旁路开关。默认 false：即使请求带了 noCache=true 也不会旁路，
     * 保证生产环境行为与旧版完全一致（评估环境才在配置里打开）。
     */
    @Value("${rag.eval.bypass-cache-enabled:false}")
    private boolean evalBypassEnabled;

    /**
     * 单次请求的检索模式覆盖开关（默认 false）。开启后可用 {@code X-Retrieval-Mode} 头
     * 为这一次请求指定 vector/bm25/rrf/rerank，用于四组检索对比实验
     * （一次跑完四组，不必反复切全局模式）。生产环境保持 false。
     */
    @Value("${rag.eval.mode-override-enabled:false}")
    private boolean modeOverrideEnabled;

    /** 量化评估旁路：仅当配置开关打开且请求显式要求时才旁路 qa:cache */
    private boolean bypass(Boolean noCache) {
        return evalBypassEnabled && Boolean.TRUE.equals(noCache);
    }

    /** 设置本次请求的检索模式覆盖（ThreadLocal）。调用方必须在 finally 里 clear。 */
    private void applyModeOverride(String retrievalMode) {
        if (modeOverrideEnabled && StringUtils.hasText(retrievalMode)) {
            RetrievalModeContext.set(retrievalMode);
        }
    }

    /** 普通问答（SRS 接口：POST /api/chat/ask） */
    @PostMapping("/ask")
    public Result<ChatAnswer> ask(@RequestBody @Valid ChatRequest request,
                                  @RequestParam(required = false) Boolean noCache,
                                  @RequestHeader(value = "X-Retrieval-Mode", required = false) String retrievalMode) {
        applyModeOverride(retrievalMode);
        try {
            return Result.success(chatService.ask(request.getConversationId(), request.getMessage(), bypass(noCache)));
        } finally {
            RetrievalModeContext.clear();
        }
    }

    /** 兼容旧接口别名 */
    @PostMapping("/send")
    public Result<ChatAnswer> send(@RequestBody @Valid ChatRequest request,
                                   @RequestParam(required = false) Boolean noCache,
                                   @RequestHeader(value = "X-Retrieval-Mode", required = false) String retrievalMode) {
        return ask(request, noCache, retrievalMode);
    }

    /** SSE 流式问答（浏览器 EventSource 可直接消费） */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message,
                               @RequestParam(required = false) Long conversationId,
                               @RequestParam(required = false) Boolean noCache,
                               @RequestHeader(value = "X-Retrieval-Mode", required = false) String retrievalMode) {
        // 注意：答案在 stream() 内部**同步**算完（伪流式），所以 ThreadLocal 覆盖
        // 只需覆盖这个调用本身；后续 Flux 的持久化在 Reactor 线程上跑，不再需要它。
        applyModeOverride(retrievalMode);
        try {
            return chatService.stream(conversationId, message, bypass(noCache));
        } finally {
            RetrievalModeContext.clear();
        }
    }

    /** 已登录用户的历史会话列表 */
    @GetMapping("/history")
    public Result<PageResult<Conversation>> history(@RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "20") long pageSize) {
        String username = SecurityUtil.currentUsername();
        if (username == null) {
            return Result.success(PageResult.of(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>()));
        }
        SysUser user = userService.getByUsername(username);
        if (user == null) {
            return Result.success(PageResult.of(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>()));
        }
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, user.getId())
                .orderByDesc(Conversation::getId);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Conversation> page =
                conversationMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize), wrapper);
        return Result.success(PageResult.of(page));
    }

    /** 查看指定会话的消息列表（需登录且为会话主人） */
    @GetMapping("/history/{conversationId}/messages")
    public Result<List<Message>> historyMessages(@PathVariable Long conversationId) {
        String username = SecurityUtil.currentUsername();
        if (username == null) {
            return Result.error("请先登录");
        }
        SysUser user = userService.getByUsername(username);
        if (user == null) {
            return Result.error("用户不存在");
        }
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(user.getId())) {
            return Result.error("会话不存在或无权访问");
        }
        List<Message> messages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getId));
        return Result.success(messages);
    }
}
