package com.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.dto.CategoryRequest;
import com.rag.dto.DocumentImportRequest;
import com.rag.dto.KnowledgeChunkRequest;
import com.rag.dto.PageResult;
import com.rag.entity.KnowledgeCategory;
import com.rag.entity.KnowledgeChunk;
import com.rag.exception.BizException;
import com.rag.mapper.KnowledgeCategoryMapper;
import com.rag.mapper.KnowledgeChunkMapper;
import com.rag.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库管理服务（F02）：写入 MySQL + 向量化同步至 PGVector
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final VectorStore vectorStore;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeCategoryMapper knowledgeCategoryMapper;

    @Override
    public PageResult<KnowledgeChunk> pageChunks(long pageNum, long pageSize, Long categoryId,
                                                 String keyword, Integer status) {
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
        if (categoryId != null) {
            wrapper.eq(KnowledgeChunk::getCategoryId, categoryId);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(KnowledgeChunk::getTitle, keyword)
                    .or().like(KnowledgeChunk::getContent, keyword));
        }
        if (status != null) {
            wrapper.eq(KnowledgeChunk::getStatus, status);
        }
        wrapper.orderByDesc(KnowledgeChunk::getId);
        Page<KnowledgeChunk> page = knowledgeChunkMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @Override
    public KnowledgeChunk addChunk(KnowledgeChunkRequest request) {
        KnowledgeChunk chunk = insertChunk(request, "MANUAL", 0);
        embedAndStore(chunk);
        return chunk;
    }

    /** 落库（不含向量化），供单条新增与文档导入共用。 */
    private KnowledgeChunk insertChunk(KnowledgeChunkRequest request, String sourceType, int chunkIndex) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        applyRequest(chunk, request);
        chunk.setSourceType(sourceType);
        chunk.setChunkIndex(chunkIndex);
        chunk.setVectorStatus(0);
        chunk.setHitCount(0);
        chunk.setStatus(1);
        knowledgeChunkMapper.insert(chunk);
        return chunk;
    }

    @Override
    public KnowledgeChunk updateChunk(Long id, KnowledgeChunkRequest request) {
        KnowledgeChunk chunk = requireChunk(id);
        removeVector(chunk);

        applyRequest(chunk, request);
        chunk.setVectorId(null);
        chunk.setVectorStatus(0);
        knowledgeChunkMapper.updateById(chunk);

        embedAndStore(chunk);
        return chunk;
    }

    @Override
    public void deleteChunk(Long id) {
        KnowledgeChunk chunk = requireChunk(id);
        removeVector(chunk);
        knowledgeChunkMapper.deleteById(id);
    }

    @Override
    public KnowledgeChunk reindexChunk(Long id) {
        KnowledgeChunk chunk = requireChunk(id);
        removeVector(chunk);
        chunk.setVectorId(null);
        chunk.setVectorStatus(0);
        knowledgeChunkMapper.updateById(chunk);

        embedAndStore(chunk);
        return chunk;
    }

    // ==================== 批量导入/导出 ====================

    @Override
    public List<KnowledgeChunk> listAllChunks() {
        return knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .orderByAsc(KnowledgeChunk::getId));
    }

    @Override
    public String toCsv(List<KnowledgeChunk> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题,分类ID,内容,关键词,来源链接,源标题\n");
        for (KnowledgeChunk c : list) {
            sb.append(csvEscape(c.getTitle())).append(',')
                    .append(c.getCategoryId()).append(',')
                    .append(csvEscape(c.getContent())).append(',')
                    .append(csvEscape(c.getKeywords())).append(',')
                    .append(csvEscape(c.getSourceUrl())).append(',')
                    .append(csvEscape(c.getSourceTitle())).append('\n');
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> importCsv(String csv) {
        List<List<String>> rows = parseCsv(csv);
        int success = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) { // 跳过表头
            List<String> row = rows.get(i);
            if (row.size() < 3) {
                fail++;
                errors.add("第 " + (i + 1) + " 行列数不足");
                continue;
            }
            try {
                KnowledgeChunkRequest request = new KnowledgeChunkRequest();
                request.setTitle(row.get(0));
                request.setCategoryId(parseLong(row.get(1)));
                request.setContent(row.get(2));
                request.setKeywords(row.size() > 3 ? emptyToNull(row.get(3)) : null);
                request.setSourceUrl(row.size() > 4 ? emptyToNull(row.get(4)) : null);
                request.setSourceTitle(row.size() > 5 ? emptyToNull(row.get(5)) : null);
                addChunk(request);
                success++;
            } catch (Exception e) {
                fail++;
                errors.add("第 " + (i + 1) + " 行: " + e.getMessage());
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("fail", fail);
        result.put("errors", errors);
        return result;
    }

    @Override
    public Map<String, Object> importDocumentChunks(DocumentImportRequest request) {
        List<DocumentImportRequest.Item> items = request.getChunks();
        int success = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();
        List<KnowledgeChunk> inserted = new ArrayList<>();

        String sourceTitle = truncate(request.getSourceTitle(), 200);
        String sourceUrl = truncate(request.getSourceUrl(), 500);
        for (int i = 0; i < items.size(); i++) {
            DocumentImportRequest.Item item = items.get(i);
            try {
                KnowledgeChunkRequest chunkRequest = new KnowledgeChunkRequest();
                chunkRequest.setCategoryId(request.getCategoryId());
                chunkRequest.setTitle(resolveTitle(item.getTitle(), sourceTitle));
                chunkRequest.setContent(item.getContent());
                chunkRequest.setSourceTitle(sourceTitle);
                chunkRequest.setSourceUrl(sourceUrl);
                inserted.add(insertChunk(chunkRequest, "DOC", inserted.size()));
                success++;
            } catch (Exception e) {
                fail++;
                errors.add("第 " + (i + 1) + " 条: " + e.getMessage());
            }
        }

        int vectorFail = inserted.isEmpty() ? 0 : batchEmbed(inserted);

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("fail", fail);
        result.put("vectorFail", vectorFail);
        result.put("errors", errors);
        return result;
    }

    // ==================== 分类管理 ====================

    @Override
    public List<KnowledgeCategory> listCategories() {
        return knowledgeCategoryMapper.selectList(new LambdaQueryWrapper<KnowledgeCategory>()
                .orderByAsc(KnowledgeCategory::getSortOrder));
    }

    @Override
    public KnowledgeCategory addCategory(CategoryRequest request) {
        KnowledgeCategory category = new KnowledgeCategory();
        category.setName(request.getName());
        category.setParentId(request.getParentId() == null ? 0L : request.getParentId());
        category.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        category.setDescription(request.getDescription());
        category.setStatus(1);
        knowledgeCategoryMapper.insert(category);
        return category;
    }

    @Override
    public KnowledgeCategory updateCategory(Long id, CategoryRequest request) {
        KnowledgeCategory category = knowledgeCategoryMapper.selectById(id);
        if (category == null) {
            throw new BizException("分类不存在");
        }
        category.setName(request.getName());
        category.setParentId(request.getParentId() == null ? 0L : request.getParentId());
        if (request.getSortOrder() != null) {
            category.setSortOrder(request.getSortOrder());
        }
        category.setDescription(request.getDescription());
        knowledgeCategoryMapper.updateById(category);
        return category;
    }

    @Override
    public void deleteCategory(Long id) {
        knowledgeCategoryMapper.deleteById(id);
    }

    // ==================== 内部方法 ====================

    private void applyRequest(KnowledgeChunk chunk, KnowledgeChunkRequest request) {
        chunk.setCategoryId(request.getCategoryId() == null ? 0L : request.getCategoryId());
        chunk.setTitle(request.getTitle());
        chunk.setContent(request.getContent());
        chunk.setKeywords(request.getKeywords());
        chunk.setSourceUrl(request.getSourceUrl());
        chunk.setSourceTitle(request.getSourceTitle());
    }

    private KnowledgeChunk requireChunk(Long id) {
        KnowledgeChunk chunk = knowledgeChunkMapper.selectById(id);
        if (chunk == null) {
            throw new BizException("知识片段不存在");
        }
        return chunk;
    }

    /** 向量化并写入 VectorStore，失败时状态标记为 2 */
    private void embedAndStore(KnowledgeChunk chunk) {
        try {
            String vectorId = UUID.randomUUID().toString();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunk_id", String.valueOf(chunk.getId()));
            metadata.put("title", chunk.getTitle());
            metadata.put("category", categoryName(chunk.getCategoryId()));
            metadata.put("category_id", chunk.getCategoryId());

            Document document = new Document(vectorId, chunk.getContent(), metadata);
            vectorStore.add(List.of(document));

            chunk.setVectorId(vectorId);
            chunk.setVectorStatus(1);
            knowledgeChunkMapper.updateById(chunk);
        } catch (Exception e) {
            log.error("知识片段向量化失败, chunkId={}, err={}", chunk.getId(), e.getMessage());
            chunk.setVectorStatus(2);
            knowledgeChunkMapper.updateById(chunk);
        }
    }

    /**
     * 批量向量化：一次 {@code vectorStore.add} 减少往返（几十条逐条调用会逼近前端 60s 超时）。
     * 失败时把本批全部标记 vectorStatus=2（可后续用「重建向量」重试），返回失败条数。
     */
    private int batchEmbed(List<KnowledgeChunk> chunks) {
        String category = categoryName(chunks.get(0).getCategoryId());
        List<Document> documents = new ArrayList<>(chunks.size());
        List<String> vectorIds = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            String vectorId = UUID.randomUUID().toString();
            vectorIds.add(vectorId);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunk_id", String.valueOf(chunk.getId()));
            metadata.put("title", chunk.getTitle());
            metadata.put("category", category);
            metadata.put("category_id", chunk.getCategoryId());
            documents.add(new Document(vectorId, chunk.getContent(), metadata));
        }
        try {
            vectorStore.add(documents);
            for (int i = 0; i < chunks.size(); i++) {
                KnowledgeChunk chunk = chunks.get(i);
                chunk.setVectorId(vectorIds.get(i));
                chunk.setVectorStatus(1);
                knowledgeChunkMapper.updateById(chunk);
            }
            return 0;
        } catch (Exception e) {
            log.error("文档批量向量化失败, count={}, err={}", chunks.size(), e.getMessage());
            for (KnowledgeChunk chunk : chunks) {
                chunk.setVectorStatus(2);
                knowledgeChunkMapper.updateById(chunk);
            }
            return chunks.size();
        }
    }

    /** 切块标题兜底：自身标题 → 源标题 → 「文档片段」。 */
    private String resolveTitle(String title, String sourceTitle) {
        String resolved = truncate(title, 200);
        if (resolved != null) {
            return resolved;
        }
        resolved = truncate(sourceTitle, 200);
        return resolved == null ? "文档片段" : resolved;
    }

    /** 去空白并按字段长度截断，空串转 null。 */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private void removeVector(KnowledgeChunk chunk) {
        if (chunk.getVectorId() == null) {
            return;
        }
        try {
            vectorStore.delete(List.of(chunk.getVectorId()));
        } catch (Exception e) {
            log.warn("移除向量失败, vectorId={}, err={}", chunk.getVectorId(), e.getMessage());
        }
    }

    private String categoryName(Long categoryId) {
        if (categoryId == null || categoryId == 0L) {
            return "";
        }
        KnowledgeCategory category = knowledgeCategoryMapper.selectById(categoryId);
        return category == null ? "" : category.getName();
    }

    // ==================== CSV 工具 ====================

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        return Long.parseLong(s.trim());
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 简易 RFC 4180 CSV 解析（支持带引号字段、逗号与换行） */
    private List<List<String>> parseCsv(String csv) {
        if (!csv.isEmpty() && csv.charAt(0) == '﻿') {
            csv = csv.substring(1); // 去除 UTF-8 BOM
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
