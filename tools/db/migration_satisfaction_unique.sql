-- =============================================================================
-- 满意度评价防刷迁移：同一会话最多一条评价
-- -----------------------------------------------------------------------------
-- 背景：旧代码 POST /api/satisfaction 每次调用无条件 insert，同一会话可被无限
--       重复评价，行数无限堆积并污染满意度统计（COUNT 虚高 / AVG 被刷）。
-- 方案：① 清掉历史重复（每会话保留最新一条，id 最大）；
--       ② 加唯一键 uk_conversation(conversation_id) 兜底并发首评。
--
-- 用法：后端 8080 处于停止态时执行（一次性，可重复执行）：
--   D:\mysql-8.4.11-winx64\bin\mysql.exe -uroot -p rag_customer < tools\db\migration_satisfaction_unique.sql
-- =============================================================================

USE `rag_customer`;

-- ① 删除同一会话的历史重复评价（保留每会话 id 最大的那条）
DELETE s1 FROM `satisfaction` s1
JOIN `satisfaction` s2
  ON s1.conversation_id = s2.conversation_id AND s1.id < s2.id;

-- ② 幂等添加唯一约束（已存在则跳过）
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE table_schema = DATABASE()
                   AND table_name = 'satisfaction'
                   AND index_name = 'uk_conversation');
SET @ddl := IF(@has_idx = 0,
               'ALTER TABLE `satisfaction` ADD UNIQUE KEY `uk_conversation` (`conversation_id`)',
               'SELECT ''uk_conversation already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
