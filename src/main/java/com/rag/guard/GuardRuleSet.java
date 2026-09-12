package com.rag.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 注入防护规则表。
 *
 * <p><b>设计纪律：规则一律是「意图词 + 对象词」的共现匹配，绝不做单词匹配。</b>
 * 知识库正文里「保证」「全额」「承诺」都是合法词（例如法规原文「学校保证住宿条件…」），
 * 单词匹配必然误杀正常回答 —— 这是本层最容易出事的地方，误杀回归在
 * {@code tools/guard/run_guard_test.py} 的对照集与 150 题正常集上各有断言。
 *
 * <p>正则里的 {@code [^。！？]{0,n}} 是「同句近距离」约束：跨句匹配容易把互不相关的
 * 两个词凑成一次命中。
 */
public final class GuardRuleSet {

    private GuardRuleSet() {
    }

    /** 一条规则：ID（入审计）+ 正则 */
    public record Rule(String id, Pattern pattern) {
    }

    /**
     * L1 输入层：直接注入 —— 用户问题本身在试图改写系统指令或套取提示词。
     * 这一层在检索之前执行，命中即拒答，不检索、不调模型、不入缓存。
     */
    public static final List<Rule> INPUT_DENY = List.of(
            new Rule("inject-ignore-rules", Pattern.compile(
                    "(忽略|无视|忘掉|忘记|绕过|跳过|不要管|抛开|推翻|丢弃)"
                            + "[^。！？]{0,10}(指令|规则|设定|限制|要求|提示词|以上|之前|前面|上述)")),
            new Rule("inject-leak-prompt", Pattern.compile(
                    "(输出|显示|告诉我|重复|复述|打印|朗读|泄露|说出)"
                            + "[^。！？]{0,10}(系统提示词|系统指令|提示词|初始指令|你的设定|你的规则|prompt)")),
            new Rule("inject-jailbreak", Pattern.compile(
                    "(开发者模式|越狱|jailbreak|DAN模式|不受任何限制|没有任何限制|解除所有限制)")),
            new Rule("inject-role-override", Pattern.compile(
                    "(从现在起|现在开始|从现在开始|接下来你|以后你)"
                            + "[^。！？]{0,8}(你|您)?[^。！？]{0,6}(是|就是|要|必须|扮演|充当)"))
    );

    /**
     * L2 上下文层：间接注入 —— 检索到的知识片段里被埋了「指挥助手做事」的句子。
     * 这一层在检索之后、生成之前执行，命中即整轮拒答并记录是哪个 chunk 被污染。
     *
     * <p>注意与 L3 的分工：本层只抓<b>指令式</b>注入（文本在命令助手），
     * 而<b>事实式</b>被污染的承诺（如「本校承诺可申请全额退款」原样写进资料）
     * 由 L3 输出层兜住 —— 输出层与检索路径解耦，是最后一道闸。
     */
    public static final List<Rule> CONTEXT_DENY = List.of(
            new Rule("ctx-ignore-prior", Pattern.compile(
                    "(忽略|无视|忘记|不要理会)"
                            + "[^。！？]{0,10}(以上|之前|前面|上述|先前)[^。！？]{0,8}(指令|规则|要求|设定|内容)")),
            new Rule("ctx-system-prompt", Pattern.compile(
                    "(系统指令|系统提示词|你现在的角色是|你的角色是|你现在是|你的身份是)")),
            new Rule("ctx-instruct-user", Pattern.compile(
                    "(对用户说|回复用户|告诉用户|请务必|你必须|一定要|务必让用户|需要让用户)"
                            + "[^。！？]{0,16}(退款|赔偿|免单|全额|补偿|中签|录取|入学)"))
    );

    /**
     * L3 输出层：承诺检测 —— 兜住「事实式」被污染内容。
     *
     * <p>这是最关键的一层，理由：本项目是抽取式回答，答案逐字来自原文，
     * 所以间接注入的最终表现就是「答案里原样出现了承诺句」。而 L2 依赖
     * 「毒片段被检索到」这一前提（批次 D 的 BM25/RRF/rerank 都可能把它带进来），
     * 输出层因此必须与检索路径解耦、独立成立。
     *
     * <p>对象词只保留「用户受益类」承诺（退款/赔偿/录取/毕业…），刻意不含「通过/入学」，
     * 因为法规原文「以确保通过考核」「保证入学资格审核规范」是合法的。
     */
    public static final List<Rule> OUTPUT_DENY = List.of(
            new Rule("out-refund", Pattern.compile(
                    "(全额|全部|无条件|马上|立即|立刻|双倍|三倍)"
                            + "[^。！？]{0,8}(退款|退货|赔偿|补偿|免单)")),
            new Rule("out-promise", Pattern.compile(
                    "(保证|承诺|担保|确保)"
                            + "[^。！？]{0,10}(退款|赔偿|免单|中签|录取|毕业|包过)"))
    );
}
