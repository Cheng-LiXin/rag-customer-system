package com.rag.guard;

import lombok.Getter;

/**
 * 一次注入防护判定的结果。
 *
 * <p>三种动作：
 * <ul>
 *   <li>{@code PASS}  未命中任何规则</li>
 *   <li>{@code BLOCK} 命中且判定为拦截（整轮拒答，不调用大模型 / 丢弃已生成答案）</li>
 *   <li>{@code LOG}   命中但该层配置为 log —— 只落审计、放行（演示「只记录不拦截」用）</li>
 * </ul>
 */
@Getter
public class GuardDecision {

    public static final String PASS = "PASS";
    public static final String BLOCK = "BLOCK";
    public static final String LOG = "LOG";

    /** 动作：PASS / BLOCK / LOG */
    private final String action;

    /** 命中的层：input 输入层 / context 上下文层（间接注入）/ output 输出层 */
    private final String layer;

    /** 命中的规则 ID（便于审计与规则调优） */
    private final String ruleId;

    /** 命中的原文片段（截断后入库，供人工复核） */
    private final String hitText;

    /** 上下文层命中时：携带恶意内容的片段 chunk_id */
    private final String chunkId;

    public GuardDecision(String action, String layer, String ruleId, String hitText, String chunkId) {
        this.action = action;
        this.layer = layer;
        this.ruleId = ruleId;
        this.hitText = hitText;
        this.chunkId = chunkId;
    }

    public static GuardDecision pass() {
        return new GuardDecision(PASS, null, null, null, null);
    }

    /** 命中（需要落审计）：BLOCK 或 LOG */
    public boolean isHit() {
        return BLOCK.equals(action) || LOG.equals(action);
    }

    /** 需要拦截 */
    public boolean isBlocked() {
        return BLOCK.equals(action);
    }
}
