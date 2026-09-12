package com.rag.controller;

import com.rag.annotation.OperationLog;
import com.rag.dto.CategoryRequest;
import com.rag.dto.DocumentImportRequest;
import com.rag.dto.KnowledgeChunkRequest;
import com.rag.dto.PageResult;
import com.rag.entity.KnowledgeCategory;
import com.rag.entity.KnowledgeChunk;
import com.rag.retrieve.RetrievalResult;
import com.rag.retrieve.Retriever;
import com.rag.retrieve.RetrieverFactory;
import com.rag.service.DocumentChunker;
import com.rag.service.DocumentParser;
import com.rag.service.KnowledgeService;
import com.rag.service.SegmentTitleGenerator;
import com.rag.util.Result;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理（F02）：仅管理员可用
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final DocumentParser documentParser;
    private final DocumentChunker documentChunker;
    private final SegmentTitleGenerator segmentTitleGenerator;
    private final RetrieverFactory retrieverFactory;

    // ==================== 检索预览（评估用） ====================

    /**
     * 检索预览：直接返回指定检索模式的 top-K 结果，供离线评估量 Recall@K / MRR。
     *
     * <p><b>为什么不复用 {@code /api/chat/ask} 的 sources</b>：那是**喂给答案生成器**的片段
     * （{@code rag.top-k=3}），而 Recall@5 是**检索层**指标。两者刻意解耦 ——
     * 为凑指标把答案路径的 top-k 提到 5，会把多余片段塞进抽取池、污染已经调好的答案质量。
     *
     * <p>本接口在类级 {@code @PreAuthorize("hasRole('ADMIN')")} 之下，不对公众开放。
     *
     * @param mode vector / bm25 / rrf / rerank；不传则用当前生效模式
     */
    @GetMapping("/chunk/retrieve-preview")
    public Result<List<Map<String, Object>>> retrievePreview(@RequestParam("q") String query,
                                                             @RequestParam(defaultValue = "5") int topK,
                                                             @RequestParam(required = false) String mode) {
        int k = Math.min(Math.max(topK, 1), 50);
        Retriever retriever = (mode == null || mode.isBlank())
                ? retrieverFactory.current()
                : retrieverFactory.byMode(mode);
        RetrievalResult result = retriever.retrieve(query, k);

        List<Map<String, Object>> out = new ArrayList<>();
        int index = 1;
        for (Document doc : result.getDocuments()) {
            Map<String, Object> item = new HashMap<>();
            item.put("index", index++);
            item.put("chunkId", doc.getMetadata() == null ? null : doc.getMetadata().get("chunk_id"));
            item.put("title", doc.getMetadata() == null ? null : doc.getMetadata().get("title"));
            item.put("category", doc.getMetadata() == null ? null : doc.getMetadata().get("category"));
            item.put("score", RetrievalResult.similarityOf(doc));
            item.put("mode", result.getMode());
            String content = doc.getContent() == null ? "" : doc.getContent();
            item.put("contentPreview", content.length() <= 120 ? content : content.substring(0, 120));
            out.add(item);
        }
        return Result.success(out);
    }

    // ==================== 知识片段 ====================

    @GetMapping("/chunk/page")
    public Result<PageResult<KnowledgeChunk>> pageChunks(@RequestParam(defaultValue = "1") long pageNum,
                                                         @RequestParam(defaultValue = "10") long pageSize,
                                                         @RequestParam(required = false) Long categoryId,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) Integer status) {
        return Result.success(knowledgeService.pageChunks(pageNum, pageSize, categoryId, keyword, status));
    }

    @OperationLog(module = "知识库", action = "新增知识片段")
    @PostMapping("/chunk")
    public Result<KnowledgeChunk> add(@RequestBody @Valid KnowledgeChunkRequest request) {
        return Result.success(knowledgeService.addChunk(request));
    }

    @OperationLog(module = "知识库", action = "编辑知识片段")
    @PutMapping("/chunk/{id}")
    public Result<KnowledgeChunk> update(@PathVariable Long id, @RequestBody @Valid KnowledgeChunkRequest request) {
        return Result.success(knowledgeService.updateChunk(id, request));
    }

    @OperationLog(module = "知识库", action = "删除知识片段")
    @DeleteMapping("/chunk/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.deleteChunk(id);
        return Result.success();
    }

    @OperationLog(module = "知识库", action = "重建向量")
    @PostMapping("/chunk/{id}/reindex")
    public Result<KnowledgeChunk> reindex(@PathVariable Long id) {
        return Result.success(knowledgeService.reindexChunk(id));
    }

    /** 导出全部知识片段为 CSV（Excel 可直接打开） */
    @GetMapping("/chunk/export")
    public void export(HttpServletResponse response) throws IOException {
        String csv = knowledgeService.toCsv(knowledgeService.listAllChunks());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=knowledge_chunks.csv");
        response.getWriter().write("﻿" + csv);
    }

    /** 从 CSV 批量导入知识片段（自动向量化） */
    @OperationLog(module = "知识库", action = "批量导入知识片段")
    @PostMapping("/chunk/import")
    public Result<Map<String, Object>> importChunks(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return Result.error("文件为空");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return Result.success(knowledgeService.importCsv(content));
    }

    /** 解析上传文档并自动切块（仅预览，不落库） */
    @OperationLog(module = "知识库", action = "解析上传文档")
    @PostMapping("/document/parse")
    public Result<Map<String, Object>> parseDocument(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return Result.error("文件为空");
        }
        String fileName = file.getOriginalFilename();
        if (!DocumentParser.isSupported(fileName)) {
            return Result.error("暂不支持的文件类型，仅支持 .txt / .md / .docx");
        }
        String text = documentParser.parse(fileName, file.getBytes());
        List<DocumentChunker.Chunk> chunks = documentChunker.chunk(text, baseName(fileName));
        // 每个分段由 DeepSeek 总结出 6~14 字标题（失败回退原标题/段首短句），供预览确认时展示与修改
        List<String> titles = segmentTitleGenerator.summarize(chunks);

        List<Map<String, Object>> chunkList = new ArrayList<>(chunks.size());
        int totalChars = 0;
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.Chunk chunk = chunks.get(i);
            Map<String, Object> item = new HashMap<>();
            item.put("title", titles.get(i));
            item.put("content", chunk.content());
            item.put("charCount", chunk.content().length());
            chunkList.add(item);
            totalChars += chunk.content().length();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("fileName", fileName);
        result.put("total", chunks.size());
        result.put("totalChars", totalChars);
        result.put("chunks", chunkList);
        return Result.success(result);
    }

    /** 确认导入预览中选中的切块（落库 + 批量向量化） */
    @OperationLog(module = "知识库", action = "文档导入知识片段")
    @PostMapping("/document/import")
    public Result<Map<String, Object>> importDocument(@RequestBody @Valid DocumentImportRequest request) {
        return Result.success(knowledgeService.importDocumentChunks(request));
    }

    /** 取不含扩展名的文件名（切块兜底标题用）。 */
    private String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "文档片段";
        }
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    // ==================== 分类管理 ====================

    @GetMapping("/category/list")
    public Result<List<KnowledgeCategory>> listCategories() {
        return Result.success(knowledgeService.listCategories());
    }

    @OperationLog(module = "知识库", action = "新增分类")
    @PostMapping("/category")
    public Result<KnowledgeCategory> addCategory(@RequestBody @Valid CategoryRequest request) {
        return Result.success(knowledgeService.addCategory(request));
    }

    @OperationLog(module = "知识库", action = "编辑分类")
    @PutMapping("/category/{id}")
    public Result<KnowledgeCategory> updateCategory(@PathVariable Long id, @RequestBody @Valid CategoryRequest request) {
        return Result.success(knowledgeService.updateCategory(id, request));
    }

    @OperationLog(module = "知识库", action = "删除分类")
    @DeleteMapping("/category/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        knowledgeService.deleteCategory(id);
        return Result.success();
    }
}
