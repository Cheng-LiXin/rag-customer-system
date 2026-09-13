-- =============================================================================
-- 清理 vector_store 上的冗余 HNSW 索引（一次性，幂等）
-- -----------------------------------------------------------------------------
-- 背景：Spring AI 0.8.1 的 PgVectorStore.afterPropertiesSet() 执行的是
--         CREATE INDEX ON vector_store USING hnsw (embedding vector_cosine_ops)
--       —— **既没指定索引名、也没有 IF NOT EXISTS**。PostgreSQL 会为无名索引
--       自动生成唯一名（vector_store_embedding_idx、_idx1、_idx2…），
--       于是**每次应用启动都会新增一个 HNSW 索引**。
--
--       实测：本机累积到 219 个、索引占 336 MB（而表数据只有 328 kB），
--       且每条向量写入都要同时更新全部索引 —— 知识导入慢的元凶。
--
-- 根治：PgVectorConfig 已改为 PgIndexType.NONE + 启动时 CREATE INDEX IF NOT EXISTS，
--       所以**新版本不会再产生冗余索引**。本脚本负责清掉历史遗留。
--
-- 用法：
--   docker exec -i rag-postgres psql -U postgres -d rag_vector < tools/db/pg-vector-index-cleanup.sql
--
-- 清理后由应用启动时的 ensureVectorIndex 重建唯一那个索引（也可由本脚本末尾直接建）。
-- 目标状态：**恰好 2 个索引** —— 1 个主键（vector_store_pkey）+ 1 个 HNSW。
-- =============================================================================

\echo '--- 清理前 ---'
SELECT COUNT(*) AS indexes_before,
       pg_size_pretty(pg_relation_size('vector_store'))  AS table_size,
       pg_size_pretty(pg_indexes_size('vector_store'))   AS index_size
FROM pg_indexes WHERE tablename = 'vector_store';

-- 删掉除主键以外的全部索引（含那两百多个 HNSW）
-- 用 DO 块是因为索引名是动态生成的，必须逐个 DROP
DO $$
DECLARE
    r record;
    n int := 0;
BEGIN
    FOR r IN SELECT indexname FROM pg_indexes
             WHERE tablename = 'vector_store'
               AND indexname <> 'vector_store_pkey'
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %I', r.indexname);
        n := n + 1;
    END LOOP;
    RAISE NOTICE '已删除 % 个冗余索引', n;
END $$;

-- 重建唯一那个 HNSW 余弦索引（IF NOT EXISTS ⇒ 重复执行无副作用）
-- 索引名与 PostgreSQL 的传统默认名一致，便于对照文档里的「应为 2 个」
CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops);

\echo '--- 清理后（indexes_after 应为 2）---'
SELECT COUNT(*) AS indexes_after,
       pg_size_pretty(pg_relation_size('vector_store'))  AS table_size,
       pg_size_pretty(pg_indexes_size('vector_store'))   AS index_size
FROM pg_indexes WHERE tablename = 'vector_store';

SELECT indexname FROM pg_indexes WHERE tablename = 'vector_store' ORDER BY indexname;
