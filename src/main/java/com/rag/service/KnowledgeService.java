package com.rag.service;

import com.rag.dto.CategoryRequest;
import com.rag.dto.DocumentImportRequest;
import com.rag.dto.KnowledgeChunkRequest;
import com.rag.dto.PageResult;
import com.rag.entity.KnowledgeCategory;
import com.rag.entity.KnowledgeChunk;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理服务（F02）：知识片段 CRUD + 向量同步 + 分类管理
 */
public interface KnowledgeService {

    /** 分页查询知识片段（支持分类/关键词/状态筛选） */
    PageResult<KnowledgeChunk> pageChunks(long pageNum, long pageSize, Long categoryId, String keyword, Integer status);

    /** 新增知识片段（自动向量化并写入 VectorStore） */
    KnowledgeChunk addChunk(KnowledgeChunkRequest request);

    /** 编辑知识片段（重新向量化） */
    KnowledgeChunk updateChunk(Long id, KnowledgeChunkRequest request);

    /** 删除知识片段（同时移除向量） */
    void deleteChunk(Long id);

    /** 重新向量化（重试失败的片段） */
    KnowledgeChunk reindexChunk(Long id);

    /** 全部知识片段（导出用） */
    List<KnowledgeChunk> listAllChunks();

    /** 知识片段序列化为 CSV（含表头） */
    String toCsv(List<KnowledgeChunk> list);

    /** 从 CSV 批量导入（返回成功/失败统计） */
    Map<String, Object> importCsv(String csv);

    /** 文档上传导入：批量落库（sourceType=DOC、chunkIndex 递增）+ 批量向量化 */
    Map<String, Object> importDocumentChunks(DocumentImportRequest request);

    /** 分类列表 */
    List<KnowledgeCategory> listCategories();

    KnowledgeCategory addCategory(CategoryRequest request);

    KnowledgeCategory updateCategory(Long id, CategoryRequest request);

    void deleteCategory(Long id);
}
