package com.rag.service;

import com.rag.entity.UnresolvedQuestion;
import com.rag.mapper.UnresolvedQuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 未解决问题池（数据飞轮入口）：把「机器人没答上来」的问题沉淀下来。
 *
 * <p>四个来源：兜底文案 / 用户点踩 / 相似度过低被拒答 / 命中注入防护拦截。
 * 池子是闭环的起点 —— 人工据此补知识，补完导出复测清单重跑评估。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnresolvedQuestionService {

    public static final int SOURCE_FALLBACK = 1;
    public static final int SOURCE_DISLIKE = 2;
    public static final int SOURCE_REJECTED = 3;
    public static final int SOURCE_BLOCKED = 4;

    /** 去标点/符号/空白，用于问题归一化 */
    private static final Pattern PUNCT = Pattern.compile("[\\p{P}\\p{S}\\s]");

    private final UnresolvedQuestionMapper unresolvedMapper;

    /**
     * 归一化：全角转半角 + 去标点空白 + 转小写。
     *
     * <p><b>刻意不做同义改写</b>（不把「咋办」并成「怎么办」）——
     * 归一化太激进会把不同问题误合并，池子里就再也看不出「具体是哪一问答不上来」。
     */
    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(question.length());
        for (int i = 0; i < question.length(); i++) {
            char c = question.charAt(i);
            if (c == '　') {
                sb.append(' ');
            } else if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return PUNCT.matcher(sb.toString().toLowerCase()).replaceAll("");
    }

    public static String hash(String question) {
        return DigestUtils.md5DigestAsHex(normalize(question).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 记一条「没答上」。已存在同一问题则累加次数并刷新最近上下文。
     *
     * <p>写池子失败绝不影响问答本身 —— 整体 try/catch 兜住，只打 WARN。
     */
    public void record(String question, int source, Double topScore,
                       Long conversationId, Long messageId, Long userId) {
        if (!StringUtils.hasText(question)) {
            return;
        }
        String h = hash(question);
        BigDecimal score = null;
        if (topScore != null) {
            try {
                score = BigDecimal.valueOf(topScore).setScale(4, RoundingMode.HALF_UP);
            } catch (Exception ignored) {
                // 分数只是参考信息，转换失败就不存
            }
        }
        try {
            // 先尝试原子自增（命中已有行），比「查出来 +1 再写回」少一次竞态
            if (unresolvedMapper.bump(h, source, score, conversationId, messageId, userId) > 0) {
                return;
            }
            UnresolvedQuestion q = new UnresolvedQuestion();
            q.setQuestion(question.length() > 500 ? question.substring(0, 500) : question);
            q.setQuestionHash(h);
            q.setSource(source);
            q.setHitCount(1);
            q.setTopScore(score);
            q.setConversationId(conversationId);
            q.setMessageId(messageId);
            q.setUserId(userId);
            q.setStatus(1);
            unresolvedMapper.insert(q);
        } catch (DuplicateKeyException e) {
            // 并发首插撞唯一键：说明别人刚插进去，回读自增即可
            try {
                unresolvedMapper.bump(h, source, score, conversationId, messageId, userId);
            } catch (Exception ignored) {
                // 竞态下这一次没记上无所谓，下次再问会补上
            }
        } catch (Exception e) {
            log.warn("记录未解决问题失败: {}", e.getMessage());
        }
    }
}
