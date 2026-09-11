-- =====================================================================
-- 账号体系 V2 一次性迁移（仅执行一次；执行前请确保 8080 处于停止态）
-- 作用：sys_user 主键改为 6 位角色前缀账号号(11用户/12客服/13管理员)，
--       重建 sys_user / sys_user_role 空表（demo 由 DataInitializer 出号重建），
--       清空会话/工单/满意度等测试数据，新增 sys_id_seq 分配器与 sys_banned_word 违禁词表。
-- 保留：knowledge_*、sys_role、sys_permission、sys_role_permission、operation_log。
-- 用法：& "D:\mysql-8.4.11-winx64\bin\mysql.exe" -uroot -p123456 rag_customer < tools/db/migration_account_v2.sql
-- 之后清 Redis agent 会话残留：
--   docker exec rag-redis redis-cli --scan --pattern "rag:cs:*" | ForEach-Object { docker exec rag-redis redis-cli DEL $_ }
-- =====================================================================

USE `rag_customer`;

-- 1) 先清业务测试数据（无外键，顺序无关）
TRUNCATE TABLE `satisfaction`;
TRUNCATE TABLE `ticket`;
TRUNCATE TABLE `message`;
TRUNCATE TABLE `conversation`;

-- 2) 重建用户账号表（主键不再自增，由 sys_id_seq 分配 6 位账号号）
DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id`                  BIGINT       NOT NULL COMMENT '账号ID：11用户/12客服/13管理员+4位序号',
    `username`            VARCHAR(50)  NOT NULL COMMENT '用户名（自助注册=手机号）',
    `password`            VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密，明文不外泄）',
    `nickname`            VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `nickname_updated_at` DATETIME     DEFAULT NULL COMMENT '昵称最近修改时间（30天内仅可改1次）',
    `avatar`              VARCHAR(255) DEFAULT NULL COMMENT '头像地址',
    `email`               VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone`               VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `province`            VARCHAR(50)  DEFAULT NULL COMMENT '所在省份',
    `city`                VARCHAR(50)  DEFAULT NULL COMMENT '所在城市',
    `identity`            VARCHAR(20)  DEFAULT NULL COMMENT '身份：考生/本科生/硕士生',
    `status`              TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `deleted`             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户表';

CREATE TABLE `sys_user_role` (
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`user_id`, `role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户角色关联表';

-- 3) 账号号分配器
DROP TABLE IF EXISTS `sys_id_seq`;
CREATE TABLE `sys_id_seq` (
    `role_prefix` INT NOT NULL COMMENT '角色前缀：11用户 12客服 13管理员',
    `curr`        INT NOT NULL DEFAULT 0 COMMENT '已发出的最大4位序号',
    PRIMARY KEY (`role_prefix`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账号号序号分配器';
INSERT INTO `sys_id_seq` (`role_prefix`, `curr`) VALUES (11, 0), (12, 0), (13, 0);

-- 4) 违禁词表 + 示例词（昵称/个人资料屏蔽，admin 后台可维护）
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
