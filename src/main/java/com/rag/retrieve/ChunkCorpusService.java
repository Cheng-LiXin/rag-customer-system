package com.rag.retrieve;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.entity.KnowledgeCategory;
import com.rag.entity.KnowledgeChunk;
import com.rag.mapper.KnowledgeCategoryMapper;
import com.rag.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存语料：把知识库全量片段加载进来，供 BM25 建索引、并用于把 chunkId 还原成 {@link Document}。
 *
 * <p>刷新策略是「懒刷新 + TTL」（默认 30 秒）：每次取用若距上次构建超过 TTL 就重建一次。
 * 语料只有约 200 条、重建 &lt;100ms，所以这个代价可以忽略，换来的是
 * **不必在知识库的每个写入口（新增/编辑/删除/CSV导入/文档导入）都手工挂一次重建**
 * —— 那种做法容易漏挂，而漏挂的表现是「BM25 检索悄悄看不到新数据」，很难发现。
 * 需要立即生效时调用 {@link #rebuild()}（管理端有对应接口）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkCorpusService {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeCategoryMapper categoryMapper;

    @Value("${rag.retrieve.bm25.k1:1.2}")
    private double k1;

    @Value("${rag.retrieve.bm25.b:0.75}")
    private double b;

    @Value("${rag.retrieve.bm25.rebuild-ms:30000}")
    private long rebuildMs;

    private volatile Bm25Index index;
    private volatile Map<Long, KnowledgeChunk> byId = Map.of();
    private volatile Map<Long, String> categoryNames = Map.of();
    private volatile long builtAt;

    /** 取 BM25 索引（必要时先刷新） */
    public Bm25Index index() {
        ensureFresh();
        Bm25Index i = index;
        return i == null ? Bm25Index.build(List.of(), k1, b) : i;
    }

    public int size() {
        ensureFresh();
        return byId.size();
    }

    /** 把 chunkId 还原成 Document（含 chunk_id/title/category 元数据，与向量库写入时保持一致） */
    public Document document(long chunkId) {
        ensureFresh();
        KnowledgeChunk c = byId.get(chunkId);
        if (c == null || c.getContent() == null) {
            return null;
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunk_id", String.valueOf(c.getId()));
        metadata.put("title", c.getTitle() == null ? "" : c.getTitle());
        metadata.put("category", categoryNames.getOrDefault(c.getCategoryId(), ""));
        metadata.put("category_id", c.getCategoryId() == null ? 0L : c.getCategoryId());
        return new Document(String.valueOf(c.getId()), c.getContent(), metadata);
    }

    /** 立即重建索引（知识库变更后想立刻生效时调用） */
    public synchronized void rebuild() {
        try {
            List<KnowledgeChunk> chunks = chunkMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getStatus, 1));
            Map<Long, KnowledgeChunk> map = new HashMap<>();
            List<Bm25Index.Doc> docs = new ArrayList<>();
            if (chunks != null) {
                for (KnowledgeChunk c : chunks) {
                    if (c.getContent() == null || c.getContent().isEmpty()) {
                        continue;
                    }
                    map.put(c.getId(), c);
                    // 标题一并入索引：标题是「小标题——源标题」，本身承载强信号
                    String text = (c.getTitle() == null ? "" : c.getTitle() + " ") + c.getContent();
                    docs.add(new Bm25Index.Doc(c.getId(), text));
                }
            }
            List<KnowledgeCategory> cats = categoryMapper.selectList(null);
            Map<Long, String> names = new HashMap<>();
            if (cats != null) {
                for (KnowledgeCategory cat : cats) {
                    names.put(cat.getId(), cat.getName());
                }
            }
            this.index = Bm25Index.build(docs, k1, b);
            this.byId = map;
            this.categoryNames = names;
            this.builtAt = System.currentTimeMillis();
            log.info("BM25 语料重建完成：{} 条片段", docs.size());
        } catch (Exception e) {
            // 重建失败不让检索整体挂掉：保留上一份索引（可能是空），下次再试
            log.warn("BM25 语料重建失败: {}", e.getMessage());
            this.builtAt = System.currentTimeMillis();
        }
    }

    private void ensureFresh() {
        if (System.currentTimeMillis() - builtAt > rebuildMs) {
            rebuild();
        }
    }
}
