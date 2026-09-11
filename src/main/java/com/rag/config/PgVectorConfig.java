package com.rag.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
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
     * 向量存储：首次启动会自动创建 vector_store 表及 HNSW 索引。
     */
    @Bean
    public VectorStore pgVectorStore(JdbcTemplate pgVectorJdbcTemplate, EmbeddingClient embeddingClient) {
        // 0.8.x 构造器：(JdbcTemplate, EmbeddingClient, dimensions, PgDistanceType, removeExistingVectorStoreTable, PgIndexType)
        // removeExistingVectorStoreTable 传 false，避免每次启动都重建/清空向量表；
        // 首次启动时 PgVectorStore.afterPropertiesSet() 会自动建表并创建 HNSW 索引。
        return new PgVectorStore(pgVectorJdbcTemplate, embeddingClient,
                dimensions,
                PgVectorStore.PgDistanceType.COSINE_DISTANCE,
                false,
                PgVectorStore.PgIndexType.HNSW);
    }
}
