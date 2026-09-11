package com.rag.controller;

import com.rag.entity.KnowledgeCategory;
import com.rag.service.IntentService;
import com.rag.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图识别（F03）
 */
@RestController
@RequestMapping("/api/intent")
@RequiredArgsConstructor
public class IntentController {

    private final IntentService intentService;

    @GetMapping("/classify")
    public Result<Map<String, Object>> classify(@RequestParam String question) {
        String categoryName = intentService.classify(question);
        KnowledgeCategory category = intentService.resolveCategory(question);
        Map<String, Object> result = new HashMap<>();
        result.put("categoryName", categoryName);
        result.put("categoryId", category == null ? null : category.getId());
        return Result.success(result);
    }

    /** 批量重算历史用户消息的意图（规则升级后回填，使「意图分布」口径与新分类器一致） */
    @PostMapping("/reclassify")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> reclassify() {
        return Result.success(intentService.reclassifyAllMessages());
    }
}
