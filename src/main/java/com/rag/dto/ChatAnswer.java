package com.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 智能问答结果（同时作为 Redis 缓存值序列化对象）
 *
 * <p>注意：作为缓存值时 timings 不写入（见 ChatServiceImpl 的写入顺序），
 * 避免热路径命中缓存后返回一份早已过期的耗时数据。
 */
@Data
public class ChatAnswer {

    /** 生成的回答 */
    private String answer;

    /** 是否来自缓存 */
    private boolean fromCache;

    /** 会话ID */
    private Long conversationId;

    /**
     * 本次机器人回答在 message 表中的 id。
     * 前端「有帮助 / 没帮助」按钮要靠它提交反馈 —— 没有这个 id 就没法对具体某条回答评价。
     */
    private Long messageId;

    /** 引用来源 */
    private List<Source> sources;

    /** 识别到的意图分类（F03） */
    private String intentCategory;

    /** 生成时间 */
    private LocalDateTime createdAt;

    /** 是否为「拒答」：最高相似度低于阈值，或命中注入防护 —— 前端据此展示「转人工客服」入口 */
    private boolean rejected;

    /** 拒答/拦截原因：low-score | injection-input | injection-context | injection-output */
    private String rejectReason;

    /** 低置信：相似度偏低但未低到拒答 —— 答案照给，前端提示「仅供参考，可转人工核实」 */
    private boolean lowConfidence;

    /** 本次检索最高相似度（融合前向量池 top-1 余弦），供阈值标定与排查 */
    private Double maxScore;

    /** 本次使用的检索模式：vector | bm25 | rrf | rerank */
    private String retrievalMode;

    /** 分阶段耗时（毫秒）：retrieveMs / generateMs / totalMs / ttftMs。仅实时路径写入 */
    private Map<String, Long> timings;
}
