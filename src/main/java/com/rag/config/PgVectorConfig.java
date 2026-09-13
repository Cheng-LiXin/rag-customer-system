package com.rag.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PGVector 配置：
 * PostgreSQL 仅用于向量检索，不参与业务数据存储。
 * 注意：此处未把 DataSource 注册为 Spring Bean，而是内联创建 Hikari 连接池，
 * 避免与 MySQL 主数据源的自动装配产生冲突。
 */
@Configuration
public class PgVectorConfig {

    @Value("${pgvector.datasource.url}")
    private String url;

    @Value("${pgvector.datasource.username}")
    private String username;

    @Value("${pgvector.datasource.password}")
    private String password;

    /** 向量维度，需与所用 Embedding 模型的输出维度一致 */
    @Value("${pgvector.dimensions:1024}")
    private int dimensions;

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(5);
        return new JdbcTemplate(dataSource);
    }

    /**
     * 向量存储（首次启动会自动建 vector_store 表）。
     *
     * <p><b>索引刻意交给 {@link #ensureVectorIndex} 建，这里传 {@link PgVectorStore.PgIndexType#NONE}。</b>
     * 原因：Spring AI 0.8.1 的 {@code PgVectorStore.afterPropertiesSet()} 执行的是
     * <pre>CREATE INDEX ON %s USING %s (embedding %s)</pre>
     * —— <b>既没指定索引名，也没有 IF NOT EXISTS</b>。PostgreSQL 会给无名索引自动生成唯一名
     * （{@code vector_store_embedding_idx}、{@code _idx1}、{@code _idx2}…），
     * 于是**每次应用启动都会新增一个 HNSW 索引**（实测 2 个/次启动，见下）。
     * 后果：本机一度累积到 <b>219 个索引、占 336 MB</b>（表数据只有 328 kB），
     * 且每条向量写入都要同时更新全部索引，写入放大 200 余倍 —— 知识导入变慢的元凶。
     *
     * <p>返回类型声明为 {@code PgVectorStore}（而非接口 {@code VectorStore}）是有意的：
     * Spring AI 的 {@code PgVectorStoreAutoConfiguration} 用
     * {@code @ConditionalOnMissingBean(PgVectorStore.class)} 退让，若这里只声明接口类型，
     * 该条件匹配不上 → 上下文里会出现**两个** PgVectorStore（各自跑一次建索引，即 +2/次启动）。
     */
    @Bean
    public PgVectorStore pgVectorStore(JdbcTemplate pgVectorJdbcTemplate, EmbeddingClient embeddingClient) {
        // 0.8.x 构造器：(JdbcTemplate, EmbeddingClient, dimensions, PgDistanceType, removeExistingVectorStoreTable, PgIndexType)
        // removeExistingVectorStoreTable 传 false，避免每次启动都重建/清空向量表。
        return new PgVectorStore(pgVectorJdbcTemplate, embeddingClient,
                dimensions,
                PgVectorStore.PgDistanceType.COSINE_DISTANCE,
                false,
                PgVectorStore.PgIndexType.NONE);
    }

    /**
     * 幂等地保证 {@code vector_store} 上**恰好有一个** HNSW 余弦索引。
     *
     * <p>用 {@link ApplicationRunner} 而非在 {@link #pgVectorStore} 里直接建，是为了确保
     * 它一定在 {@code PgVectorStore.afterPropertiesSet()} 建好表**之后**才执行
     * （ApplicationRunner 在容器 refresh 完成后运行）。
     *
     * <p>{@code IF NOT EXISTS} 让本方法可反复执行：已存在就什么都不做，
     * 所以重启不再产生新索引。索引名固定为 {@code vector_store_embedding_idx}
     * —— 与 PostgreSQL 为「表 vector_store + 列 embedding」生成的传统默认名一致。
     */
    @Bean
    public ApplicationRunner ensureVectorIndex(JdbcTemplate pgVectorJdbcTemplate) {
        return args -> pgVectorJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS vector_store_embedding_idx "
                        + "ON vector_store USING hnsw (embedding vector_cosine_ops)");
    }
}
