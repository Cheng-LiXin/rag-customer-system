package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.annotation.OperationLog;
import com.rag.dto.PageResult;
import com.rag.entity.BannedWord;
import com.rag.mapper.BannedWordMapper;
import com.rag.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 违禁词管理（昵称/个人资料屏蔽词表）：仅管理员
 */
@RestController
@RequestMapping("/api/admin/banned-word")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BannedWordController {

    private final BannedWordMapper bannedWordMapper;

    @GetMapping("/page")
    public Result<PageResult<BannedWord>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<BannedWord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(BannedWord::getWord, keyword);
        }
        wrapper.orderByDesc(BannedWord::getId);
        Page<BannedWord> page = bannedWordMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.success(PageResult.of(page));
    }

    @OperationLog(module = "违禁词管理", action = "新增违禁词")
    @PostMapping
    public Result<Void> create(@RequestBody BannedWord body) {
        if (!StringUtils.hasText(body.getWord())) {
            return Result.error("违禁词不能为空");
        }
        String word = body.getWord().trim();
        long exists = bannedWordMapper.selectCount(
                new LambdaQueryWrapper<BannedWord>().eq(BannedWord::getWord, word));
        if (exists > 0) {
            return Result.error("违禁词已存在");
        }
        BannedWord bw = new BannedWord();
        bw.setWord(word);
        bw.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        bannedWordMapper.insert(bw);
        return Result.success();
    }

    @OperationLog(module = "违禁词管理", action = "编辑违禁词")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody BannedWord body) {
        BannedWord bw = bannedWordMapper.selectById(id);
        if (bw == null) {
            return Result.error("违禁词不存在");
        }
        if (StringUtils.hasText(body.getWord())) {
            String word = body.getWord().trim();
            long dup = bannedWordMapper.selectCount(new LambdaQueryWrapper<BannedWord>()
                    .eq(BannedWord::getWord, word)
                    .ne(BannedWord::getId, id));
            if (dup > 0) {
                return Result.error("违禁词已存在");
            }
            bw.setWord(word);
        }
        if (body.getStatus() != null) {
            bw.setStatus(body.getStatus());
        }
        bannedWordMapper.updateById(bw);
        return Result.success();
    }

    @OperationLog(module = "违禁词管理", action = "删除违禁词")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        bannedWordMapper.deleteById(id);
        return Result.success();
    }
}
