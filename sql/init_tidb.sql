-- ================================================
-- AI 每日简报 - TiDB 数据库初始化脚本
-- ================================================
-- 运行方式: 在 TiDB Cloud Console 或通过 mysql 客户端执行

-- 创建数据库
CREATE DATABASE IF NOT EXISTS ai_daily
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE ai_daily;

-- ================================================
-- 简报表
-- ================================================
CREATE TABLE IF NOT EXISTS report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    title VARCHAR(255) NOT NULL COMMENT '标题',
    content TEXT NOT NULL COMMENT '简报内容（Markdown）',
    summary VARCHAR(500) COMMENT '摘要',
    source VARCHAR(50) DEFAULT 'ai-news-bot' COMMENT '来源',
    edition VARCHAR(20) DEFAULT 'morning' COMMENT '版本: morning(早间)/evening(晚间)',
    push_time DATETIME COMMENT '推送时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_push_time (push_time),
    INDEX idx_edition (edition),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI简报表';

-- ================================================
-- 订阅配置表
-- ================================================
CREATE TABLE IF NOT EXISTS subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) DEFAULT 'default' COMMENT '用户标识',
    morning_enabled BOOLEAN DEFAULT TRUE COMMENT '早间推送开关',
    evening_enabled BOOLEAN DEFAULT TRUE COMMENT '晚间推送开关',
    morning_time TIME DEFAULT '08:00:00' COMMENT '早间推送时间',
    evening_time TIME DEFAULT '20:00:00' COMMENT '晚间推送时间',
    categories JSON COMMENT '偏好领域',
    webhook_url VARCHAR(500) COMMENT '企业微信 Webhook 地址',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅配置表';

-- ================================================
-- 初始化默认订阅配置
-- ================================================
INSERT INTO subscription (user_id, morning_enabled, evening_enabled, categories)
VALUES ('default', TRUE, TRUE, '["AI", "LLM", "开源"]')
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;

-- ================================================
-- 验证
-- ================================================
SELECT '数据库初始化完成！' AS status;
SHOW TABLES;
