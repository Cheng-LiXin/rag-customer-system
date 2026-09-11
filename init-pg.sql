-- 初始化 PGVector 扩展。
-- pgvector/pgvector 镜像已内置该扩展，此处显式创建以便 Spring AI 使用。
-- 首次启动容器时由 docker-entrypoint-initdb.d 自动执行。
CREATE EXTENSION IF NOT EXISTS vector;
