package com.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.annotation.OperationLog;
import com.rag.dto.PageResult;
import com.rag.dto.UnresolvedHandleRequest;
import com.rag.entity.SysUser;
import com.rag.entity.UnresolvedQuestion;
import com.rag.mapper.UnresolvedQuestionMapper;
import com.rag.service.UserService;
import com.rag.util.Result;
import com.rag.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 未解决问题池（数据飞轮）：管理端查看与处理。
 *
 * <p>闭环：机器人答不上来 → 池子里出现 → 管理员补知识 → 导出复测清单重跑评估 → 标记已补知识。
 * 「重评估」刻意做成**导出清单 + 由脚本跑**（而不是后端去调 Python），
 * 避免线上服务依赖 Python 运行时。
 */
@RestController
@RequestMapping("/api/admin/unresolved")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UnresolvedController {

    private final UnresolvedQuestionMapper unresolvedMapper;
    private final UserService userService;

    @GetMapping("/page")
    public Result<PageResult<UnresolvedQuestion>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                       @RequestParam(defaultValue = "10") long pageSize,
                                                       @RequestParam(required = false) Integer status,
                                                       @RequestParam(required = false) Integer source,
                                                       @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<UnresolvedQuestion> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(UnresolvedQuestion::getStatus, status);
        }
        if (source != null) {
            wrapper.eq(UnresolvedQuestion::getSource, source);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(UnresolvedQuestion::getQuestion, keyword);
        }
        // 默认按「被问得最多的」排前面 —— 池子的价值就在于先补高频问题
        wrapper.orderByDesc(UnresolvedQuestion::getHitCount).orderByDesc(UnresolvedQuestion::getId);
        Page<UnresolvedQuestion> page = unresolvedMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.success(PageResult.of(page));
    }

    /** 按来源/状态汇总，前端画卡片用 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        List<UnresolvedQuestion> all = unresolvedMapper.selectList(null);
        Map<Integer, Integer> bySource = new LinkedHashMap<>();
        Map<Integer, Integer> byStatus = new LinkedHashMap<>();
        int total = 0;
        int pending = 0;
        for (UnresolvedQuestion q : all) {
            total++;
            bySource.merge(q.getSource() == null ? 0 : q.getSource(), 1, Integer::sum);
            int st = q.getStatus() == null ? 1 : q.getStatus();
            byStatus.merge(st, 1, Integer::sum);
            if (st == 1) {
                pending++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("pending", pending);
        data.put("bySource", bySource);
        data.put("byStatus", byStatus);
        return Result.success(data);
    }

    /** 标记处理结果（已补知识需带上补充的知识片段 ID，形成闭环） */
    @OperationLog(module = "未解决问题", action = "处理未解决问题")
    @PostMapping("/{id}/handle")
    public Result<Void> handle(@PathVariable Long id, @RequestBody UnresolvedHandleRequest request) {
        UnresolvedQuestion q = unresolvedMapper.selectById(id);
        if (q == null) {
            return Result.error("记录不存在");
        }
        if (request.getStatus() != null) {
            q.setStatus(request.getStatus());
        }
        if (request.getKnowledgeChunkId() != null) {
            q.setKnowledgeChunkId(request.getKnowledgeChunkId());
        }
        if (request.getRemark() != null) {
            q.setRemark(request.getRemark().length() > 500
                    ? request.getRemark().substring(0, 500) : request.getRemark());
        }
        q.setHandleTime(LocalDateTime.now());
        String username = SecurityUtil.currentUsername();
        if (username != null) {
            SysUser user = userService.getByUsername(username);
            if (user != null) {
                q.setHandlerId(user.getId());
            }
        }
        unresolvedMapper.updateById(q);
        return Result.success();
    }
}
