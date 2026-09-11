package com.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.entity.KnowledgeCategory;
import com.rag.entity.Message;
import com.rag.mapper.KnowledgeCategoryMapper;
import com.rag.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别（F03）：基于关键词规则的轻量级分类，辅助 RAG 检索与运营统计。
 *
 * <p><b>评分式规则分类（比“首个命中即返回”更细更准）</b>：每个被命中的关键词
 * 给它所属意图 +1 分；全部关键词扫描完后取分最高者。平票（同分）时再比
 * “该意图命中最长关键词的长度”（越具体越优先，如「一卡通」优于泛词「校园」、
 * 「转专业」优于泛词「专业」），仍并列则按 {@link #INTENT_ORDER} 的规范顺序取先者。
 * 这样“多主题混问 / 专词与泛词同现”都能正确分流，而不是被先出现的泛词抢走。</p>
 *
 * <p>分类命名尽量与知识库分类一致（招生政策/教务服务/学工服务/学校概况），
 * 另加「专业设置」「校园生活」两个高频运营主题；无法识别时归入「其他」。</p>
 */
@Service
@RequiredArgsConstructor
public class IntentService {

    /** 规范意图顺序：统计页展示顺序，同时作为评分平票的最终兜底顺序 */
    private static final List<String> INTENT_ORDER = List.of(
            "招生政策", "教务服务", "学工服务", "校园生活", "专业设置", "学校概况");

    /** 无法识别任何意图时的兜底分类 */
    public static final String OTHER = "其他";

    /** 关键词 → 意图（HashMap 无序即可；分流由评分 + 最长关键词 + INTENT_ORDER 保证） */
    private static final Map<String, String> KEYWORDS = new HashMap<>();

    static {
        // —— 招生政策：招考录取 / 分数位次 / 志愿章程 ——
        addGroup("招生政策",
                "招生", "录取", "分数线", "省控线", "投档", "位次", "报考",
                "志愿", "章程", "简章", "扩招", "调档", "通知书");
        // —— 教务服务：在校培养环节（转专业/成绩考试/学分学位/学籍毕业）——
        addGroup("教务服务",
                "转专业", "辅修", "双学位", "选课", "课表", "校历", "成绩", "绩点", "挂科",
                "补考", "重修", "缓考", "考试", "成绩单", "学分", "学位", "学位证",
                "毕业证", "毕业论文", "实习", "培养方案", "学籍", "休学", "复学",
                "退学", "转学", "学生证", "学号");
        // —— 学工服务：入学报到与在校学生事务（证件档案/一卡通/缴费/奖助）——
        addGroup("学工服务",
                "报到", "入学", "迎新", "新生", "军训", "档案", "户口", "银行卡", "银行",
                "学费", "缴费", "缴纳", "一卡通", "校园卡", "今日校园", "奖学金",
                "助学金", "助学贷款", "资助", "勤工", "医保", "开学", "接站", "补助");
        // —— 校园生活：吃住用玩与公共设施 ——
        addGroup("校园生活",
                "食堂", "宿舍", "住宿", "寝室", "快递", "取件", "超市", "浴室", "澡堂",
                "社团", "校园", "生活", "校园网", "无线网", "上网", "图书馆", "借书",
                "自习", "运动", "操场", "自行车", "电动车", "活动");
        // —— 专业设置：专业学科选择（特色/一流/目录/就业相关简介词）——
        addGroup("专业设置",
                "专业", "学科", "王牌", "特色专业", "优势专业", "一流专业", "一流本科",
                "专业目录", "专业介绍", "热门专业", "专业排名", "选专业");
        // —— 学校概况：校史简介/学院院系/规模区位 ——
        addGroup("学校概况",
                "简介", "学校", "历史", "沿革", "校史", "校训", "校歌", "校区", "学院",
                "院系", "地址", "面积", "规模", "在校生", "教职工", "师资", "排名", "概况");
    }

    private static void addGroup(String intent, String... keywords) {
        for (String kw : keywords) {
            KEYWORDS.put(kw, intent);
        }
    }

    private final KnowledgeCategoryMapper categoryMapper;
    private final MessageMapper messageMapper;

    /**
     * 对问题文本进行意图分类（评分式规则，见类注释），返回意图名；无法识别返回「其他」。
     */
    public String classify(String question) {
        if (!StringUtils.hasText(question)) {
            return OTHER;
        }
        // 每意图：命中关键词个数 与 命中最长关键词长度
        Map<String, Integer> count = new HashMap<>();
        Map<String, Integer> maxLen = new HashMap<>();
        for (Map.Entry<String, String> e : KEYWORDS.entrySet()) {
            if (question.contains(e.getKey())) {
                String intent = e.getValue();
                count.merge(intent, 1, Integer::sum);
                maxLen.merge(intent, e.getKey().length(), Math::max);
            }
        }
        if (count.isEmpty()) {
            return OTHER;
        }
        String best = null;
        int bestCount = 0;
        int bestLen = 0;
        for (String intent : INTENT_ORDER) {
            Integer c = count.get(intent);
            if (c == null) {
                continue;
            }
            int len = maxLen.getOrDefault(intent, 0);
            if (best == null || c > bestCount || (c == bestCount && len > bestLen)) {
                best = intent;
                bestCount = c;
                bestLen = len;
            }
        }
        return best == null ? OTHER : best;
    }

    /** 规范意图顺序（统计用，去掉「其他」） */
    public List<String> listIntents() {
        return new ArrayList<>(INTENT_ORDER);
    }

    /**
     * 返回存在的分类（校验分类名称是否在知识分类表中；专业设置/校园生活无对应分类行则返回 null）
     */
    public KnowledgeCategory resolveCategory(String question) {
        String name = classify(question);
        return categoryMapper.selectOne(new LambdaQueryWrapper<KnowledgeCategory>()
                .eq(KnowledgeCategory::getName, name)
                .last("LIMIT 1"));
    }

    /**
     * 批量重算历史用户消息的意图（规则升级后回填，保持「用户意图分布」口径统一）。
     * 只更新发生变化的行；返回扫描数/更新数。
     */
    public Map<String, Object> reclassifyAllMessages() {
        List<Message> userMessages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSenderType, "USER")
                .isNotNull(Message::getContent)
                .ne(Message::getContent, "")
                .orderByAsc(Message::getId));
        int scanned = 0;
        int updated = 0;
        for (Message m : userMessages) {
            String label = classify(m.getContent());
            if (!label.equals(m.getIntentCategory())) {
                m.setIntentCategory(label);
                messageMapper.updateById(m);
                updated++;
            }
            scanned++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", scanned);
        result.put("updated", updated);
        return result;
    }

    public List<KnowledgeCategory> listCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<KnowledgeCategory>()
                .orderByAsc(KnowledgeCategory::getSortOrder));
    }
}
