-- =============================================================================
-- 注入防护审计表迁移（批次 B）
-- -----------------------------------------------------------------------------
-- 背景：问答热路径不在 @OperationLog 的切点范围内，注入攻击需要独立的审计痕迹。
--       每次命中防护规则（BLOCK 拦截 或 LOG 仅记录）写一行，供管理端复核与规则调优。
--
-- 说明：这是**新建表**，用 CREATE TABLE IF NOT EXISTS 即可幂等，
--       无需 satisfaction 迁移里那套 information_schema 探测（那是给 ALTER 加列用的）。
--
-- 用法（后端停止态执行，可重复执行）：
--   D:\mysql-8.4.11-winx64\bin\mysql.exe -uroot -p123456 rag_customer < tools\db\migration_guard.sql
-- =============================================================================

USE `rag_customer`;

CREATE TABLE IF NOT EXISTS `guard_event` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `layer`       VARCHAR(16)  NOT NULL COMMENT '命中层：input/context/output',
    `rule_id`     VARCHAR(64)  DEFAULT NULL COMMENT '命中的规则 ID',
    `action`      VARCHAR(16)  NOT NULL COMMENT '动作：BLOCK 拦截 / LOG 仅记录',
    `question`    VARCHAR(500) DEFAULT NULL COMMENT '触发本次问答的用户问题',
    `chunk_id`    VARCHAR(64)  DEFAULT NULL COMMENT '上下文层命中时被污染的知识片段 chunk_id',
    `hit_text`    VARCHAR(500) DEFAULT NULL COMMENT '命中处上下文（前后各约 40 字）',
    `username`    VARCHAR(50)  DEFAULT NULL COMMENT '当前登录用户名（游客为 NULL）',
    `ip`          VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_layer` (`layer`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '注入防护审计事件';
