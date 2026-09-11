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
import com.rag.mapper.ConversationMapper;
import com.rag.mapper.KnowledgeChunkMapper;
import com.rag.mapper.MessageMapper;
import com.rag.service.CacheService;
import com.rag.service.ChatService;
import com.rag.service.IntentService;
import com.rag.service.UserService;
import com.rag.util.SecurityUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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
    private final VectorStore vectorStore;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final CacheService cacheService;
    private final IntentService intentService;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

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

    /** 抽取式回答：参与编号选择的句子上限（防止列表过长导致选择失准） */
    private static final int MAX_SELECT_UNITS = 80;
    /** 抽取式回答：挑选句子编号时的数字提取正则 */
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");
    /** 抽取式回答：无相关内容/未选中任何句子时的固定兜底文案（不让模型自由造句，避免语序错乱） */
    private static final String NO_CONTENT_MSG = "资料中没有相关内容，建议换个问法或补充关键词。";
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

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ChatAnswer ask(Long conversationId, String question) {
        // 1. 缓存优先
        Optional<ChatAnswer> cached = cacheService.get(question);
        if (cached.isPresent()) {
            ChatAnswer answer = cached.get();
            answer.setConversationId(conversationId);
            return answer;
        }

        // 1.5 快速上手 FAQ：极简短问法（你好/你是谁/你能做什么/怎么使用等）直接给固定答案，
        //     不检索不调模型，避免“怎么使用”这类通用词把校园生活类原文（圈存/班车等）串答进来。
        String faq = faqAnswer(question);
        List<Document> documents;
        String answer;
        if (faq != null) {
            documents = Collections.emptyList();
            answer = faq;
        } else {
            // 2. 检索 + 生成（抽取式，见 answer()）
            documents = retrieve(question);
            answer = answer(documents, question);
        }

        // 3. 事务保存会话与消息（先解析 userId 再透传，保证会话归属当前用户而非游客 0）
        Long userId = resolveUserId();
        Long convId = persistExchange(conversationId, userId, question, answer, documents, false);

        // 4. 异步写缓存（兜底“没有相关内容”的答复不入缓存，避免旧否定被误命中 1 小时）
        ChatAnswer result = buildAnswer(answer, false, convId, documents, question);
        if (!answer.equals(NO_CONTENT_MSG)) {
            cacheService.put(question, result);
        }
        return result;
    }

    @Override
    public Flux<String> stream(Long conversationId, String question) {
        // 1. 缓存命中：直接返回一个 end 事件
        Optional<ChatAnswer> cached = cacheService.get(question);
        if (cached.isPresent()) {
            ChatAnswer answer = cached.get();
            answer.setConversationId(conversationId);
            return Flux.just(endEvent(answer));
        }

        // 2. 抽取式生成最终答案（模型只选句子编号，答案文本由原文逐字拼出，语序天然正确）
        final List<Document> documents;
        final String answerText;
        try {
            String faq = faqAnswer(question);
            if (faq != null) {
                documents = Collections.emptyList();
                answerText = faq;
            } else {
                documents = retrieve(question);
                answerText = answer(documents, question);
            }
        } catch (Exception e) {
            log.error("流式问答失败", e);
            return Flux.just(errorEvent(e.getMessage() == null ? "AI服务暂时不可用" : e.getMessage()));
        }

        // 2.1 关键：SSE 返回后响应体在 Reactor 线程上异步订阅执行，SecurityContext(ThreadLocal) 已不可达。
        // 必须先在此（servlet 请求线程）解析出当前用户，随闭包透传给异步持久化，否则会话会被绑定成游客 userId=0。
        final Long userId = resolveUserId();

        // 3. 把答案分片当作 token 推送，保持 SSE 流式形态；end 事件携带完整答案
        return Flux.fromIterable(slice(answerText, 18))
                .map(this::tokenEvent)
                // 4. 结束后：事务持久化 + 异步缓存 + end 事件
                .concatWith(Mono.fromCallable(() -> {
                    Long convId = persistExchange(conversationId, userId, question, answerText, documents, false);
                    ChatAnswer result = buildAnswer(answerText, false, convId, documents, question);
                    if (!answerText.equals(NO_CONTENT_MSG)) {
                        cacheService.put(question, result);
                    }
                    return endEvent(result);
                }))
                .onErrorResume(e -> {
                    log.error("流式问答失败", e);
                    return Flux.just(errorEvent(e.getMessage() == null ? "AI服务暂时不可用" : e.getMessage()));
                });
    }

    // ==================== 内部方法 ====================

    private List<Document> retrieve(String question) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.query(question).withTopK(topK));
        incrementHitCount(documents);
        return documents;
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
            for (String rawLine : doc.getContent().split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
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
        String prompt = "下面是若干条编号的知识原文句子。请选出能直接回答用户问题的所有句子编号，"
                + "只输出编号，多个编号用英文逗号分隔；没有能直接回答的句子就只输出 0。"
                + "不要输出任何其他文字。\n\n"
                + list
                + "\n用户问题：" + question + "\n答案编号：";
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
        String prompt = "下面是若干条编号的知识原文句子。请从中挑选与用户问题相关、包含答案信息的句子编号。\n"
                + "规则：最多选 3 条；只选承载实质内容的句子，不要选以问号结尾的提问句或标题句；"
                + "若没有任何句子与问题相关，只输出 0。\n"
                + "只输出编号，用英文逗号分隔，不要输出其他文字。\n\n"
                + list
                + "\n用户问题：" + question + "\n答案编号：";
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

    /** 拼接被选句元：优先丢弃提问/标题句，保证输出的是实打实的答案内容 */
    private String joinSelected(List<Integer> selected, List<UnitItem> units) {
        StringBuilder sb = new StringBuilder();
        for (int idx : selected) {
            String unit = units.get(idx).text;
            if (isQuestionUnit(unit)) {
                continue;
            }
            sb.append(unit);
        }
        return sb.toString();
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
        String prompt = "下面是按原顺序编号的知识资料片段（含句子与表格行），每一行是一个可引用的整体。用户问题如下。\n"
                + "请把能回答用户问题的原文片段分成若干“要点”，每个要点：\n"
                + "1) 起一个不超过 12 个字的小标题（概括该要点要说的内容，不加编号、不用标点结尾、不要引用符号）；\n"
                + "2) 列出要引用的原文编号（片段前面的数字），每个要点至多 " + structuredMaxPerGroup + " 条，编号从小到大；\n"
                + "3) 只引用承载实质信息、能直接回答问题的编号，不要选以问号结尾的纯提问句或标题句，不要复述改写；\n"
                + "4) 要点总数量不超过 " + structuredMaxGroups + " 个，宁可少而精，答不上的内容不要硬塞。\n"
                + "只输出一个 JSON 对象，不要 markdown 代码块、不要任何其它文字。格式：\n"
                + "{\"groups\":[{\"t\":\"小标题\",\"n\":[1,5,9]},{\"t\":\"小标题\",\"n\":[2,3]}]}\n\n"
                + "片段：\n" + list
                + "\n用户问题：" + question + "\n";
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
            for (int i : idxs) {
                UnitItem u = units.get(i);
                if (u.tableLine) {
                    tableRows.add(u.text);
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
            List<String> backfill = referencedTableRun(proseIndex, units, used);
            if (backfill != null && !backfill.isEmpty()) {
                tableRows = backfill;
            }

            if (proseText.isEmpty() && tableRows.isEmpty()) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            if (StringUtils.hasText(g.title)) {
                lines.add("**" + g.title + "**");
            }
            // 仿官方：每组下每个原文句单独成一条列表项，逐字不改写（条例更清晰）
            for (String sentence : proseText) {
                lines.add("- " + sentence);
            }
            if (tableRows.size() >= 2) {
                lines.add("");
                lines.add(toMarkdownTable(tableRows));
            } else if (tableRows.size() == 1) {
                // 只有单行不成表：退化成“标签：内容”一句话，避免孤行表头
                String inline = toInlineTableRow(tableRows.get(0));
                if (StringUtils.hasText(inline)) {
                    lines.add(inline);
                }
            }
            entries.add(String.join("\n", lines));
        }
        return entries.isEmpty() ? "" : String.join("\n\n", entries);
    }

    /**
     * 遍历“被本要点引用的正文句”：若某句提到表格（见下表/如下/如表…）且其后的同片段内有一串
     * 紧邻表格行，返回该串表格行的**完整原文文本**（取整块，不因个别行已被模型选中而截断），
     * 并把整块句元标为已用（防后续要点重复渲染）；找不到则返回 null。
     */
    private List<String> referencedTableRun(List<Integer> proseIndex, List<UnitItem> units, Set<Integer> used) {
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
                return rows;
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
    private Long persistExchange(Long conversationId, Long userId, String question, String answer,
                                 List<Document> documents, boolean fromCache) {
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
            aiMsg.setCitations(toCitationsJson(documents));
            aiMsg.setFromCache(fromCache ? 1 : 0);
            aiMsg.setMessageType("TEXT");
            aiMsg.setStatus(1);
            messageMapper.insert(aiMsg);

            // 更新会话最后回复时间
            Conversation conv = conversationMapper.selectById(convId);
            if (conv != null) {
                conv.setLastReplyTime(LocalDateTime.now());
                conversationMapper.updateById(conv);
            }

            return convId;
        });
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
                                   List<Document> documents, String question) {
        ChatAnswer result = new ChatAnswer();
        result.setAnswer(answer);
        result.setFromCache(fromCache);
        result.setConversationId(convId);
        result.setSources(toSources(documents));
        result.setIntentCategory(intentService.classify(question));
        result.setCreatedAt(LocalDateTime.now());
        return result;
    }

    private List<Source> toSources(List<Document> documents) {
        List<Source> sources = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> md = doc.getMetadata();
            Source source = new Source();
            source.setChunkId(asString(md.get("chunk_id")));
            source.setTitle(asString(md.get("title")));
            source.setCategory(asString(md.get("category")));
            // 参考来源给完整片段并做 markdown 表格化（前端 MarkdownText 渲染），不再截 200 字
            source.setContent(tableize(doc.getContent()));
            source.setScore(readScore(md));
            sources.add(source);
        }
        return sources;
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
        List<Long> ids = new ArrayList<>();
        for (Document doc : documents) {
            String idStr = asString(doc.getMetadata().get("chunk_id"));
            if (StringUtils.hasText(idStr)) {
                try {
                    ids.add(Long.parseLong(idStr));
                } catch (NumberFormatException ignored) {
                    // 忽略非数字 chunk_id
                }
            }
        }
        if (ids.isEmpty()) {
            return "";
        }
        Map<Long, KnowledgeChunk> byId;
        try {
            List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectBatchIds(ids);
            byId = chunks == null ? Map.of() : chunks.stream()
                    .collect(Collectors.toMap(KnowledgeChunk::getId, c -> c, (a, b) -> a));
        } catch (Exception e) {
            log.warn("回查参考文件失败: {}", e.getMessage());
            return "";
        }
        // 按「来源链接（无则用源标题/标题）」去重，保持检索序
        List<String> seen = new ArrayList<>();
        List<String> links = new ArrayList<>();
        for (Document doc : documents) {
            String idStr = asString(doc.getMetadata().get("chunk_id"));
            if (!StringUtils.hasText(idStr)) {
                continue;
            }
            KnowledgeChunk chunk;
            try {
                chunk = byId.get(Long.parseLong(idStr));
            } catch (NumberFormatException e) {
                continue;
            }
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
