package com.rag.guard;

import com.rag.metrics.QaMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Matcher;

/**
 * 注入防护门面：三层检测统一入口。
 *
 * <pre>
 *   L1 输入层   ask/stream 入口、检索之前     扫用户问题 —— 直接注入
 *   L2 上下文层 retrieve() 之后、生成之前     扫检索到的片段 —— 间接注入（指令式）
 *   L3 输出层   answer() 之后、拼尾注之前     扫最终答案 —— 事实式污染承诺的外泄
 * </pre>
 *
 * <p>三层都只在「防护开启」时生效（{@link GuardSwitchService}，支持运行时切换）。
 * 每层还可配置为 {@code log}（只落审计、放行），用于演示与灰度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InjectionGuard {

    /** 命中时入库的上下文长度：命中点前后各取这么多字符 */
    private static final int SNIPPET_PAD = 40;

    private final GuardProperties props;
    private final GuardSwitchService switchService;
    private final GuardEventService eventService;
    private final QaMetrics qaMetrics;

    /** L1：输入层 —— 用户问题本身就是攻击载荷 */
    public GuardDecision checkInput(String question) {
        if (!switchService.isEnabled()) {
            return GuardDecision.pass();
        }
        GuardDecision d = match(GuardRuleSet.INPUT_DENY, question, "input", null);
        return applyLevel(d, props.getInjection().getInputLevel(), question);
    }

    /** L2：上下文层 —— 检索到的知识片段里被埋了命令助手的句子 */
    public GuardDecision scanContext(List<Document> documents, String question) {
        if (!switchService.isEnabled() || documents == null || documents.isEmpty()) {
            return GuardDecision.pass();
        }
        for (Document doc : documents) {
            GuardDecision d = match(GuardRuleSet.CONTEXT_DENY, doc.getContent(), "context", chunkIdOf(doc));
            if (d.isHit()) {
                return applyLevel(d, props.getInjection().getContextLevel(), question);
            }
        }
        return GuardDecision.pass();
    }

    /** L3：输出层 —— 抽取式回答会逐字引用原文，故被污染的承诺可能直接出现在答案里 */
    public GuardDecision checkOutput(String answer, String question) {
        if (!switchService.isEnabled() || !StringUtils.hasText(answer)) {
            return GuardDecision.pass();
        }
        GuardDecision d = match(GuardRuleSet.OUTPUT_DENY, answer, "output", null);
        return applyLevel(d, props.getInjection().getOutputLevel(), question);
    }

    /** 遍历规则集，命中即返回（规则有先后：越靠前越明确） */
    private GuardDecision match(List<GuardRuleSet.Rule> rules, String text, String layer, String chunkId) {
        if (!StringUtils.hasText(text)) {
            return GuardDecision.pass();
        }
        for (GuardRuleSet.Rule rule : rules) {
            Matcher m = rule.pattern().matcher(text);
            if (m.find()) {
                return new GuardDecision(GuardDecision.BLOCK, layer, rule.id(),
                        snippet(text, m.start(), m.end()), chunkId);
            }
        }
        return GuardDecision.pass();
    }

    /**
     * 按该层配置决定动作，并统一落审计。
     * BLOCK → 拦截；log → 降级为 LOG（只记录放行）。两种情况都写 guard_event。
     */
    private GuardDecision applyLevel(GuardDecision d, String level, String question) {
        if (!d.isHit()) {
            return d;
        }
        GuardDecision result = "log".equalsIgnoreCase(level)
                ? new GuardDecision(GuardDecision.LOG, d.getLayer(), d.getRuleId(), d.getHitText(), d.getChunkId())
                : d;
        eventService.record(result, question);
        qaMetrics.recordGuardBlock(result.getLayer(), result.getRuleId());
        log.info("注入防护命中: layer={} rule={} action={}", result.getLayer(), result.getRuleId(), result.getAction());
        return result;
    }

    /** 取命中点前后一小段原文，便于人工复核（只存一个词看不出上下文） */
    private String snippet(String text, int start, int end) {
        int from = Math.max(0, start - SNIPPET_PAD);
        int to = Math.min(text.length(), end + SNIPPET_PAD);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }

    private String chunkIdOf(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        Object id = doc.getMetadata().get("chunk_id");
        return id == null ? null : String.valueOf(id);
    }
}
