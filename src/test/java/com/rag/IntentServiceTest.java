package com.rag;

import com.rag.service.IntentService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图规则分类的映射回归测试（纯 JUnit，不启动 Spring 上下文）。
 * 覆盖：六类真实问法 + 「其他」兜底 + 重叠词/泛词抢答（评分式：长词优先、计数优先）。
 */
class IntentServiceTest {

    private final IntentService svc = new IntentService(null, null);

    private void assertIntent(String question, String expected) {
        assertThat(svc.classify(question))
                .as("问题：%s", question)
                .isEqualTo(expected);
    }

    @Test
    void 招生政策() {
        assertIntent("燕山大学的招生政策是什么", "招生政策");
        assertIntent("2026年录取分数线是多少", "招生政策");
        assertIntent("报考燕山大学需要什么条件", "招生政策");
        assertIntent("河北省投档线和位次怎么查", "招生政策");
        assertIntent("招生简章和报考志愿哪里看", "招生政策");
    }

    @Test
    void 教务服务() {
        assertIntent("燕山大学是否可以转专业", "教务服务");
        assertIntent("挂科成绩怎么记，会不会影响绩点", "教务服务");
        assertIntent("怎么申请缓考、补考和重修", "教务服务");
        assertIntent("学生证丢失怎么补办", "教务服务");
        assertIntent("选课系统和课表从哪里进", "教务服务");
        assertIntent("休学复学和退学的流程是什么", "教务服务");
    }

    @Test
    void 学工服务() {
        assertIntent("2026本科新生入学须知", "学工服务");
        assertIntent("新生报到需要带哪些证件和档案", "学工服务");
        assertIntent("如何办理校园一卡通", "学工服务");
        assertIntent("银行卡需要自己提前办吗", "学工服务");
        assertIntent("学费怎么缴纳，可以缓交吗", "学工服务");
        assertIntent("奖学金和助学金有哪些政策", "学工服务");
        assertIntent("今日校园怎么用，军训多久", "学工服务");
        // 真实历史问法：把「银行卡」打成「银行可」时裸词「银行」仍能归学工
        assertIntent("是否需要自己提前办理银行可", "学工服务");
    }

    @Test
    void 校园生活() {
        assertIntent("学校有哪些食堂", "校园生活");
        assertIntent("宿舍条件怎么样，能选几人间", "校园生活");
        assertIntent("校园里可以骑电动自行车吗", "校园生活");
        assertIntent("快递去哪取，有代收点吗", "校园生活");
        assertIntent("图书馆借书和自习座位怎么弄", "校园生活");
        assertIntent("社团有哪些，怎么加入", "校园生活");
    }

    @Test
    void 专业设置() {
        assertIntent("学校有哪些特色专业", "专业设置");
        assertIntent("燕山大学的王牌专业有哪些", "专业设置");
        assertIntent("一流专业和优势专业名单", "专业设置");
        assertIntent("燕山大学有哪些学院和专业", "专业设置");
    }

    @Test
    void 学校概况() {
        assertIntent("燕山大学都有哪些学院", "学校概况");
        assertIntent("学校的历史沿革和校训", "学校概况");
        assertIntent("燕山大学有几个校区，地址在哪", "学校概况");
        assertIntent("学校的规模、在校生和师资怎么样", "学校概况");
    }

    @Test
    void 兜底其他() {
        assertIntent("你好", "其他");
        assertIntent("谢谢", "其他");
        assertIntent("哈哈哈哈", "其他");
        assertIntent("1213", "其他");
        assertIntent("", "其他");
        assertIntent(null, "其他");
    }

    @Test
    void 泛词不抢答_长词优先() {
        // 「转专业」压过泛词「专业」；「选专业」也是 3 字，比不过转专业语境、且规范顺序教务在前
        assertIntent("怎么转专业", "教务服务");
        // 「一卡通/今日校园」压过泛词「校园」
        assertIntent("校园一卡通丢了怎么补", "学工服务");
        assertIntent("今日校园和校园卡的区别", "学工服务");
        // 「特色专业/学院+专业」多词计数压过泛词「学校/学院」
        assertIntent("学校有哪些特色专业和一流专业", "专业设置");
        // 「学校」vs「食堂」平票时，校园生活规范序在概况前
        assertIntent("学校食堂几点开饭", "校园生活");
        // 多主题：教务词多 → 教务
        assertIntent("开学后怎么选课和查成绩", "教务服务");
        // 多主题：学工词多 → 学工
        assertIntent("学费多少，能申请助学贷款吗", "学工服务");
        // 「学费」学工与「入学须知」学工同向
        assertIntent("新生学费和入学须知", "学工服务");
    }
}
