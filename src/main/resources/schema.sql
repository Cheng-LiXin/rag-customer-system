-- =====================================================================
-- 基于 RAG 的智能客服系统 —— MySQL 8.0 初始化脚本
-- 使用方法：mysql -uroot -p < src/main/resources/schema.sql
-- 说明：脚本可重复执行（先 DROP 再 CREATE）
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `rag_customer` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `rag_customer`;

SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 1. 系统用户表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    -- 账号ID（主键）：6 位 = 角色前缀(11用户/12客服/13管理员) + 同前缀4位序号，由 sys_id_seq 分配，
    -- 非自增；新增用户一律经 AccountNoService.nextId(prefix) 取得后再 insert。
    `id`                 BIGINT       NOT NULL COMMENT '账号ID：11用户/12客服/13管理员+4位序号',
    `username`           VARCHAR(50)  NOT NULL COMMENT '用户名（自助注册=手机号）',
    `password`           VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密，明文不外泄）',
    `nickname`           VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `nickname_updated_at` DATETIME     DEFAULT NULL COMMENT '昵称最近修改时间（30天内仅可改1次）',
    `avatar`             VARCHAR(255) DEFAULT NULL COMMENT '头像地址',
    `email`              VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone`              VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `province`           VARCHAR(50)  DEFAULT NULL COMMENT '所在省份',
    `city`               VARCHAR(50)  DEFAULT NULL COMMENT '所在城市',
    `identity`           VARCHAR(20)  DEFAULT NULL COMMENT '身份：考生/本科生/硕士生',
    `status`             TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `deleted`            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    `create_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户表';

-- ---------------------------------------------------------------------
-- 1.1 账号号分配器（每个角色前缀一行，curr=该前缀已发出的最大序号）
-- id = role_prefix * 10000 + curr；取号：UPDATE ... SET curr=curr+1（行锁）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_id_seq`;
CREATE TABLE `sys_id_seq` (
    `role_prefix` INT NOT NULL COMMENT '角色前缀：11用户 12客服 13管理员',
    `curr`        INT NOT NULL DEFAULT 0 COMMENT '已发出的最大4位序号',
    PRIMARY KEY (`role_prefix`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账号号序号分配器';
INSERT INTO `sys_id_seq` (`role_prefix`, `curr`) VALUES (11, 0), (12, 0), (13, 0);

-- ---------------------------------------------------------------------
-- 1.2 违禁词表（昵称/个人资料屏蔽，admin 后台可维护）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_banned_word`;
CREATE TABLE `sys_banned_word` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `word`        VARCHAR(50) NOT NULL COMMENT '违禁词',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-停用',
    `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '违禁词表';
INSERT INTO `sys_banned_word` (`word`, `status`) VALUES
    ('垃圾', 1), ('笨蛋', 1), ('傻逼', 1), ('去死', 1), ('滚', 1);

-- ---------------------------------------------------------------------
-- 2. 角色表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_name`   VARCHAR(50) NOT NULL COMMENT '角色名称',
    `role_code`   VARCHAR(50) NOT NULL COMMENT '角色编码',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '角色描述',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色表';

-- ---------------------------------------------------------------------
-- 3. 权限表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `permission_name` VARCHAR(50)  NOT NULL COMMENT '权限名称',
    `permission_code` VARCHAR(100) NOT NULL COMMENT '权限编码',
    `type`            TINYINT      NOT NULL DEFAULT 1 COMMENT '类型：1-菜单 2-按钮',
    `parent_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '父权限ID，0 表示根',
    `path`            VARCHAR(200) DEFAULT NULL COMMENT '路由路径',
    `icon`            VARCHAR(100) DEFAULT NULL COMMENT '图标',
    `sort_order`      INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`permission_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限表';

-- ---------------------------------------------------------------------
-- 4. 用户-角色关联表（RBAC 辅助表）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`user_id`, `role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户角色关联表';

-- ---------------------------------------------------------------------
-- 5. 角色-权限关联表（RBAC 辅助表）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
    `role_id`       BIGINT NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT NOT NULL COMMENT '权限ID',
    PRIMARY KEY (`role_id`, `permission_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色权限关联表';

