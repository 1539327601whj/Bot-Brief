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

CREATE TABLE IF NOT EXISTS content_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    platform VARCHAR(40) NOT NULL COMMENT 'douyin|xiaohongshu|kuaishou|bilibili',
    account_name VARCHAR(120) NOT NULL,
    homepage_url VARCHAR(1000) DEFAULT NULL,
    avatar_url VARCHAR(1000) DEFAULT NULL,
    follower_count BIGINT NOT NULL DEFAULT 0,
    account_positioning VARCHAR(500) DEFAULT NULL,
    bind_status VARCHAR(40) NOT NULL DEFAULT 'manual',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_content_account_user (user_id),
    INDEX idx_content_account_user_platform (user_id, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容增长账号表';

CREATE TABLE IF NOT EXISTS content_work (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    platform VARCHAR(40) NOT NULL,
    title VARCHAR(500) NOT NULL,
    cover_url VARCHAR(1000) DEFAULT NULL,
    work_url VARCHAR(1000) DEFAULT NULL,
    publish_time DATETIME DEFAULT NULL,
    play_count BIGINT NOT NULL DEFAULT 0,
    like_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    collect_count BIGINT NOT NULL DEFAULT 0,
    share_count BIGINT NOT NULL DEFAULT 0,
    follower_gain BIGINT NOT NULL DEFAULT 0,
    content_type VARCHAR(40) NOT NULL DEFAULT 'video',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_content_work_user (user_id),
    INDEX idx_content_work_account (account_id),
    INDEX idx_content_work_publish (user_id, publish_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容增长作品数据表';

CREATE TABLE IF NOT EXISTS content_growth_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT DEFAULT NULL,
    analysis_type VARCHAR(60) NOT NULL COMMENT 'hot_analysis|topic_recommendation|rewrite_advice',
    input_text TEXT DEFAULT NULL,
    result_text MEDIUMTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_content_analysis_user (user_id, created_at),
    INDEX idx_content_analysis_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容增长 AI 分析记录表';

CREATE TABLE IF NOT EXISTS competitor_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    platform VARCHAR(40) NOT NULL,
    account_name VARCHAR(120) NOT NULL,
    homepage_url VARCHAR(1000) DEFAULT NULL,
    note VARCHAR(500) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_competitor_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='竞品账号表';

-- 「低估精选」盘后预取的清单 + 基本面快照。头一行每交易日、明细每标的每交易日。
-- 与 V13__screener_prefetch.sql 内容一致，改一处必须同时改另一处。
CREATE TABLE IF NOT EXISTS screener_universe_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL COMMENT '清单所属交易日',
    prefetch_date DATE NOT NULL COMMENT '预取执行的自然日',
    pool_size INT NOT NULL COMMENT '本次请求的池子大小 N',
    listed_count INT NOT NULL COMMENT '清单取到的条数',
    missing_count INT NOT NULL COMMENT '清单有、行情没取到、已剔除的条数',
    cap_floor DECIMAL(24, 4) DEFAULT NULL COMMENT '池内最小总市值（元）',
    source VARCHAR(100) NOT NULL COMMENT '数据来源',
    fetched_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_screener_universe_counts CHECK (pool_size > 0 AND listed_count >= 0 AND missing_count >= 0),
    CONSTRAINT chk_screener_universe_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_screener_universe_trade_date (trade_date),
    INDEX idx_screener_universe_prefetch_date (prefetch_date),
    INDEX idx_screener_universe_fetched_at (fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选全市场快照头';

CREATE TABLE IF NOT EXISTS screener_universe_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_trade_date DATE NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(100) DEFAULT NULL,
    market TINYINT NOT NULL,
    is_etf TINYINT NOT NULL DEFAULT 0,
    industry VARCHAR(100) DEFAULT NULL,
    price DECIMAL(18, 6) DEFAULT NULL,
    pct_change DECIMAL(12, 4) DEFAULT NULL,
    amount DECIMAL(24, 4) DEFAULT NULL,
    turnover_rate DECIMAL(12, 4) DEFAULT NULL,
    total_market_cap DECIMAL(24, 4) DEFAULT NULL,
    float_market_cap DECIMAL(24, 4) DEFAULT NULL,
    pb DECIMAL(18, 6) DEFAULT NULL,
    pe_ttm DECIMAL(18, 6) DEFAULT NULL,
    roe DECIMAL(12, 4) DEFAULT NULL,
    revenue_growth DECIMAL(12, 4) DEFAULT NULL,
    profit_growth DECIMAL(12, 4) DEFAULT NULL,
    gross_margin DECIMAL(12, 4) DEFAULT NULL,
    debt_ratio DECIMAL(12, 4) DEFAULT NULL,
    dividend_yield DECIMAL(12, 4) DEFAULT NULL,
    bps DECIMAL(18, 6) DEFAULT NULL,
    list_date DATE DEFAULT NULL,
    change_60d DECIMAL(12, 4) DEFAULT NULL,
    ytd_change DECIMAL(12, 4) DEFAULT NULL,
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_screener_stock_market CHECK (market IN (0, 1)),
    CONSTRAINT chk_screener_stock_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_screener_universe_stock (snapshot_trade_date, stock_code),
    INDEX idx_screener_universe_stock_code (stock_code, snapshot_trade_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选全市场快照明细';

-- 「低估精选」筛选历史：管理员每点一次「开始筛选」，成功就落一行。
-- 与 V14__screener_scan_history.sql 内容一致，改一处必须同时改另一处。
-- 只增不改的审计台账：列表页读摘要列，详情读 result_json 整页回放。
CREATE TABLE IF NOT EXISTS screener_scan_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scanned_at DATETIME NOT NULL COMMENT '本次计算时间（Asia/Shanghai）',
    scanned_by_user_id BIGINT DEFAULT NULL COMMENT '触发筛选的用户 ID',
    scanned_by_email VARCHAR(255) DEFAULT NULL COMMENT '触发人展示用',
    mode VARCHAR(20) NOT NULL COMMENT 'index_first / index_only / stock_only',
    bucket VARCHAR(20) NOT NULL COMMENT 'both / steady / growth',
    per_bucket INT NOT NULL COMMENT '每档输出只数',
    params_json LONGTEXT NOT NULL COMMENT '生效参数快照（解析默认值之后，回填条件面板用）',
    scanned_count INT NOT NULL COMMENT '扫描总数',
    after_vetoes INT NOT NULL COMMENT '通过排雷数',
    steady_pool INT NOT NULL COMMENT '稳健档池子',
    growth_pool INT NOT NULL COMMENT '成长档池子',
    shortlist_fetched INT NOT NULL COMMENT '抓取日线只数',
    index_count INT NOT NULL COMMENT '指数池条目数',
    qualified_index_count INT NOT NULL COMMENT '其中本次入选（合格）的指数数',
    selection_json LONGTEXT NOT NULL COMMENT '入选清单：指数/稳健/成长的代码与名称',
    result_json LONGTEXT NOT NULL COMMENT '完整结果快照，可整页恢复',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_screener_history_mode CHECK (mode IN ('index_first','index_only','stock_only')),
    CONSTRAINT chk_screener_history_bucket CHECK (bucket IN ('both','steady','growth')),
    INDEX idx_screener_history_scanned_at (scanned_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选筛选历史';
