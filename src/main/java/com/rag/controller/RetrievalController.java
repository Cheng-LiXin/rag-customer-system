package com.rag.controller;

import com.rag.annotation.OperationLog;
import com.rag.retrieve.ChunkCorpusService;
import com.rag.retrieve.RetrieverFactory;
import com.rag.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索策略管理（ADMIN）：当前模式 / 运行时切换 / 语料重建。
 *
 * <p>运行时切换的价值在于答辩演示与对比实验：不必改配置重启就能在
 * vector / bm25 / rrf / rerank 之间切换。切换动作由 {@code @OperationLog} 落审计。
 */
@RestController
@RequestMapping("/api/admin/retrieval")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RetrievalController {

    private final RetrieverFactory retrieverFactory;
    private final ChunkCorpusService corpusService;

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", retrieverFactory.activeMode());
        data.put("source", retrieverFactory.source());
        data.put("available", retrieverFactory.modes());
        // 语料条数：与 knowledge_chunk 的 COUNT(*) WHERE deleted=0 AND status=1 对拍，
        // 不一致说明 BM25 索引与库不同步（懒刷新最多滞后 rebuild-ms）
        data.put("corpusSize", corpusService.size());
        return Result.success(data);
    }

    /** 切换检索模式；mode 传空则清除运行时覆盖、回到配置打底值 */
    @OperationLog(module = "检索策略", action = "切换检索模式")
    @PostMapping("/mode")
    public Result<Map<String, Object>> setMode(@RequestBody Map<String, String> body) {
        String mode = body == null ? null : body.get("mode");
        if (mode == null || mode.isBlank()) {
            retrieverFactory.clearRuntimeMode();
        } else {
            retrieverFactory.setRuntimeMode(mode);
        }
        return status();
    }

    /** 立即重建 BM25 语料（知识库刚改完想马上生效时用） */
    @OperationLog(module = "检索策略", action = "重建检索语料")
    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuild() {
        corpusService.rebuild();
        return status();
    }
}