-- ---------------------------------------------------------------------
-- 6. 知识分类表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `knowledge_category`;
CREATE TABLE `knowledge_category` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(50)  NOT NULL COMMENT '分类名称',
    `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类ID，0 表示根',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '分类描述',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识分类表';

-- ---------------------------------------------------------------------
-- 7. 知识切片表（对应 PGVector 中的向量文档）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `knowledge_chunk`;
CREATE TABLE `knowledge_chunk` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `category_id` BIGINT       NOT NULL DEFAULT 0 COMMENT '知识分类ID',
    `title`       VARCHAR(200) NOT NULL COMMENT '标题',
    `content`     LONGTEXT     NOT NULL COMMENT '切片内容',
    `source_type` VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL/WEB/DOC',
    `source_url`  VARCHAR(500) DEFAULT NULL COMMENT '来源链接',
    `source_title` VARCHAR(200) DEFAULT NULL COMMENT '源标题（所属源文干净标题，热门知识聚合用）',
    `chunk_index` INT          NOT NULL DEFAULT 0 COMMENT '切片序号',
    `vector_id`   VARCHAR(64)  DEFAULT NULL COMMENT 'PGVector 中对应文档ID',
    `vector_status` TINYINT    NOT NULL DEFAULT 0 COMMENT '向量化状态：0-待向量化 1-已向量化 2-失败',
    `keywords`    VARCHAR(500) DEFAULT NULL COMMENT '关键词（逗号分隔）',
    `hit_count`   INT          NOT NULL DEFAULT 0 COMMENT '被检索命中次数',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_category` (`category_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识切片表';

-- ---------------------------------------------------------------------
-- 8. 会话表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `conversation`;
CREATE TABLE `conversation` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `title`        VARCHAR(200) DEFAULT NULL COMMENT '会话标题',
    `session_type` VARCHAR(20)  NOT NULL DEFAULT 'AUTO' COMMENT '会话类型：AUTO-机器人 HUMAN-人工',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-进行中 2-已结束',
    `agent_id`        BIGINT       DEFAULT NULL COMMENT '处理客服ID（会话结束后保留归属）',
    `last_reply_time` DATETIME     DEFAULT NULL COMMENT '最后回复时间（消息写入时更新）',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_agent` (`agent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话表';

-- ---------------------------------------------------------------------
-- 9. 消息表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `message`;
CREATE TABLE `message` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT   NOT NULL COMMENT '会话ID',
    `sender_type`     VARCHAR(20) NOT NULL COMMENT '发送方：USER-用户 AI-机器人 AGENT-客服',
    `content`         LONGTEXT NOT NULL COMMENT '消息内容',
    `citations`       TEXT      DEFAULT NULL COMMENT '引用知识片段（JSON 数组）',
    `intent_category` VARCHAR(50) DEFAULT NULL COMMENT '意图分类（F03 持久化）',
    `from_cache`      TINYINT   NOT NULL DEFAULT 0 COMMENT '是否来自缓存：0-否 1-是',
    `message_type`    VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型：TEXT/IMAGE',
    `status`          TINYINT  NOT NULL DEFAULT 1 COMMENT '状态：1-正常 0-撤回',
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_conversation` (`conversation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息表';

-- ---------------------------------------------------------------------
-- 10. 工单表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `ticket`;
CREATE TABLE `ticket` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT       NOT NULL COMMENT '提交用户ID',
    `conversation_id` BIGINT       DEFAULT NULL COMMENT '关联会话ID',
    `title`           VARCHAR(200) NOT NULL COMMENT '工单标题',
    `description`     TEXT         COMMENT '问题描述',
    `category`        VARCHAR(50)  DEFAULT NULL COMMENT '问题分类',
    `priority`        TINYINT      NOT NULL DEFAULT 2 COMMENT '优先级：1-低 2-中 3-高',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-待处理 2-处理中 3-已解决 4-已关闭',
    `assignee_id`     BIGINT       DEFAULT NULL COMMENT '处理人（客服）ID',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `close_time`      DATETIME     DEFAULT NULL COMMENT '关闭时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_assignee` (`assignee_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单表';

-- ---------------------------------------------------------------------
-- 11. 满意度评价表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `satisfaction`;
CREATE TABLE `satisfaction` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT       NOT NULL COMMENT '会话ID',
    `user_id`         BIGINT       NOT NULL COMMENT '评价用户ID',
    `ticket_id`       BIGINT       DEFAULT NULL COMMENT '关联工单ID',
    `rating`          TINYINT      NOT NULL COMMENT '评分：1-5',
    `comment`         VARCHAR(500) DEFAULT NULL COMMENT '评价内容',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation` (`conversation_id`) COMMENT '同一会话最多一条评价'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '满意度评价表';

-- ---------------------------------------------------------------------
-- 12. 操作日志表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `operation_log`;
CREATE TABLE `operation_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  DEFAULT NULL COMMENT '操作用户名',
    `module`      VARCHAR(50)  DEFAULT NULL COMMENT '操作模块',
    `action`      VARCHAR(100) DEFAULT NULL COMMENT '操作描述',
    `method`      VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP 方法',
    `uri`         VARCHAR(255) DEFAULT NULL COMMENT '请求路径',
    `ip`          VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
    `params`      TEXT         DEFAULT NULL COMMENT '请求参数（JSON）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-成功 0-失败',
    `error_msg`   VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    `cost_time`   BIGINT       DEFAULT NULL COMMENT '耗时（毫秒）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_username` (`username`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志表';

-- ---------------------------------------------------------------------
-- 13. 注入防护审计事件表（批次 B）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `guard_event`;
CREATE TABLE `guard_event` (
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

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 初始数据
-- =====================================================================

-- 角色
INSERT INTO `sys_role` (`id`, `role_name`, `role_code`, `description`, `status`) VALUES
    (1, '系统管理员', 'ADMIN', '拥有全部权限', 1),
    (2, '人工客服',   'AGENT', '处理会话与工单', 1),
    (3, '普通用户',   'USER',  '提问与评价', 1);

-- 权限（菜单类型，骨架示例）
INSERT INTO `sys_permission` (`id`, `permission_name`, `permission_code`, `type`, `parent_id`, `path`, `sort_order`, `status`) VALUES
    (1, '用户管理',   'sys:user',        1, 0, '/system/user',  1, 1),
    (2, '知识库管理', 'knowledge:manage',1, 0, '/knowledge',    2, 1),
    (3, '会话管理',   'conversation:manage', 1, 0, '/conversation', 3, 1),
    (4, '工单处理',   'ticket:handle',   1, 0, '/ticket',       4, 1);

-- 角色-权限：管理员拥有全部；客服拥有会话与工单；普通用户拥有会话
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4),
    (2, 3), (2, 4),
    (3, 3);

-- 初始用户：由应用启动时 DataInitializer 重建（账号ID走 sys_id_seq 分配：admin=130001、
-- agent=120001、user=110001）。密码默认 Admin@Ysu2026/Agent@Ysu2026/User@Ysu2026，
-- 可用 DEMO_ADMIN_PASSWORD 等环境变量覆盖，BCrypt 加密。此处不再预置。
