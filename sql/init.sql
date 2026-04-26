-- 创建数据库（如还未创建）
CREATE DATABASE IF NOT EXISTS ai_daily
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE ai_daily;

-- 简报表
CREATE TABLE IF NOT EXISTS report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    edition VARCHAR(20) NOT NULL COMMENT '版本：morning / evening',
    title VARCHAR(255) NOT NULL COMMENT '简报标题',
    content LONGTEXT NOT NULL COMMENT '简报正文（Markdown）',
    summary VARCHAR(500) DEFAULT NULL COMMENT '摘要（列表展示用）',
    run_id VARCHAR(50) DEFAULT NULL COMMENT 'GitHub Actions Run ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_edition (edition),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 简报表';

-- 订阅配置表
CREATE TABLE IF NOT EXISTS subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    receive_time VARCHAR(20) NOT NULL DEFAULT 'both' COMMENT '接收时间：morning/evening/both',
    preference_fields JSON DEFAULT NULL COMMENT '偏好领域JSON数组',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用订阅：1启用 0暂停',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅配置表';

-- 插入默认订阅配置
INSERT INTO subscription (receive_time, preference_fields, enabled) VALUES ('both', '["AI大模型","Web开发"]', 1);