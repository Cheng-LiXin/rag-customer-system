package com.rag.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.dto.ChatAnswer;
import com.rag.dto.Source;
import com.rag.entity.Conversation;
import com.rag.entity.KnowledgeChunk;
import com.rag.entity.Message;
import com.rag.entity.SysUser;
import com.rag.guard.GuardDecision;
import com.rag.guard.InjectionGuard;
import com.rag.guard.PromptSanitizer;
import com.rag.mapper.ConversationMapper;
import com.rag.mapper.KnowledgeChunkMapper;
import com.rag.mapper.MessageMapper;
import com.rag.metrics.QaMetrics;
import com.rag.retrieve.RetrievalResult;
import com.rag.retrieve.RetrieverFactory;
import com.rag.service.CacheService;
import com.rag.service.ChatService;
import com.rag.service.IntentService;
import com.rag.service.UnresolvedQuestionService;
import com.rag.service.UserService;
import com.rag.util.SecurityUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 问答服务（RAG 流程，SRS 4.1）：
 * 1. Redis 缓存优先（qa:cache:{MD5}）
 * 2. Embedding 向量化 + VectorStore 检索 Top-K
 * 3. 构造带知识片段的 Prompt
 * 4. 调用 DeepSeek 生成回答（支持 SSE 流式）
 * 5. 编程式事务保存会话与消息记录
 * 6. 异步写回 Redis 缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final RetrieverFactory retrieverFactory;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final CacheService cacheService;
    private final IntentService intentService;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final InjectionGuard injectionGuard;
    private final PromptSanitizer promptSanitizer;
    private final UnresolvedQuestionService unresolvedQuestionService;
    private final QaMetrics qaMetrics;

    /** 向量检索 Top-K */
    @Value("${rag.top-k:3}")
    private int topK;

    /**
     * 抽取式「宽松兜底」的最低相似度门槛：只有最相似片段足够相关时才允许走
     * “挑最相关句作答”，否则仍回固定兜底，避免把完全无关的问题答非所问。
     */
    @Value("${rag.partial-min-score:0.55}")
    private double partialMinScore;

    /** 结构化 markdown 抽取开关：false 时 answer() 完全复刻旧版扁平抽取（便于线上回滚） */
    @Value("${rag.structured.enabled:true}")
    private boolean structuredEnabled;
    /** 结构化抽取：答案分组（要点）上限 */
    @Value("${rag.structured.max-groups:4}")
    private int structuredMaxGroups;
    /** 结构化抽取：每组最多引用的原文句/行数 */
    @Value("${rag.structured.max-per-group:4}")
    private int structuredMaxPerGroup;

    /**
     * 行内引用标记开关：为 true 时在答案正文的每个原文句尾追加 [n]（n 指向 sources[n-1]，
     * 点击可展开原始条目）。标记在 Java 拼装阶段追加，句元原文零改动 —— 关闭后答案与旧版逐字节一致，
     * 既作线上回滚开关，也用于「逐字保真」断言比对。
     */
    @Value("${rag.citation.inline:true}")
    private boolean inlineCitations;

    /**
     * 拒答阈值：检索最高相似度低于此值时直接拒答并引导转人工，且**不调用大模型**。
     * 0 表示关闭拒答（回滚开关）。
     *
     * <p>默认值不是文档建议的 0.65，而是由 150 题实测的离线阈值扫描定稿
     * （tools/eval/threshold_sweep.py）：两组分数**有重叠**，不存在干净切点。
     * 实测 0.55 时超纲拒答率仅 73.3% 不达标；0.59 时 96.7% 达标且误拒仅 3.3%；
     * 0.65 虽也达标但误拒升到 12.5%。故取 0.59，代价见 docs/评估报告.md。
     */
    @Value("${rag.answer.reject-threshold:0.59}")
    private double rejectThreshold;

    /** 低置信阈值：相似度落在 [reject-threshold, 此值) 的答案照常给出，但标记低置信供前端提示 */
    @Value("${rag.answer.low-confidence-threshold:0.65}")
    private double lowConfidenceThreshold;

    /** 拒答文案（配置留空则用常量默认值） */
    @Value("${rag.answer.reject-message:}")
    private String rejectMessageCfg;

    /**
     * 缓存命中也落库（默认开）。
     *
     * <p>为什么需要：缓存命中路径原本提前 return、不写 message 表，于是热门的重复问题
     * **在库里根本没有一条机器人消息** —— 用户就没法对这条回答点赞/点踩，数据飞轮的
     * 「点踩」入口对最热的问题反而失效。
     *
     * <p>代价是热路径多一次写库。可关（回到旧行为）。
     */
    @Value("${rag.cache.persist-on-hit:true}")
    private boolean persistOnHit;

    /** 抽取式回答：参与编号选择的句子上限（防止列表过长导致选择失准） */
    private static final int MAX_SELECT_UNITS = 80;
    /** 抽取式回答：挑选句子编号时的数字提取正则 */
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");
    /** 抽取式回答：无相关内容/未选中任何句子时的固定兜底文案（不让模型自由造句，避免语序错乱） */
    private static final String NO_CONTENT_MSG = "资料中没有相关内容，建议换个问法或补充关键词。";
    /** 拒答文案默认值：相似度低于阈值时给出，与「兜底文案」的区别是它明确引导转人工 */
    private static final String DEFAULT_REJECT_MSG = "未找到相关信息，建议换个问法，或点击「转人工客服」由人工协助。";
    /** 注入防护拦截文案：与「相似度过低」的拒答分开 —— 被安全策略拦截不该看起来像「资料里没有」 */
    private static final String GUARD_BLOCKED_MSG =
            "该请求已被安全策略拦截。如有正常咨询需求，请换个问法，或点击「转人工客服」由人工协助。";
    /** 行内按句末标点切分的正向回顾（保留标点），同旧实现 */
    private static final Pattern SENT_SPLIT = Pattern.compile("(?<=[。！？!?])");
    /** 竖线表格行判定：行内至少一个 | 且 | 两侧有非空内容（兼容「a | b」与「| a | b |」） */
    private static final Pattern TABLE_LINE = Pattern.compile("^\\s*\\|?\\s*[^|\\n]+(?:\\s*\\|\\s*[^|\\n]+)+\\s*\\|?\\s*$");
    /** 行首纯编号标记（如 1. / 2、/ 一．）——渲染去重或识别标题行 */
    private static final Pattern LEAD_NUM = Pattern.compile("^\\s*(?:\\d+|[一二三四五六七八九十]+)\\s*[.、．)）]\\s*(?=\\S)");
    /** “提到表格”的触发词：该句后紧跟的表格块需要一并并入答案 */
    private static final Pattern TABLE_REF = Pattern.compile("见下表|见下|如下表|参见下表|下表|如下|如表|见表");
    /** markdown 表格分隔行单元格（如 --- / :--:） */
    private static final Pattern SEP_CELL = Pattern.compile(":?-{1,}:?");

    // ==================== 快速上手 FAQ（问候/自我介绍/能力/用法） ====================
    // 这些极简“上手问法”若走 RAG，通用词容易把同义的校园生活类原文（浴池圈存、班车预约等）也选进来，
    // 造成与助手无关的内容串答。命中这里直接给固定答案——仍正常持久化与走缓存，只是不检索、不调用模型。
    private static final String FAQ_GREET =
            "你好！欢迎使用燕山大学智能客服助手。\n\n"
            + "关于招生政策、专业设置、教务服务、学工服务与校园生活等问题，你都可以直接问我。\n"
            + "需要真人客服时，点击输入框上方的「转人工客服」即可。";
    private static final String FAQ_WHO =
            "我是燕山大学智能客服助手，一个基于校园公开资料构建的智能问答机器人。\n\n"
            + "我的知识来自燕山大学官网等公开渠道发布的通知、管理规定与办事指南，回答时都会注明出处。\n"
            + "若你遇到更复杂的问题，也可以随时转接人工客服处理。";
    private static final String FAQ_CAN =
            "我可以帮你解答燕山大学的相关问题，主要包括：\n\n"
            + "- **招生政策**：招生简章、报考录取、分数线等\n"
            + "- **专业设置**：学院学科、特色专业等\n"
            + "- **教务服务**：选课、成绩、转专业、学籍等\n"
            + "- **学工服务**：新生报到、宿舍、奖助学金、校园卡等\n"
            + "- **校园生活**：食堂、快递、校园网、作息等\n\n"
            + "我会依据校园公开资料给出准确回答，并附上参考文件；需要人工服务时点击「转人工客服」即可。";
    private static final String FAQ_HOW =
            "使用方法很简单：在页面底部的输入框输入你的问题，点击发送即可获得答案。\n\n"
            + "也可以直接点按首页的「常见问题」卡片快速体验，或在历史对话中回顾之前的提问与回答。";
    /** 去掉空白、标点与 emoji/符号，只留文字核心，用于判定是否为“上手问法” */
    private static final Pattern FAQ_PUNCT = Pattern.compile("[\\p{P}\\p{S}\\s]");
    /** FAQ 中“用法类”命中须为整句（白名单），避免“怎么使用校园卡”这类带具体对象的问题被误抢 */
    private static final Set<String> FAQ_HOW_PHRASES = Set.of(
            "怎么用", "怎么使用", "如何使用", "怎么操作", "怎么提问", "怎么问",
            "怎么用啊", "怎么用呀", "怎么使用啊", "怎么使用呀", "如何使用啊",
            "怎么开始", "怎么开始使用", "快速开始", "使用说明", "使用教程",
            "怎么用呢", "怎么使用呢", "如何使用呢");

    /** 句元：结构化抽取/分组的最小单位 */
    private static class UnitItem {
        final int docIndex;      // 来自 documents 的第几个片段（0 基，用于“同片段找后续表格”）
        final String text;       // trim 后的原文（未加编号、未改写）
        final boolean tableLine; // 是否为竖线表格行

        UnitItem(int docIndex, String text, boolean tableLine) {
            this.docIndex = docIndex;
            this.text = text;
            this.tableLine = tableLine;
        }
    }

    /** 模型分组结果：一个「要点」= ≤12字小标题 + 0 基升序去重的原文行下标 */
    private static class BulletGroup {
        final String title;
        final List<Integer> indexes;

        BulletGroup(String title, List<Integer> indexes) {
            this.title = title;
            this.indexes = indexes;
        }
    }

    /** 一段完整的表格块：整块原文行 + 其所属片段下标（用于行内引用编号） */
    private static class TableBlock {
        final List<String> rows;
        final int docIndex;

        TableBlock(List<String> rows, int docIndex) {
            this.rows = rows;
            this.docIndex = docIndex;
        }
    }

    /**
     * 一次问答的决策上下文：拒答/低置信/最高相似度。
     * 由 ask()/stream() 在检索后写入，再交给 buildAnswer() 回填到响应里；
     * 用对象而非多个散变量，是为了在 stream() 的 lambda 里能以单个 effectively-final 引用捕获。
     */
    private static class AnswerContext {
        boolean rejected;
        String rejectReason;
        boolean lowConfidence;
        Double maxScore;
        String retrievalMode;
    }

    /**
     * 一次问答的生成结果。ask 与 stream 共用 {@link #generate(String)} 的输出，
     * 避免「三层防护 + 拒答 + 降级」这套分支在两条路径上各写一遍而走样。
     */
    private static class Generation {
        List<Document> documents = Collections.emptyList();
        String answer = "";
        final AnswerContext ctx = new AnswerContext();
        long retrieveMs;
    }

    /**
     * persistExchange 的产物：会话ID + 机器人消息ID。
     * 后者是批次 E 消息级反馈的前提 —— 没有消息 id 就没法点赞/点踩。
     */
    private static class Exchange {
        final Long conversationId;
        final Long aiMessageId;

        Exchange(Long conversationId, Long aiMessageId) {
            this.conversationId = conversationId;
            this.aiMessageId = aiMessageId;
        }
    }

    /** 拒答判定结果：是否拒答、是否低置信、判定所依据的最高相似度 */
    private static class RejectDecision {
        final boolean rejected;
        final boolean lowConfidence;
        final Double score;

        RejectDecision(boolean rejected, boolean lowConfidence, Double score) {
            this.rejected = rejected;
            this.lowConfidence = lowConfidence;
            this.score = score;
        }
    }

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ChatAnswer ask(Long conversationId, String question) {
        return ask(conversationId, question, false);
    }

    @Override
    public ChatAnswer ask(Long conversationId, String question, boolean bypassCache) {
        long t0 = System.currentTimeMillis();

        // 1. 缓存优先（离线评估旁路：强制每次真实检索，否则重复问题命中缓存会让指标失真）
        if (!bypassCache) {
            Optional<ChatAnswer> cached = cacheService.get(question);
            if (cached.isPresent()) {
                ChatAnswer answer = cached.get();
                if (persistOnHit) {
                    // 缓存命中也落库：否则热问题的机器人消息在库里根本不存在，
                    // 用户没法点赞/点踩（批次 E 数据飞轮的「点踩」入口对最热的问题反而失效）
                    Long hitUserId = resolveUserId();
                    Exchange hit = persistExchange(conversationId, hitUserId, question,
                            answer.getAnswer(), citationsJsonFromSources(answer.getSources()), true);
                    answer.setConversationId(hit.conversationId);
                    // 必须用**本次**新建的消息 id：缓存里那份 messageId 是上一次的，
                    // 直接用它会让用户给别人的旧回答点赞
                    answer.setMessageId(hit.aiMessageId);
                } else {
                    answer.setConversationId(conversationId);
                }
                qaMetrics.recordCacheHit();
                return answer;
            }
        }

        // 2. L1 输入层防护：命中则不检索、不调模型、不入缓存
        Generation g = generate(question);
        long totalMs = System.currentTimeMillis() - t0;

        // 3. 事务保存会话与消息（先解析 userId 再透传，保证会话归属当前用户而非游客 0）
        Long userId = resolveUserId();
        Exchange exchange = persistExchange(conversationId, userId, question, g.answer, g.documents, false);
        // 3.1 「没答上」的问题沉淀进未解决问题池（数据飞轮入口）
        recordUnresolved(question, exchange, g, userId);

        // 4. 异步写缓存（兜底、拒答与防护拦截都不入缓存，避免旧否定被误命中 1 小时）
        ChatAnswer result = buildAnswer(g.answer, false, exchange.conversationId, g.documents, question, g.ctx);
        result.setMessageId(exchange.aiMessageId);
        if (!bypassCache && !skipCache(g.answer, g.ctx)) {
            cacheService.put(question, result);
        }
        // 5. 耗时埋点放最后：必须在写缓存之后，否则这份「当时」的耗时会被一起写进缓存
        result.setTimings(timings(g.retrieveMs, totalMs - g.retrieveMs, totalMs));
        qaMetrics.recordAnswer(outcomeTag(g), totalMs);
        return result;
    }

    /** 把一次问答归到「结局」标签上（供指标分布与红线监控用） */
    private String outcomeTag(Generation g) {
        if (g.ctx.rejected) {
            String reason = g.ctx.rejectReason == null ? "" : g.ctx.rejectReason;
            return reason.startsWith("injection-") ? QaMetrics.BLOCKED : QaMetrics.REFUSED;
        }
        return NO_CONTENT_MSG.equals(g.answer) ? QaMetrics.FALLBACK : QaMetrics.ANSWERED;
    }

    @Override
    public Flux<String> stream(Long conversationId, String question) {
        return stream(conversationId, question, false);
    }

    @Override
    public Flux<String> stream(Long conversationId, String question, boolean bypassCache) {
        long t0 = System.currentTimeMillis();

        // 1. 缓存命中：直接返回一个 end 事件
        if (!bypassCache) {
            Optional<ChatAnswer> cached = cacheService.get(question);
            if (cached.isPresent()) {
                ChatAnswer answer = cached.get();
                if (persistOnHit) {
                    // 同 ask()：热路径也落库，否则这条回答没有消息 id，无法被评价
                    Long hitUserId = resolveUserId();
                    Exchange hit = persistExchange(conversationId, hitUserId, question,
                            answer.getAnswer(), citationsJsonFromSources(answer.getSources()), true);
                    answer.setConversationId(hit.conversationId);
                    answer.setMessageId(hit.aiMessageId);
                } else {
                    answer.setConversationId(conversationId);
                }
                qaMetrics.recordCacheHit();
                return Flux.just(endEvent(answer));
            }
        }

        // 2. 检索 → 三层防护 → 拒答判定 → 抽取式生成（模型只选句子编号，答案文本由原文逐字拼出）
        final Generation g;
        try {
            g = generate(question);
        } catch (Exception e) {
            log.error("流式问答失败", e);
            return Flux.just(errorEvent(e.getMessage() == null ? "AI服务暂时不可用" : e.getMessage()));
        }

        // 2.1 本问答是「伪流式」：答案在 Flux 创建之前就已被完整算出（抽取式回答的性质决定），
        //     因此 TTFT（首片 token 到达时间）≈ 上述检索+生成的全程耗时，报告口径中如实标注。
        final long totalMs = System.currentTimeMillis() - t0;
        qaMetrics.recordAnswer(outcomeTag(g), totalMs);

        // 2.2 关键：SSE 返回后响应体在 Reactor 线程上异步订阅执行，SecurityContext(ThreadLocal) 已不可达。
        // 必须先在此（servlet 请求线程）解析出当前用户，随闭包透传给异步持久化，否则会话会被绑定成游客 userId=0。
        final Long userId = resolveUserId();

        // 3. 把答案分片当作 token 推送，保持 SSE 流式形态；end 事件携带完整答案
        return Flux.fromIterable(slice(g.answer, 18))
                .map(this::tokenEvent)
                // 4. 结束后：事务持久化 + 异步缓存 + end 事件
                .concatWith(Mono.fromCallable(() -> {
                    Exchange exchange = persistExchange(conversationId, userId, question, g.answer, g.documents, false);
                    recordUnresolved(question, exchange, g, userId);
                    ChatAnswer result = buildAnswer(g.answer, false, exchange.conversationId, g.documents, question, g.ctx);
                    result.setMessageId(exchange.aiMessageId);
                    if (!bypassCache && !skipCache(g.answer, g.ctx)) {
                        cacheService.put(question, result);
                    }
                    // 同 ask()：耗时埋点在写缓存之后，避免过期耗时被写进缓存
                    result.setTimings(timings(g.retrieveMs, totalMs - g.retrieveMs, totalMs));
                    return endEvent(result);
                }))
                .onErrorResume(e -> {
                    log.error("流式问答失败", e);
                    return Flux.just(errorEvent(e.getMessage() == null ? "AI服务暂时不可用" : e.getMessage()));
                });
    }

    // ==================== 内部方法 ====================

    private RetrievalResult retrieve(String question) {
        RetrievalResult result = retrieverFactory.current().retrieve(question, topK);
        incrementHitCount(result.getDocuments());
        return result;
    }

    /**
     * 问答主链路（ask 与 stream 共用）：
     * L1 输入层防护 → FAQ 短接 → 检索 → L2 上下文层防护 → 拒答判定 → 抽取式生成 → L3 输出层防护。
     *
     * <p>三层防护的位置不是随意安排的：
     * <ul>
     *   <li>L1 在检索之前 —— 命中即省掉无谓的检索与模型调用</li>
     *   <li>L2 在检索之后、调模型之前 —— 拦住「指挥助手做事」的毒片段</li>
     *   <li>L3 在生成之后 —— 抽取式回答逐字引用原文，被污染的<b>事实式</b>承诺只能在这里兜住；
     *       且它与检索路径解耦，不论哪条检索路线把毒片段带进来都成立</li>
     * </ul>
     */
    private Generation generate(String question) {
        Generation g = new Generation();

        // L1 输入层：用户问题本身在试图改写系统指令或套取提示词
        GuardDecision inputGuard = injectionGuard.checkInput(question);
        if (inputGuard.isBlocked()) {
            g.answer = GUARD_BLOCKED_MSG;
            g.ctx.rejected = true;
            g.ctx.rejectReason = "injection-input";
            return g;
        }

        // FAQ 短接：极简短问法（你好/你是谁/你能做什么/怎么使用等）直接给固定答案，
        // 不检索不调模型，避免“怎么使用”这类通用词把校园生活类原文（圈存/班车等）串答进来。
        String faq = faqAnswer(question);
        if (faq != null) {
            g.answer = faq;
            return g;
        }

        long r0 = System.currentTimeMillis();
        RetrievalResult retrieval = retrieve(question);
        g.retrieveMs = System.currentTimeMillis() - r0;
        List<Document> hits = retrieval.getDocuments();
        g.ctx.retrievalMode = retrieval.getMode();

        // L2 上下文层：检索到的片段里被埋了「命令助手」的句子（间接注入）
        GuardDecision ctxGuard = injectionGuard.scanContext(hits, question);
        if (ctxGuard.isBlocked()) {
            g.answer = GUARD_BLOCKED_MSG;
            g.ctx.rejected = true;
            g.ctx.rejectReason = "injection-context";
            return g;
        }

        // 拒答判定：相似度过低则直接拒答，不调大模型（省时延，也从根上杜绝幻觉）。
        // 阈值一律读「融合前向量池 top-1 相似度」，与 BM25/RRF/rerank 的分数无关，
        // 保证四种检索模式下阈值语义一致（见 RetrievalResult 的类注释）。
        RejectDecision rd = rejectPolicy(hits, retrieval.getMaxVectorSimilarity());
        g.ctx.maxScore = rd.score;
        if (rd.rejected) {
            g.answer = rejectMessage();
            g.ctx.rejected = true;
            g.ctx.rejectReason = "low-score";
            return g;
        }

        g.ctx.lowConfidence = rd.lowConfidence;
        g.documents = hits;
        String generated = answer(hits, question);

        // L3 输出层：被污染的承诺句若漏到答案里，在此拦下并清空引用来源
        GuardDecision outGuard = injectionGuard.checkOutput(generated, question);
        if (outGuard.isBlocked()) {
            g.answer = GUARD_BLOCKED_MSG;
            g.ctx.rejected = true;
            g.ctx.rejectReason = "injection-output";
            g.documents = Collections.emptyList();
            return g;
        }
        g.answer = generated;
        return g;
    }

    /**
     * 快速上手 FAQ 短接：命中返回固定答案文本，否则返回 null（继续走正常 RAG）。
     * 判定规则：先把问句剥成“核心文字”（去空白/标点/符号），仅当核心文字很短（≤12 字）
     * 才可能命中，避免把“怎么使用校园卡充值”等真实的长问题抢走。
     */
    private String faqAnswer(String raw) {
        if (raw == null) {
            return null;
        }
        String core = FAQ_PUNCT.matcher(raw.toLowerCase()).replaceAll("").trim();
        if (core.isEmpty() || core.length() > 12) {
            return null;
        }

        // 打招呼
        if (core.startsWith("你好") || core.startsWith("您好") || core.startsWith("哈喽")
                || core.startsWith("hello") || core.startsWith("hi") || core.startsWith("嗨")
                || core.startsWith("早上好") || core.startsWith("下午好") || core.startsWith("晚上好")
                || core.equals("在吗")) {
            return FAQ_GREET;
        }
        // 自我介绍
        if (core.contains("你是谁") || core.contains("你叫什么") || core.contains("你是哪位")
                || core.contains("介绍一下你") || core.contains("介绍你自己") || core.contains("自我介绍一下")
                || core.contains("介绍下你") || core.contains("你是机器人") || core.contains("你的身份")) {
            return FAQ_WHO;
        }
        // 能力范围
        if (core.contains("你能做什么") || core.contains("你可以做什么") || core.contains("你会做什么")
                || core.contains("你有什么功能") || core.contains("你能回答什么") || core.contains("能帮我做什么")
                || core.contains("功能有哪些") || core.contains("能回答哪些") || core.contains("什么都能问")) {
            return FAQ_CAN;
        }
        // 用法（整句白名单，避免误抢具体对象类问题）
        if (FAQ_HOW_PHRASES.contains(core)) {
            return FAQ_HOW;
        }
        return null;
    }

    /** 被检索命中的知识片段 hit_count 累加（用于热门知识统计） */
    private void incrementHitCount(List<Document> documents) {
        List<Long> ids = new ArrayList<>();
        for (Document doc : documents) {
            String idStr = asString(doc.getMetadata().get("chunk_id"));
            if (!StringUtils.hasText(idStr)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(idStr));
            } catch (NumberFormatException ignored) {
                // 忽略非数字 chunk_id
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            knowledgeChunkMapper.incrementHitCount(ids);
        } catch (Exception e) {
            log.warn("命中数累加失败: {}", e.getMessage());
        }
    }

    /**
     * 回答生成（主路径，RAG）：
     * A. 结构化 markdown（默认开）：模型只输出「小标题 + 原文行号分组」（planBullets），
     *    Java 侧按行号逐字拼原文、渲染成 markdown 要点——每组以 **小标题** 开头，正文逐字来自资料，
 *    竖线表格行自动转合法 markdown 表格；某句提到「见下表」且模型漏选表格时由
 *    backfillReferencedTables 兜底并入该组后的表格块，杜绝「具体见下表」悬空。
 *    ——模型不重写任何事实句，只起小标题与分组，故不引入语序错乱/数字幻觉。
 * B. 扁平抽取式（降级 / 开关关闭时）：与旧版三档逐字节一致——
 *    ① 严格选句（全量）→ ② 空则收敛 top-1 再严格 → ③ 仍空且最相似片段相关度足够
 *    (≥ rag.partial-min-score) 时宽松选句（先 top-1、再全量）；仍空回固定兜底文案。
 *    纯提问/标题句不单独充当答案。
     */
    private String answer(List<Document> documents, String question) {
        if (documents.isEmpty()) {
            return NO_CONTENT_MSG;
        }
        List<UnitItem> fullUnits = splitUnits(documents);

        // A. 结构化 markdown 主路径：成功渲染出非空文本才用；失败/为空一律降级，绝不比旧版更易回兜底
        if (structuredEnabled) {
            try {
                List<BulletGroup> groups = planBullets(question, fullUnits);
                String md = renderMarkdown(groups, fullUnits);
                if (StringUtils.hasText(md)) {
                    return withFooter(md, documents);
                }
            } catch (Exception e) {
                log.warn("结构化 markdown 回答失败，回退扁平抽取: {}", e.getMessage());
            }
        }

        // B. 扁平抽取式三档（降级）
        List<UnitItem> units = fullUnits;
        List<Integer> selected = selectIndexes(question, units);                                  // ①严格·全部片段
        if (isEmptySelection(selected, units) && documents.size() > 1) {
            units = splitUnits(documents.subList(0, 1));
            selected = selectIndexes(question, units);                                            // ②严格·最相似片段
        }
        Double best = bestScore(documents);
        if (isEmptySelection(selected, units) && best != null && best >= partialMinScore) {
            selected = selectPartial(question, units);                                            // ③宽松·当前候选
            if (isEmptySelection(selected, units) && documents.size() > 1) {
                List<UnitItem> f = splitUnits(documents);
                selected = selectPartial(question, f);                                            // ③宽松·全量片段
                if (!isEmptySelection(selected, f)) {
                    units = f;
                }
            }
        }
        if (isEmptySelection(selected, units)) {
            return NO_CONTENT_MSG;
        }
        return withFooter(joinSelected(selected, units), documents);
    }

    /**
     * 把检索到的知识片段切成“句元”：
     * - 竖线表格行（含至少一个 |、两侧有内容）**整行**作为一个句元（tableLine=true），
     *   不做行内切句，保证一张表的每一行原子、可被整块还原成 markdown 表格；
     * - 其余按换行分行，行内再按句末标点（。！？!?）切分并保留标点。
     */
    private List<UnitItem> splitUnits(List<Document> documents) {
        List<UnitItem> units = new ArrayList<>();
        for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
            Document doc = documents.get(docIndex);
            if (doc == null || doc.getContent() == null) {
                continue;
            }
            for (String line : mergeWrappedLines(doc.getContent())) {
                if (TABLE_LINE.matcher(line).matches()) {
                    if (!isSeparatorRow(line)) {
                        units.add(new UnitItem(docIndex, line, true));
                    }
                    continue;
                }
                for (String part : SENT_SPLIT.split(line)) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        units.add(new UnitItem(docIndex, trimmed, false));
                    }
                }
            }
        }
        return units;
    }

    /**
     * 把**被硬折行截断**的行拼回一整行。
     *
     * <p>为什么需要：源文档（尤其 PDF / 网页转来的）常在一行中途硬换行，例如
     * 「…审核，确定一、」+「四级学业预警学生名单。」。不拼的话切句元会切出碎片，
     * 答案里就会出现半截话的条目 —— 这是 150 题评估实测暴露出来的（N61）。
     *
     * <p>判定刻意保守，只拼「明显没说完」的行：
     * <ul>
     *   <li>以逗号/顿号/冒号结尾 → 一定没说完</li>
     *   <li>以句末标点或右引号/右括号结尾 → 说完了，绝不拼</li>
     *   <li>其余（无标点结尾）：**长行**才当硬折行；短行视为小标题/列表项（如「（五）开展帮扶」），不拼</li>
     * </ul>
     * 表格行之间永不互拼（表格有自己的整行语义）。
     */
    private List<String> mergeWrappedLines(String content) {
        List<String> merged = new ArrayList<>();
        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!merged.isEmpty()) {
                String prev = merged.get(merged.size() - 1);
                boolean bothPlain = !TABLE_LINE.matcher(prev).matches()
                        && !TABLE_LINE.matcher(line).matches();
                if (bothPlain && isWrappedLine(prev)) {
                    merged.set(merged.size() - 1, prev + line);
                    continue;
                }
            }
            merged.add(line);
        }
        return merged;
    }

    /** 上一行是否明显被硬折行截断（应与下一行合并） */
    private boolean isWrappedLine(String prev) {
        if (prev == null || prev.isEmpty()) {
            return false;
        }
        char last = prev.charAt(prev.length() - 1);
        if (last == '，' || last == '、' || last == '：' || last == ',' || last == ':') {
            return true;
        }
        if (last == '。' || last == '！' || last == '？' || last == '!' || last == '?'
                || last == '；' || last == ';' || last == '”' || last == '）' || last == ')') {
            return false;
        }
        return prev.length() >= 20;
    }

    /**
     * 让模型挑句子编号：只输出编号，不做任何复述。
     * 返回 0 基、按原文顺序升序排列的被选句元下标；异常或选择为空时返回空列表。
     */
    private List<Integer> selectIndexes(String question, List<UnitItem> units) {
        int cap = Math.min(units.size(), MAX_SELECT_UNITS);
        if (cap == 0) {
            return Collections.emptyList();
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < cap; i++) {
            list.append(i + 1).append(". ").append(units.get(i).text).append('\n');
        }
        String prompt = PromptSanitizer.CONTEXT_HARDENING
                + "下面是若干条编号的知识资料句子。请选出能直接回答用户问题的所有句子编号，"
                + "只输出编号，多个编号用英文逗号分隔；没有能直接回答的句子就只输出 0。"
                + "不要输出任何其他文字。\n\n"
                + list
                + promptSanitizer.wrapQuestion(question) + "答案编号：";
        try {
            String resp = chatClient.call(new Prompt(prompt)).getResult().getOutput().getContent();
            if (!StringUtils.hasText(resp)) {
                return Collections.emptyList();
            }
            TreeSet<Integer> set = new TreeSet<>();
            Matcher m = DIGIT_PATTERN.matcher(resp);
            while (m.find()) {
                int v = Integer.parseInt(m.group());
                if (v >= 1 && v <= cap) {
                    set.add(v - 1);
                }
            }
            return new ArrayList<>(set);
        } catch (Exception e) {
            log.warn("句子编号选择失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 宽松选句（第三档兜底）：与严格档一样只输出编号、由 Java 按编号拼原文，杜绝语序错乱；
     * 区别是当问题较宽泛、无单句能完整作答时，允许模型“挑最相关、承载实质信息的至多 3 句”
     * 作参考回答，且不选以问号结尾的提问句/标题句。返回 0 基、升序下标；异常/为空返回空列表。
     */
    private List<Integer> selectPartial(String question, List<UnitItem> units) {
        int cap = Math.min(units.size(), MAX_SELECT_UNITS);
        if (cap == 0) {
            return Collections.emptyList();
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < cap; i++) {
            list.append(i + 1).append(". ").append(units.get(i).text).append('\n');
        }
        String prompt = PromptSanitizer.CONTEXT_HARDENING
                + "下面是若干条编号的知识资料句子。请从中挑选与用户问题相关、包含答案信息的句子编号。\n"
                + "规则：最多选 3 条；只选承载实质内容的句子，不要选以问号结尾的提问句或标题句；"
                + "若没有任何句子与问题相关，只输出 0。\n"
                + "只输出编号，用英文逗号分隔，不要输出其他文字。\n\n"
                + list
                + promptSanitizer.wrapQuestion(question) + "答案编号：";
        try {
            String resp = chatClient.call(new Prompt(prompt)).getResult().getOutput().getContent();
            if (!StringUtils.hasText(resp)) {
                return Collections.emptyList();
            }
            TreeSet<Integer> set = new TreeSet<>();
            Matcher m = DIGIT_PATTERN.matcher(resp);
            while (m.find()) {
                int v = Integer.parseInt(m.group());
                if (v >= 1 && v <= cap) {
                    set.add(v - 1);
                }
            }
            return new ArrayList<>(set);
        } catch (Exception e) {
            log.warn("宽松选句失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 判定一个句元是否只是提问/标题句（以？结尾且较短）——本身不承载答案，不应单独充当回答 */
    private boolean isQuestionUnit(String unit) {
        return (unit.endsWith("？") || unit.endsWith("?")) && unit.length() <= 40;
    }

    /** 选择是否“有效为空”：没选中任何句子，或只选中了提问/标题句（没有实质答案内容） */
    private boolean isEmptySelection(List<Integer> selected, List<UnitItem> units) {
        if (selected == null || selected.isEmpty()) {
            return true;
        }
        for (int idx : selected) {
            if (!isQuestionUnit(units.get(idx).text)) {
                return false;
            }
        }
        return true;
    }

    /** 拼接被选句元：优先丢弃提问/标题句，保证输出的是实打实的答案内容；句尾追加行内引用号 */
    private String joinSelected(List<Integer> selected, List<UnitItem> units) {
        StringBuilder sb = new StringBuilder();
        for (int idx : selected) {
            UnitItem item = units.get(idx);
            if (isQuestionUnit(item.text)) {
                continue;
            }
            sb.append(item.text).append(cite(item.docIndex));
        }
        return sb.toString();
    }

    /**
     * 行内引用标记：追加在句末标点之后，内容为 [n]，n = docIndex + 1 指向 sources[n-1]。
     * 只在 Java 拼装阶段生成，**绝不让模型输出引用标记** —— 这是「抽取式回答逐字保真」铁律的必然推论。
     * inlineCitations 关闭或 docIndex 未知（-1）时返回空串，答案即与旧版逐字节一致。
     */
    private String cite(int docIndex) {
        if (!inlineCitations || docIndex < 0) {
            return "";
        }
        return " [" + (docIndex + 1) + "]";
    }

    // ==================== 结构化 markdown 抽取（A 路径） ====================

    /**
     * 一次调用让模型做“分组 + 起小标题”：只输出 JSON，不做任何复述/造句。
     * 返回的每组 = 1 个 ≤12 字小标题 + 若干个 0 基原文行号；组数不超过 structuredMaxGroups、
     * 每组行数不超过 structuredMaxPerGroup，跨组去重。异常/解析失败返回空列表（走扁平降级）。
     */
    private List<BulletGroup> planBullets(String question, List<UnitItem> units) {
        int cap = Math.min(units.size(), MAX_SELECT_UNITS);
        if (cap == 0) {
            return Collections.emptyList();
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < cap; i++) {
            list.append(i + 1).append(". ").append(units.get(i).text).append('\n');
        }
        String prompt = PromptSanitizer.CONTEXT_HARDENING
                + "下面是按原顺序编号的知识资料片段（含句子与表格行），每一行是一个可引用的整体。用户问题如下。\n"
                + "请把能回答用户问题的原文片段分成若干“要点”，每个要点：\n"
                + "1) 起一个不超过 12 个字的小标题（概括该要点要说的内容，不加编号、不用标点结尾、不要引用符号）；\n"
                // 标题只描述主题、不下结论：实测模型写过「帮扶与退学无直接关联」这种**原文里没有的判断**，
                // 标题是会被用户看到的，等于凭空造了一句结论（评估中由此产生了唯一的非忠实论断）
                + "   小标题只描述主题，不要下判断或结论（例如不要写「帮扶与退学无直接关联」这类原文没有的断言）；\n"
                + "2) 列出要引用的原文编号（片段前面的数字），每个要点至多 " + structuredMaxPerGroup + " 条，编号从小到大；\n"
                + "3) 只引用承载实质信息、能直接回答问题的编号，不要选以问号结尾的纯提问句或标题句，不要复述改写；\n"
                + "4) 要点总数量不超过 " + structuredMaxGroups + " 个，宁可少而精，答不上的内容不要硬塞。\n"
                + "只输出一个 JSON 对象，不要 markdown 代码块、不要任何其它文字。格式：\n"
                + "{\"groups\":[{\"t\":\"小标题\",\"n\":[1,5,9]},{\"t\":\"小标题\",\"n\":[2,3]}]}\n\n"
                + "片段：\n" + list
                + promptSanitizer.wrapQuestion(question);
        try {
            String resp = chatClient.call(new Prompt(prompt)).getResult().getOutput().getContent();
            return parseGroups(resp, cap);
        } catch (Exception e) {
            log.warn("结构化分组失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 容错解析模型返回的 {"groups":[{t,n}]}：剥代码块、裁越界、去重、限组数/每组成员数 */
    private List<BulletGroup> parseGroups(String resp, int cap) {
        if (!StringUtils.hasText(resp)) {
            return Collections.emptyList();
        }
        String s = resp.trim();
        s = s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) {
            s = s.substring(a, b + 1);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(s);
        } catch (Exception e) {
            log.warn("分组 JSON 解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
        JsonNode arr = node == null ? null : node.get("groups");
        if (arr == null || !arr.isArray()) {
            return Collections.emptyList();
        }
        List<BulletGroup> groups = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (JsonNode el : arr) {
            if (groups.size() >= structuredMaxGroups) {
                break;
            }
            String title = el.path("t").asText("");
            if (!StringUtils.hasText(title)) {
                title = el.path("title").asText("");
            }
            title = cleanTitle(title);
            JsonNode nums = el.get("n");
            List<Integer> idxs = new ArrayList<>();
            if (nums != null && nums.isArray()) {
                for (JsonNode num : nums) {
                    int one;
                    if (num.isNumber()) {
                        one = num.asInt();
                    } else if (num.isTextual()) {
                        try {
                            one = Integer.parseInt(num.asText().trim());
                        } catch (NumberFormatException e) {
                            continue;
                        }
                    } else {
                        continue;
                    }
                    if (one < 1 || one > cap) {
                        continue;
                    }
                    int zero = one - 1;
                    if (used.contains(zero)) {
                        continue;
                    }
                    used.add(zero);
                    idxs.add(zero);
                    if (idxs.size() >= structuredMaxPerGroup) {
                        break;
                    }
                }
            }
            if (!idxs.isEmpty()) {
                idxs.sort(Comparator.naturalOrder());
                groups.add(new BulletGroup(title, idxs));
            }
        }
        return groups;
    }

    /**
     * 把分组结果渲染成 markdown：每组 = **小标题** 单独一行 + 逐字正文段落；
     * 若正文某句提到表格（见下表/如下…）而模型没选表，则把该句之后同一片段内紧邻的
     * 表格行整块并进来，转合法 GFM 表格——杜绝“具体见下表”悬空。拼不出任何内容返回空串。
     */
    private String renderMarkdown(List<BulletGroup> groups, List<UnitItem> units) {
        if (groups == null || groups.isEmpty()) {
            return "";
        }
        List<String> entries = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (BulletGroup g : groups) {
            List<Integer> idxs = new ArrayList<>();
            for (int i : g.indexes) {
                if (i < 0 || i >= units.size() || used.contains(i)) {
                    continue;
                }
                used.add(i);
                idxs.add(i);
            }
            if (idxs.isEmpty()) {
                continue;
            }
            idxs.sort(Comparator.naturalOrder());

            List<String> proseText = new ArrayList<>();
            List<Integer> proseIndex = new ArrayList<>();
            List<String> tableRows = new ArrayList<>();
            int tableDocIndex = -1;
            for (int i : idxs) {
                UnitItem u = units.get(i);
                if (u.tableLine) {
                    tableRows.add(u.text);
                    if (tableDocIndex < 0) {
                        tableDocIndex = u.docIndex;
                    }
                } else if (!isQuestionUnit(u.text)) {
                    // 展示层去掉原文序号前缀（1. / 2、 / 三．…），重组后原序号已无意义且会误导
                    String body = stripLeadingNumber(u.text);
                    if (!body.isEmpty()) {
                        proseText.add(body);
                        proseIndex.add(i);
                    }
                }
            }

            // 某句“见下表/如下/如表…”且其后的同片段确有紧邻表格：整块并回（覆盖模型漏选/只挑几行），
            // 全文优先于模型零散选行——避免“具体见下表”悬空。backfill 会把这些行标为已用，防重复渲染。
            TableBlock backfill = referencedTableRun(proseIndex, units, used);
            if (backfill != null && !backfill.rows.isEmpty()) {
                tableRows = backfill.rows;
                tableDocIndex = backfill.docIndex;
            }

            if (proseText.isEmpty() && tableRows.isEmpty()) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            if (StringUtils.hasText(g.title)) {
                lines.add("**" + g.title + "**");
            }
            // 仿官方：每组下每个原文句单独成一条列表项，逐字不改写（条例更清晰）。
            // 行内引用 [n] 只在句末追加，句元原文本身零改动（逐字保真的关键）。
            for (int k = 0; k < proseText.size(); k++) {
                lines.add("- " + proseText.get(k) + cite(units.get(proseIndex.get(k)).docIndex));
            }
            if (tableRows.size() >= 2) {
                lines.add("");
                lines.add(toMarkdownTable(tableRows));
                // 表格整块来自同一个片段，在表后统一标一次引用号。
                // 必须**另起一段**：GFM 表格会丢弃超出表头列数的单元格，若把 [n] 直接接在最后一行
                // 末尾，它会被当成多出来的第 N+1 列而被静默吃掉（实测确认过）。
                String tableCite = cite(tableDocIndex);
                if (!tableCite.isEmpty()) {
                    lines.add("");
                    lines.add(tableCite.trim());
                }
            } else if (tableRows.size() == 1) {
                // 只有单行不成表：退化成“标签：内容”一句话，避免孤行表头
                String inline = toInlineTableRow(tableRows.get(0));
                if (StringUtils.hasText(inline)) {
                    lines.add(inline + cite(tableDocIndex));
                }
            }
            entries.add(String.join("\n", lines));
        }
        return entries.isEmpty() ? "" : String.join("\n\n", entries);
    }

    /**
     * 遍历“被本要点引用的正文句”：若某句提到表格（见下表/如下/如表…）且其后的同片段内有一串
     * 紧邻表格行，返回该串表格行的**完整原文文本**（取整块，不因个别行已被模型选中而截断）
     * 及其所属片段下标（供行内引用编号），并把整块句元标为已用（防后续要点重复渲染）；
     * 找不到则返回 null。
     */
    private TableBlock referencedTableRun(List<Integer> proseIndex, List<UnitItem> units, Set<Integer> used) {
        for (int si : proseIndex) {
            UnitItem s = units.get(si);
            if (!TABLE_REF.matcher(s.text).find()) {
                continue;
            }
            int p = si + 1;
            List<String> rows = new ArrayList<>();
            List<Integer> mark = new ArrayList<>();
            while (p < units.size()) {
                UnitItem u = units.get(p);
                if (u.docIndex != s.docIndex || !u.tableLine) {
                    break;
                }
                rows.add(u.text);
                mark.add(p);
                p++;
            }
            if (!rows.isEmpty()) {
                used.addAll(mark);
                return new TableBlock(rows, s.docIndex);
            }
        }
        return null;
    }

    /** 清洗模型给的小标题：去 **、去行首编号/点号、去尾标点、限长 */
    private String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = title.replace("**", "").trim();
        Matcher m = LEAD_NUM.matcher(t);
        if (m.find()) {
            t = t.substring(m.end()).trim();
        }
        while (!t.isEmpty()) {
            char last = t.charAt(t.length() - 1);
            if (last == '。' || last == '．' || last == '.' || last == '：' || last == ':'
                    || last == '！' || last == '！') {
                t = t.substring(0, t.length() - 1).trim();
            } else {
                break;
            }
        }
        if (t.length() > 24) {
            t = t.substring(0, 24);
        }
        return t.trim();
    }

    /** 去掉句元开头的原文序号/小节标记（1. / 2、/ 三．…，可嵌套），最多剥 3 层 */
    private String stripLeadingNumber(String text) {
        String t = text == null ? "" : text.trim();
        for (int k = 0; k < 3; k++) {
            Matcher m = LEAD_NUM.matcher(t);
            if (!m.find()) {
                break;
            }
            t = t.substring(m.end()).trim();
        }
        return t;
    }

    /** 最相似片段（检索结果首个文档）的相似度，用于宽松兜底的相关性门槛 */
    private Double bestScore(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        Map<String, Object> md = documents.get(0).getMetadata();
        Object distance = md.get("distance");
        if (distance instanceof Number n) {
            return 1.0 - n.doubleValue();
        }
        Object similarity = md.get("similarity");
        if (similarity instanceof Number m) {
            return m.doubleValue();
        }
        return null;
    }

    /**
     * 编程式事务保存：确保会话存在 + 写入 user 消息 + assistant 消息
     */
    private Exchange persistExchange(Long conversationId, Long userId, String question, String answer,
                                     List<Document> documents, boolean fromCache) {
        return persistExchange(conversationId, userId, question, answer,
                toCitationsJson(documents), fromCache);
    }

    /**
     * 落库一次问答。citationsJson 直接给字符串是为了覆盖「缓存命中」路径 ——
     * 那条路上没有 Document（只有缓存里的 Source），但引用关系仍要保留。
     */
    private Exchange persistExchange(Long conversationId, Long userId, String question, String answer,
                                     String citationsJson, boolean fromCache) {
        return transactionTemplate.execute(status -> {
            Long convId = ensureConversation(conversationId, userId, question);

            Message userMsg = new Message();
            userMsg.setConversationId(convId);
            userMsg.setSenderType("USER");
            userMsg.setContent(question);
            userMsg.setIntentCategory(intentService.classify(question));
            userMsg.setMessageType("TEXT");
            userMsg.setStatus(1);
            messageMapper.insert(userMsg);

            Message aiMsg = new Message();
            aiMsg.setConversationId(convId);
            aiMsg.setSenderType("AI");
            aiMsg.setContent(answer);
            aiMsg.setCitations(citationsJson);
            aiMsg.setFromCache(fromCache ? 1 : 0);
            aiMsg.setMessageType("TEXT");
            aiMsg.setStatus(1);
            aiMsg.setFeedback(0);
            messageMapper.insert(aiMsg);

            // 更新会话最后回复时间
            Conversation conv = conversationMapper.selectById(convId);
            if (conv != null) {
                conv.setLastReplyTime(LocalDateTime.now());
                conversationMapper.updateById(conv);
            }

            return new Exchange(convId, aiMsg.getId());
        });
    }

    /** 从缓存的 sources 还原 citations JSON（缓存命中路径没有 Document，只能从 Source 拿 chunkId） */
    private String citationsJsonFromSources(List<Source> sources) {
        try {
            List<String> ids = new ArrayList<>();
            if (sources != null) {
                for (Source s : sources) {
                    if (s != null && StringUtils.hasText(s.getChunkId())) {
                        ids.add(s.getChunkId());
                    }
                }
            }
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Long ensureConversation(Long conversationId, Long userId, String question) {
        // 复用仍在「进行中」且归属当前用户的会话；已结束(2)/不存在/非本人 → 视为需要新开会话
        if (conversationId != null) {
            Conversation existing = conversationMapper.selectById(conversationId);
            if (existing != null
                    && Integer.valueOf(1).equals(existing.getStatus())
                    && sameUser(existing.getUserId(), userId)) {
                return conversationId;
            }
        }
        // 登录用户：复用其最近一条「进行中」的自动会话，避免每次刷新/重进都开新会话
        if (userId != null && userId != 0L) {
            Conversation active = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getUserId, userId)
                    .eq(Conversation::getSessionType, "AUTO")
                    .eq(Conversation::getStatus, 1)
                    .orderByDesc(Conversation::getId)
                    .last("LIMIT 1"));
            if (active != null) {
                return active.getId();
            }
        }
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(truncate(question, 50));
        conversation.setSessionType("AUTO");
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    /** 判断会话归属是否与当前用户一致（游客 userId=0，仅可复用归属为 0 的会话） */
    private boolean sameUser(Long ownerId, Long userId) {
        if (ownerId == null) {
            return userId == null || userId == 0L;
        }
        return ownerId.equals(userId);
    }

    private Long resolveUserId() {
        String username = SecurityUtil.currentUsername();
        if (username == null) {
            return 0L;
        }
        SysUser user = userService.getByUsername(username);
        return user == null ? 0L : user.getId();
    }

    private ChatAnswer buildAnswer(String answer, boolean fromCache, Long convId,
                                   List<Document> documents, String question, AnswerContext ctx) {
        ChatAnswer result = new ChatAnswer();
        result.setAnswer(answer);
        result.setFromCache(fromCache);
        result.setConversationId(convId);
        result.setSources(toSources(documents));
        result.setIntentCategory(intentService.classify(question));
        // 拒答时 documents 已被清空，故优先用决策上下文里记下的分数（拒答前的真实最高相似度）
        result.setMaxScore(ctx != null && ctx.maxScore != null ? ctx.maxScore : bestScore(documents));
        result.setRetrievalMode(ctx != null && ctx.retrievalMode != null
                ? ctx.retrievalMode : retrieverFactory.activeMode());
        result.setRejected(ctx != null && ctx.rejected);
        result.setRejectReason(ctx == null ? null : ctx.rejectReason);
        result.setLowConfidence(ctx != null && ctx.lowConfidence);
        result.setCreatedAt(LocalDateTime.now());
        return result;
    }

    /** 兜底文案与拒答都不入缓存：避免「没有内容」这类旧否定被误命中 1 小时（CLAUDE.md 踩坑） */
    private boolean skipCache(String answer, AnswerContext ctx) {
        return (ctx != null && ctx.rejected) || NO_CONTENT_MSG.equals(answer);
    }

    /**
     * 把「没答上」的问题沉淀进未解决问题池（批次 E 数据飞轮入口）。
     *
     * <p>这里判定三个来源：兜底文案 / 相似度过低被拒答 / 命中注入防护拦截。
     * 第四个来源「用户点踩」在 {@code MessageFeedbackController} 里记 —— 那是显式反馈，
     * 只有用户点了才有，不属于问答主链路。
     */
    private void recordUnresolved(String question, Exchange exchange, Generation g, Long userId) {
        int source;
        if (g.ctx.rejected) {
            String reason = g.ctx.rejectReason == null ? "" : g.ctx.rejectReason;
            source = reason.startsWith("injection-")
                    ? UnresolvedQuestionService.SOURCE_BLOCKED
                    : UnresolvedQuestionService.SOURCE_REJECTED;
        } else if (NO_CONTENT_MSG.equals(g.answer)) {
            source = UnresolvedQuestionService.SOURCE_FALLBACK;
        } else {
            return;   // 正常作答，不入池
        }
        unresolvedQuestionService.record(question, source, g.ctx.maxScore,
                exchange.conversationId, exchange.aiMessageId, userId);
    }

    /**
     * 拒答判定：发生在检索之后、调用大模型之前。
     * - 最高相似度 &lt; reject-threshold → 拒答（rejectThreshold ≤ 0 时关闭该判定，用于回滚）
     * - 落在 [reject-threshold, low-confidence-threshold) → 照常作答，但标记低置信
     *
     * <p>分数一律取「融合前向量池 top-1 余弦」（{@link #bestScore}），与 BM25/RRF/rerank 的分数无关 ——
     * 这样批次 D 引入多路检索后，拒答阈值与 {@code rag.partial-min-score} 的语义完全不变。
     */
    private RejectDecision rejectPolicy(List<Document> documents, Double maxVectorSimilarity) {
        Double best = maxVectorSimilarity != null ? maxVectorSimilarity : bestScore(documents);
        if (rejectThreshold <= 0) {
            return new RejectDecision(false, false, best);
        }
        if (documents.isEmpty()) {
            // 一条都没召回：与「相似度过低」同义，直接拒答，省掉一次必然为空的模型调用
            return new RejectDecision(true, false, best);
        }
        if (best != null && best < rejectThreshold) {
            return new RejectDecision(true, false, best);
        }
        boolean low = best != null && lowConfidenceThreshold > 0 && best < lowConfidenceThreshold;
        return new RejectDecision(false, low, best);
    }

    private String rejectMessage() {
        return StringUtils.hasText(rejectMessageCfg) ? rejectMessageCfg : DEFAULT_REJECT_MSG;
    }

    /**
     * 分阶段耗时埋点（毫秒）。
     *
     * <p>本问答是伪流式：答案在 Flux 创建之前已被完整算出，所以「首片 token 到达」的时刻
     * 实际等于整条链路算完的时刻，ttftMs 与 totalMs 同值。这是该实现形态的固有性质，
     * 评估报告里按此口径如实标注，不伪装成真流式。
     */
    private Map<String, Long> timings(long retrieveMs, long generateMs, long totalMs) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("retrieveMs", Math.max(0L, retrieveMs));
        m.put("generateMs", Math.max(0L, generateMs));
        m.put("totalMs", Math.max(0L, totalMs));
        m.put("ttftMs", Math.max(0L, totalMs));
        return m;
    }

    private List<Source> toSources(List<Document> documents) {
        Map<Long, KnowledgeChunk> byId = loadChunks(documents);
        List<Source> sources = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> md = doc.getMetadata();
            Source source = new Source();
            // 行内引用序号与 sources 下标一一对应（1 基），正文中的 [n] 即指向 sources 的第 n 条
            source.setIndex(sources.size() + 1);
            source.setChunkId(asString(md.get("chunk_id")));
            source.setTitle(asString(md.get("title")));
            source.setCategory(asString(md.get("category")));
            // 参考来源给完整片段并做 markdown 表格化（前端 MarkdownText 渲染），不再截 200 字
            source.setContent(tableize(doc.getContent()));
            source.setScore(readScore(md));
            Long cid = chunkIdOf(doc);
            KnowledgeChunk chunk = cid == null ? null : byId.get(cid);
            if (chunk != null) {
                source.setSourceTitle(chunk.getSourceTitle());
                source.setSourceUrl(chunk.getSourceUrl());
                source.setUpdateTime(chunk.getUpdateTime());
            }
            sources.add(source);
        }
        return sources;
    }

    /** 取文档 metadata 里的 chunk_id（数字），无或非数字返回 null */
    private Long chunkIdOf(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        String idStr = asString(doc.getMetadata().get("chunk_id"));
        if (!StringUtils.hasText(idStr)) {
            return null;
        }
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 按 chunk_id 批量回查知识片段。引用溯源（toSources）与参考文件尾注（referenceFooter）
     * 共用此方法，避免一次回答查两遍库；查询失败返回空 Map，由调用方各自降级，绝不影响回答本身。
     */
    private Map<Long, KnowledgeChunk> loadChunks(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = new ArrayList<>();
        for (Document doc : documents) {
            Long id = chunkIdOf(doc);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectBatchIds(ids);
            return chunks == null ? Collections.emptyMap() : chunks.stream()
                    .collect(Collectors.toMap(KnowledgeChunk::getId, c -> c, (a, b) -> a));
        } catch (Exception e) {
            log.warn("回查知识片段失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 兜底文案不加尾注；有实质回答时在末尾附「参考文件」原件（标题→链接） */
    private String withFooter(String text, List<Document> documents) {
        if (!StringUtils.hasText(text) || NO_CONTENT_MSG.equals(text)) {
            return text;
        }
        String footer = referenceFooter(documents);
        return footer.isEmpty() ? text : text + "\n\n" + footer;
    }

    /**
     * 生成「参考文件」尾注：把本次检索命中的片段按 chunk_id 回查 knowledge_chunk 的
     * source_url / source_title，按「文件」去重（同一篇被切多片只列一次），标题取干净的源标题。
     * markdown 链接：[源标题](来源链接)；无来源链接则只列标题文字。
     */
    private String referenceFooter(List<Document> documents) {
        Map<Long, KnowledgeChunk> byId = loadChunks(documents);
        if (byId.isEmpty()) {
            return "";
        }
        // 按「来源链接（无则用源标题/标题）」去重，保持检索序
        List<String> seen = new ArrayList<>();
        List<String> links = new ArrayList<>();
        for (Document doc : documents) {
            Long cid = chunkIdOf(doc);
            KnowledgeChunk chunk = cid == null ? null : byId.get(cid);
            if (chunk == null) {
                continue;
            }
            String url = chunk.getSourceUrl();
            String title = StringUtils.hasText(chunk.getSourceTitle())
                    ? chunk.getSourceTitle() : chunk.getTitle();
            if (!StringUtils.hasText(title)) {
                title = "知识片段";
            }
            String key = StringUtils.hasText(url) ? url : title;
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            links.add(StringUtils.hasText(url)
                    ? "- [" + title + "](" + url + ")"
                    : "- " + title);
        }
        return links.isEmpty() ? "" : "参考文件：\n" + String.join("\n", links);
    }

    private String toCitationsJson(List<Document> documents) {
        try {
            List<String> ids = documents.stream()
                    .map(d -> asString(d.getMetadata().get("chunk_id")))
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ==================== SSE 事件构造 ====================

    private String tokenEvent(String token) {
        return json(Map.of("type", "token", "content", token == null ? "" : token));
    }

    /** 把一段文本切成固定大小的小片（保持 SSE 逐片推送的流式形态） */
    private List<String> slice(String text, int size) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        int n = text.length();
        for (int i = 0; i < n; i += size) {
            parts.add(text.substring(i, Math.min(i + size, n)));
        }
        return parts;
    }

    private String endEvent(ChatAnswer answer) {
        Map<String, Object> map = objectMapper.convertValue(answer, LinkedHashMap.class);
        map.put("type", "end");
        return json(map);
    }

    private String errorEvent(String message) {
        return json(Map.of("type", "error", "message", message));
    }

    private String json(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"序列化失败\"}";
        }
    }

    // ==================== 工具 ====================

    private String asString(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    // ---------- markdown 表格工具（知识原文以“a | b | c”竖线行存储，无 |---| 分隔行） ----------

    /**
     * 把内容里的竖线表格行（可能没带 |---| 分隔行）转成合法的 markdown 表格：
     * 连续表格行聚成一个表格、首行作表头、自动补 --- 分隔行；非表格内容原样保留。
     * 单行“像表格”的行不转换（多半是装饰性残留，保持原样可读）。
     */
    private String tableize(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String t0 = lines[i].trim();
            if (!TABLE_LINE.matcher(t0).matches() || isSeparatorRow(t0)) {
                out.append(lines[i]).append('\n');
                i++;
                continue;
            }
            int j = i;
            List<String> rows = new ArrayList<>();
            while (j < lines.length && TABLE_LINE.matcher(lines[j].trim()).matches()) {
                String cur = lines[j].trim();
                if (!isSeparatorRow(cur)) {
                    rows.add(cur);
                }
                j++;
            }
            if (rows.size() >= 2) {
                out.append(toMarkdownTable(rows)).append('\n');
            } else {
                for (int k = i; k < j; k++) {
                    out.append(lines[k]).append('\n');
                }
            }
            i = j;
        }
        String result = out.toString();
        return result.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }

    /** 若干竖线行 → GFM markdown 表格（首行=表头，自动补 --- 分隔行，缺列补空单元格） */
    private String toMarkdownTable(List<String> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        List<List<String>> table = new ArrayList<>();
        int cols = 0;
        for (String r : rows) {
            List<String> cells = splitCells(r);
            if (!cells.isEmpty()) {
                cols = Math.max(cols, cells.size());
                table.add(cells);
            }
        }
        if (table.isEmpty() || cols == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int ri = 0; ri < table.size(); ri++) {
            String[] padded = new String[cols];
            List<String> cells = table.get(ri);
            for (int c = 0; c < cols; c++) {
                padded[c] = c < cells.size() ? cells.get(c) : "";
            }
            sb.append("| ").append(String.join(" | ", padded)).append(" |");
            if (ri == 0) {
                String[] seps = new String[cols];
                Arrays.fill(seps, "---");
                sb.append('\n').append("| ").append(String.join(" | ", seps)).append(" |");
            }
            if (ri < table.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 单行表格行（如“8月27日 | 材料…、信息…”）→ 一句普通文字“标签：内容”，供不成表时使用 */
    private String toInlineTableRow(String line) {
        List<String> cells = splitCells(line);
        if (cells.isEmpty()) {
            return "";
        }
        String label = cells.get(0);
        if (cells.size() == 1) {
            return label;
        }
        return label + "：" + String.join("；", cells.subList(1, cells.size()));
    }

    /** 去掉行首/行尾竖线后按 | 拆列并逐格去空白（表格原文里单元格不含竖线） */
    private List<String> splitCells(String line) {
        String s = line.trim();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : s.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    /** 某行是否全为 markdown 表格分隔单元格（---/:--:/…），用于跳过已带分隔行的来源 */
    private boolean isSeparatorRow(String line) {
        List<String> cells = splitCells(line);
        if (cells.isEmpty()) {
            return false;
        }
        for (String c : cells) {
            if (!SEP_CELL.matcher(c).matches()) {
                return false;
            }
        }
        return true;
    }

    private Double readScore(Map<String, Object> md) {
        Object distance = md.get("distance");
        if (distance instanceof Number n) {
            return round(1.0 - n.doubleValue());
        }
        Object similarity = md.get("similarity");
        if (similarity instanceof Number n) {
            return round(n.doubleValue());
        }
        return null;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
