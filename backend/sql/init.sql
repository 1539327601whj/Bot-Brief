-- AI 每日简报 - MySQL 数据库初始化脚本

CREATE DATABASE IF NOT EXISTS ai_daily
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE ai_daily;

CREATE TABLE IF NOT EXISTS reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    edition VARCHAR(40) NOT NULL COMMENT '版本：morning/evening/etf_morning/etf_evening/market_watch_morning/market_watch_evening',
    report_date DATE DEFAULT NULL COMMENT '报告业务日期（北京时间）',
    title VARCHAR(255) NOT NULL COMMENT '简报标题',
    content LONGTEXT NOT NULL COMMENT '简报正文（Markdown）',
    summary VARCHAR(500) DEFAULT NULL COMMENT '摘要（列表展示用）',
    run_id VARCHAR(50) DEFAULT NULL COMMENT 'GitHub Actions Run ID',
    ingest_key VARCHAR(100) DEFAULT NULL COMMENT '报告入库幂等键',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_edition (edition),
    INDEX idx_created_at (created_at),
    UNIQUE KEY uk_reports_ingest_key (ingest_key),
    UNIQUE KEY uk_reports_edition_report_date (edition, report_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 简报表';

CREATE TABLE IF NOT EXISTS market_valuation_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    index_code VARCHAR(32) NOT NULL COMMENT '指数代码',
    index_name VARCHAR(100) NOT NULL COMMENT '指数名称',
    pe_ttm DECIMAL(12, 4) DEFAULT NULL COMMENT 'PE TTM',
    pe_percentile DECIMAL(8, 4) DEFAULT NULL COMMENT 'PE 分位',
    percentile_method VARCHAR(64) NOT NULL COMMENT 'PE 分位计算口径',
    valuation_level VARCHAR(20) DEFAULT NULL COMMENT '估值状态',
    trade_date DATE NOT NULL COMMENT '交易日期',
    source VARCHAR(100) DEFAULT NULL COMMENT '数据来源',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_index_trade_date_method (index_code, trade_date, percentile_method),
    INDEX idx_index_method_trade_date (index_code, percentile_method, trade_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='市场估值历史表';

CREATE TABLE IF NOT EXISTS etf_price_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    fund_code VARCHAR(32) NOT NULL COMMENT '基金代码',
    fund_name VARCHAR(100) NOT NULL COMMENT '基金名称',
    trade_date DATE NOT NULL COMMENT '交易日期',
    open_price DECIMAL(18, 6) NOT NULL COMMENT '开盘价',
    high_price DECIMAL(18, 6) NOT NULL COMMENT '最高价',
    low_price DECIMAL(18, 6) NOT NULL COMMENT '最低价',
    close_price DECIMAL(18, 6) NOT NULL COMMENT '收盘价',
    adjustment_type VARCHAR(16) NOT NULL COMMENT '复权类型，例如 QFQ',
    source VARCHAR(100) NOT NULL COMMENT '数据来源',
    fetched_at DATETIME NOT NULL COMMENT '数据抓取时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT chk_etf_price_positive CHECK (open_price > 0 AND high_price > 0 AND low_price > 0 AND close_price > 0),
    CONSTRAINT chk_etf_price_ohlc CHECK (high_price >= open_price AND high_price >= close_price AND low_price <= open_price AND low_price <= close_price),
    CONSTRAINT chk_etf_adjustment_type CHECK (adjustment_type IN ('QFQ')),
    CONSTRAINT chk_etf_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_etf_price_identity (fund_code, trade_date, adjustment_type, source),
    INDEX idx_etf_price_latest (fund_code, adjustment_type, trade_date DESC),
    INDEX idx_etf_price_fetched_at (fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ETF 历史行情表';

CREATE TABLE IF NOT EXISTS subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    user_id BIGINT NOT NULL DEFAULT 1 COMMENT '归属用户',
    receive_time VARCHAR(20) NOT NULL DEFAULT 'both' COMMENT '接收时间：morning / evening / both',
    preference_fields JSON DEFAULT NULL COMMENT '偏好领域 JSON 数组',
    topic_schedules JSON DEFAULT NULL COMMENT '早/晚间版按主题配置的推送时间',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用订阅：1 启用 0 暂停',
    morning_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否接收早间版',
    morning_time TIME NOT NULL DEFAULT '08:00:00' COMMENT '早间版时间',
    evening_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否接收晚间版',
    evening_time TIME NOT NULL DEFAULT '20:00:00' COMMENT '晚间版时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_subscription_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅配置表';

INSERT INTO subscription (id, user_id, receive_time, preference_fields, enabled)
VALUES (1, 1, 'both', '["AI大模型", "Web开发"]', 1)
ON DUPLICATE KEY UPDATE
    receive_time = VALUES(receive_time),
    preference_fields = VALUES(preference_fields),
    enabled = VALUES(enabled),
    updated_at = CURRENT_TIMESTAMP;
