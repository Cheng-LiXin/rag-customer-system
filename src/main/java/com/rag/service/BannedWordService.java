package com.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.entity.BannedWord;
import com.rag.mapper.BannedWordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 违禁词校验（昵称/个人资料提交；后端强制，词表由 admin 后台维护）。
 */
@Service
@RequiredArgsConstructor
public class BannedWordService {

    private final BannedWordMapper bannedWordMapper;

    /**
     * 返回文本中命中的第一个启用违禁词；无命中或文本为空返回 null。
     * 词表规模小、校验频率低（注册/改昵称时才触发），直接查库即可，无需缓存。
     */
    public String findBanned(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        List<BannedWord> active = bannedWordMapper.selectList(
                new LambdaQueryWrapper<BannedWord>().eq(BannedWord::getStatus, 1));
        for (BannedWord w : active) {
            if (w.getWord() != null && text.contains(w.getWord())) {
                return w.getWord();
            }
        }
        return null;
    }

    /**
     * 文本命中违禁词时返回统一格式的友好错误文案；未命中/空文本返回 null。
     * 各资料写入点（注册/改昵称/改邮箱/admin 建改号）共用同一文案，避免三处拼写漂移。
     *
     * @param text       待校验文本（如昵称、邮箱）
     * @param fieldLabel 字段名，用于提示，如 "昵称"/"邮箱"
     */
    public String bannedMessage(String text, String fieldLabel) {
        String word = findBanned(text);
        return word == null ? null : fieldLabel + "包含违禁词「" + word + "」，请修改";
    }
}
