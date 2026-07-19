-- ============================================================
-- V2 多租户改造：用户 + 邀请码 + 订阅按人隔离
-- 执行方式：在 TiDB / MySQL 里直接运行本脚本
-- 幂等：可重复执行
-- ============================================================

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    email VARCHAR(255) NOT NULL COMMENT '登录邮箱',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 密码哈希',
    display_name VARCHAR(100) DEFAULT NULL COMMENT '昵称',
    role VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '角色 ADMIN|USER',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    invite_code_used VARCHAR(50) DEFAULT NULL COMMENT '使用的邀请码',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ------------------------------------------------------------
-- 邀请码表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS invite_code (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL COMMENT '邀请码',
    created_by BIGINT NOT NULL COMMENT '创建者 user_id',
    used_by BIGINT DEFAULT NULL COMMENT '使用者 user_id',
    used_at DATETIME DEFAULT NULL COMMENT '使用时间',
    expires_at DATETIME DEFAULT NULL COMMENT '过期时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请码表';

-- ------------------------------------------------------------
-- 推送渠道表（一个用户可绑多个渠道）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS push_channel (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    channel_type VARCHAR(20) NOT NULL COMMENT 'email|wechat|dingtalk|feishu',
    display_name VARCHAR(100) DEFAULT NULL COMMENT '自定义昵称',
    target VARCHAR(1000) NOT NULL COMMENT '邮箱地址 或 webhook URL',
    secret VARCHAR(255) DEFAULT NULL COMMENT '钉钉/飞书签名密钥',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送渠道表';

-- ------------------------------------------------------------
-- 推送日志（方便排障 & 前端"通知记录"页展示）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS push_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    channel_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'success|failed',
    error_message VARCHAR(1000) DEFAULT NULL,
    pushed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_pushed (user_id, pushed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送日志';

-- ------------------------------------------------------------
-- 迁移订阅表：加 user_id + morning/evening 时间字段
-- ------------------------------------------------------------
-- MySQL 8 / TiDB 支持 IF NOT EXISTS on ADD COLUMN；若不支持请手动删除后重跑
ALTER TABLE subscription
    ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE subscription
    ADD COLUMN IF NOT EXISTS morning_enabled TINYINT(1) NOT NULL DEFAULT 1;
ALTER TABLE subscription
    ADD COLUMN IF NOT EXISTS morning_time TIME NOT NULL DEFAULT '08:00:00';
ALTER TABLE subscription
    ADD COLUMN IF NOT EXISTS evening_enabled TINYINT(1) NOT NULL DEFAULT 1;
ALTER TABLE subscription
    ADD COLUMN IF NOT EXISTS evening_time TIME NOT NULL DEFAULT '20:00:00';

-- user_id 唯一（一人一条订阅配置）
CREATE UNIQUE INDEX IF NOT EXISTS uk_subscription_user_id ON subscription(user_id);

-- ------------------------------------------------------------
-- 种子管理员用户由 AdminBootstrapRunner 按 ADMIN_EMAIL / ADMIN_PASSWORD 自动创建
