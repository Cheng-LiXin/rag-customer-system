-- =============================================================================
-- 数据飞轮迁移（批次 E）：消息级反馈 + 未解决问题池
-- -----------------------------------------------------------------------------
-- 背景：要让「负反馈 → 未解决问题池 → 人工补知识 → 重评估」这条闭环能转起来，
--       需要两样东西：① AI 消息上的 👍/👎；② 一个沉淀「答不上来的问题」的池子。
--
-- 与满意度（satisfaction）的分工：
--   satisfaction 是**整会话**一条评价（uk_conversation 唯一键）；
--   本迁移加的是**单条 AI 消息**的反馈，粒度更细，且能一路追到具体是哪个回答不行。
--
-- 幂等：ADD COLUMN 在 MySQL 8 不支持 IF NOT EXISTS，故用 information_schema 探测后动态执行
--       （与 migration_satisfaction_unique.sql 同一套写法）。整脚本可重复执行。
--
-- 用法（后端停止态执行）：
--   D:\mysql-8.4.11-winx64\bin\mysql.exe -uroot -p123456 rag_customer < tools\db\migration_feedback.sql
-- =============================================================================

USE `rag_customer`;

-- ---------------------------------------------------------------------------
-- ① message 表：AI 消息级反馈
-- ---------------------------------------------------------------------------
SET @has_feedback := (SELECT COUNT(*) FROM information_schema.COLUMNS
                      WHERE table_schema = DATABASE() AND table_name = 'message'
                        AND column_name = 'feedback');
SET @ddl := IF(@has_feedback = 0,
    'ALTER TABLE `message` ADD COLUMN `feedback` TINYINT NOT NULL DEFAULT 0 COMMENT ''AI消息反馈：0-未评 1-有帮助 2-没帮助''',
    'SELECT ''message.feedback already exists''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_ftime := (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE table_schema = DATABASE() AND table_name = 'message'
                     AND column_name = 'feedback_time');
SET @ddl := IF(@has_ftime = 0,
    'ALTER TABLE `message` ADD COLUMN `feedback_time` DATETIME DEFAULT NULL COMMENT ''反馈时间''',
    'SELECT ''message.feedback_time already exists''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_fcmt := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE table_schema = DATABASE() AND table_name = 'message'
                    AND column_name = 'feedback_comment');
SET @ddl := IF(@has_fcmt = 0,
    'ALTER TABLE `message` ADD COLUMN `feedback_comment` VARCHAR(500) DEFAULT NULL COMMENT ''点踩时可选填的原因''',
    'SELECT ''message.feedback_comment already exists''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- ② 未解决问题池
-- ---------------------------------------------------------------------------
-- question_hash：归一化（去空白/标点/全角）后的 MD5，用唯一键把「同一个问题被反复问到」
--                收敛成一行并累加 hit_count —— 池子里要看的是「哪些问题最常答不上来」，
--                而不是一堆重复行。注意归一化**不做同义改写**，避免把不同问题误合并。
-- source：1 兜底（三档全空）/ 2 用户点踩 / 3 相似度过低被拒答 / 4 命中注入防护
CREATE TABLE IF NOT EXISTS `unresolved_question` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question`            VARCHAR(500) NOT NULL COMMENT '问题原文（首次入库的那一版）',
    `question_hash`       CHAR(32)     NOT NULL COMMENT '归一化后的 MD5，用于收敛重复提问',
    `source`              TINYINT      NOT NULL DEFAULT 1 COMMENT '来源：1兜底 2点踩 3拒答 4防护拦截',
    `hit_count`           INT          NOT NULL DEFAULT 1 COMMENT '被问到的次数',
    `top_score`           DECIMAL(6,4) DEFAULT NULL COMMENT '最近一次的最高相似度，便于判断是"没召回"还是"没答上"',
    `conversation_id`     BIGINT       DEFAULT NULL COMMENT '最近一次的会话ID',
    `message_id`          BIGINT       DEFAULT NULL COMMENT '最近一次的消息ID',
    `user_id`             BIGINT       DEFAULT NULL COMMENT '提问用户（游客为 0/NULL）',
    `status`              TINYINT      NOT NULL DEFAULT 1 COMMENT '处理状态：1待处理 2已补知识 3已忽略',
    `handler_id`          BIGINT       DEFAULT NULL COMMENT '处理人',
    `handle_time`         DATETIME     DEFAULT NULL COMMENT '处理时间',
    `knowledge_chunk_id`  BIGINT       DEFAULT NULL COMMENT '补充的知识片段ID（闭环的落点）',
    `remark`              VARCHAR(500) DEFAULT NULL COMMENT '处理备注',
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_hash` (`question_hash`),
    KEY `idx_status` (`status`),
    KEY `idx_hit_count` (`hit_count`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '未解决问题池（数据飞轮入口）';

SELECT 'migration_feedback 完成' AS result;
