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
    pb DECIMAL(12, 4) DEFAULT NULL COMMENT 'PB（代码查询 v2 起补，见 V15）',
    dividend_yield DECIMAL(12, 4) DEFAULT NULL COMMENT '股息率 %（代码查询 v2 起补，见 V15）',
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


-- =====================================================================
-- 代码查询 v2 本地数据底座（与 V15__investment_warehouse.sql 保持一致）
-- =====================================================================
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- =====================================================================
-- 1. 标的母表
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_instrument (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instrument_code VARCHAR(64) NOT NULL COMMENT '带类型前缀的稳定键：STOCK:300274 / ETF:510300 / INDEX:000300',
    asset_type VARCHAR(16) NOT NULL COMMENT 'STOCK / ETF / INDEX',
    market VARCHAR(16) DEFAULT NULL COMMENT 'SH / SZ / BJ / CSI / OFFSHORE',
    raw_code VARCHAR(32) NOT NULL COMMENT '原始代码（6 位或指数代码）',
    name VARCHAR(100) DEFAULT NULL COMMENT '证券简称',
    aliases VARCHAR(500) DEFAULT NULL COMMENT '别名，逗号分隔，供本地搜索',
    industry VARCHAR(100) DEFAULT NULL COMMENT '东财行业名',
    industry_code VARCHAR(32) DEFAULT NULL COMMENT '行业板块代码',
    tracking_index_code VARCHAR(64) DEFAULT NULL COMMENT 'ETF 跟踪指数（index-pool 匹配结果）',
    list_date DATE DEFAULT NULL COMMENT '上市日期',
    status VARCHAR(32) DEFAULT NULL COMMENT 'ACTIVE / SUSPENDED / DELISTED / UNKNOWN',
    source VARCHAR(100) NOT NULL COMMENT '来源',
    fetched_at DATETIME(3) NOT NULL COMMENT '数据抓取时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_instrument_asset_type CHECK (asset_type IN ('STOCK','ETF','INDEX')),
    CONSTRAINT chk_md_instrument_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_instrument_asset_raw (asset_type, raw_code),
    INDEX idx_md_instrument_name (name),
    INDEX idx_md_instrument_industry (industry_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标的母表（股票/ETF/指数统一）';

-- =====================================================================
-- 2. 行情日表
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_price_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instrument_code VARCHAR(64) NOT NULL COMMENT 'md_instrument.instrument_code',
    trade_date DATE NOT NULL,
    adj_type VARCHAR(16) NOT NULL DEFAULT 'QFQ' COMMENT '复权类型：QFQ / NONE',
    open_price DECIMAL(18, 6) DEFAULT NULL,
    high_price DECIMAL(18, 6) DEFAULT NULL,
    low_price DECIMAL(18, 6) DEFAULT NULL,
    close_price DECIMAL(18, 6) NOT NULL,
    volume DECIMAL(24, 4) DEFAULT NULL COMMENT '成交量（手/股，按源原样存）',
    amount DECIMAL(24, 4) DEFAULT NULL COMMENT '成交额（元）',
    turnover_rate DECIMAL(12, 4) DEFAULT NULL COMMENT '换手率 %',
    pct_change DECIMAL(12, 4) DEFAULT NULL COMMENT '涨跌幅 %',
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_price_close_positive CHECK (close_price > 0),
    CONSTRAINT chk_md_price_ohlc CHECK (
        (open_price IS NULL OR open_price > 0)
        AND (high_price IS NULL OR high_price > 0)
        AND (low_price IS NULL OR low_price > 0)
        AND (high_price IS NULL OR open_price IS NULL OR high_price >= open_price)
        AND (high_price IS NULL OR high_price >= close_price)
        AND (low_price IS NULL OR open_price IS NULL OR low_price <= open_price)
        AND (low_price IS NULL OR low_price <= close_price)
    ),
    CONSTRAINT chk_md_price_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_price_identity (instrument_code, trade_date, adj_type, source),
    INDEX idx_md_price_latest (instrument_code, adj_type, trade_date DESC),
    INDEX idx_md_price_fetched_at (fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行情日表（个股/指数/ETF）';

-- =====================================================================
-- 3. 估值日表
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_valuation_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instrument_code VARCHAR(64) NOT NULL,
    trade_date DATE NOT NULL,
    pe_ttm DECIMAL(18, 6) DEFAULT NULL COMMENT 'PE(TTM)，亏损可为负',
    pb_mrq DECIMAL(18, 6) DEFAULT NULL COMMENT 'PB(MRQ)',
    ps_ttm DECIMAL(18, 6) DEFAULT NULL COMMENT 'PS(TTM)',
    dividend_yield DECIMAL(12, 4) DEFAULT NULL COMMENT '股息率 %',
    close_price DECIMAL(18, 6) DEFAULT NULL COMMENT '同一源的收盘价，供反推买入区间',
    percentile_method VARCHAR(64) DEFAULT NULL COMMENT '分位口径，与 market_valuation_history 同名字段对齐',
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_valuation_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_valuation_identity (instrument_code, trade_date, source),
    INDEX idx_md_valuation_latest (instrument_code, trade_date DESC),
    INDEX idx_md_valuation_source_date (instrument_code, source, trade_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='估值日表（个股 PE/PB 历史与指数估值）';

-- =====================================================================
-- 4. 财务报告
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_financial_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(32) NOT NULL COMMENT '原始 6 位代码',
    report_date DATE NOT NULL COMMENT '报告期',
    report_type VARCHAR(16) NOT NULL COMMENT 'Q1 / H1 / Q3 / ANNUAL',
    announce_date DATE DEFAULT NULL COMMENT '公告日',
    available_from DATE DEFAULT NULL COMMENT '数据可用日（防前视偏差；取公告日与报告期较晚者）',
    revenue DECIMAL(24, 4) DEFAULT NULL COMMENT '营业收入（元）',
    revenue_yoy DECIMAL(12, 4) DEFAULT NULL COMMENT '营收同比 %',
    net_profit DECIMAL(24, 4) DEFAULT NULL COMMENT '归母净利润（元）',
    net_profit_yoy DECIMAL(12, 4) DEFAULT NULL COMMENT '归母净利同比 %',
    roe DECIMAL(12, 4) DEFAULT NULL COMMENT 'ROE %',
    gross_margin DECIMAL(12, 4) DEFAULT NULL COMMENT '毛利率 %',
    net_margin DECIMAL(12, 4) DEFAULT NULL COMMENT '净利率 %',
    ocf DECIMAL(24, 4) DEFAULT NULL COMMENT '经营活动现金流净额（元）',
    total_assets DECIMAL(24, 4) DEFAULT NULL,
    total_equity DECIMAL(24, 4) DEFAULT NULL,
    total_liabilities DECIMAL(24, 4) DEFAULT NULL,
    current_assets DECIMAL(24, 4) DEFAULT NULL,
    current_liabilities DECIMAL(24, 4) DEFAULT NULL,
    interest_expense DECIMAL(24, 4) DEFAULT NULL,
    ebit DECIMAL(24, 4) DEFAULT NULL,
    goodwill DECIMAL(24, 4) DEFAULT NULL,
    accounts_receivable DECIMAL(24, 4) DEFAULT NULL,
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_financial_report_type CHECK (report_type IN ('Q1','H1','Q3','ANNUAL')),
    CONSTRAINT chk_md_financial_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_financial_identity (stock_code, report_date, report_type, source),
    INDEX idx_md_financial_stock_available (stock_code, available_from DESC, report_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='财务报告（东财数据中心）';

-- =====================================================================
-- 5. 资金流
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_moneyflow_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    main_net DECIMAL(24, 4) DEFAULT NULL COMMENT '主力净流入（元）',
    super_net DECIMAL(24, 4) DEFAULT NULL,
    large_net DECIMAL(24, 4) DEFAULT NULL,
    medium_net DECIMAL(24, 4) DEFAULT NULL,
    small_net DECIMAL(24, 4) DEFAULT NULL,
    main_ratio DECIMAL(12, 4) DEFAULT NULL COMMENT '主力净占比 %',
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_moneyflow_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_moneyflow_identity (stock_code, trade_date, source),
    INDEX idx_md_moneyflow_latest (stock_code, trade_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个股资金流日表';

-- =====================================================================
-- 6. 指数画像 / 权重 / 成分
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_index_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    index_code VARCHAR(64) NOT NULL,
    as_of DATE NOT NULL COMMENT '数据日期（不是抓取日期）',
    name VARCHAR(100) DEFAULT NULL,
    constituent_count INT DEFAULT NULL,
    top10_weight DECIMAL(12, 4) DEFAULT NULL COMMENT '前十大权重合计 %',
    pe_ttm DECIMAL(18, 6) DEFAULT NULL,
    dividend_yield DECIMAL(12, 4) DEFAULT NULL,
    total_market_cap DECIMAL(24, 4) DEFAULT NULL,
    tracking_index_code VARCHAR(64) DEFAULT NULL,
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_index_profile_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_index_profile_identity (index_code, as_of, source),
    INDEX idx_md_index_profile_latest (index_code, as_of DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指数画像（成分数、前十大权重、PE、股息率）';

CREATE TABLE IF NOT EXISTS md_index_industry_weight (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    index_code VARCHAR(64) NOT NULL,
    as_of DATE NOT NULL,
    industry_code VARCHAR(32) NOT NULL,
    industry_name VARCHAR(100) DEFAULT NULL,
    weight DECIMAL(12, 4) NOT NULL COMMENT '行业权重 %',
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_index_industry_weight_positive CHECK (weight >= 0),
    CONSTRAINT chk_md_index_industry_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_index_industry_identity (index_code, as_of, industry_code, source),
    INDEX idx_md_index_industry_latest (index_code, as_of DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指数行业权重（中证官网）';

CREATE TABLE IF NOT EXISTS md_index_constituent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    index_code VARCHAR(64) NOT NULL,
    as_of DATE NOT NULL,
    stock_code VARCHAR(32) NOT NULL,
    stock_name VARCHAR(100) DEFAULT NULL,
    weight DECIMAL(12, 4) DEFAULT NULL COMMENT '权重 %（只有 top10 时有值）',
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_index_constituent_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_index_constituent_identity (index_code, as_of, stock_code, source),
    INDEX idx_md_index_constituent_stock (stock_code, as_of DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指数成分（当前只确认 top10；全量待探针）';

-- =====================================================================
-- 7. 行业板块
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_industry_board (
    board_code VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '东财行业板块代码，如 BK1036',
    name VARCHAR(100) NOT NULL,
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_industry_board_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    INDEX idx_md_industry_board_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行业板块';

CREATE TABLE IF NOT EXISTS md_industry_constituent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    board_code VARCHAR(32) NOT NULL,
    stock_code VARCHAR(32) NOT NULL,
    stock_name VARCHAR(100) DEFAULT NULL,
    as_of DATE NOT NULL,
    source VARCHAR(100) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_industry_constituent_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_industry_constituent_identity (board_code, stock_code, as_of, source),
    INDEX idx_md_industry_constituent_stock (stock_code, as_of DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行业板块成分（按需拉取，不做每日全市场回填）';

-- =====================================================================
-- 8. 事件（新闻 / 公告 / 政策）
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instrument_code VARCHAR(64) DEFAULT NULL COMMENT '个股/指数键；政策与行业事件可为 NULL',
    category VARCHAR(32) NOT NULL COMMENT 'NOTICE / NEWS / POLICY',
    event_type VARCHAR(64) DEFAULT NULL COMMENT '业绩预增/减持/回购/监管处罚等',
    title VARCHAR(500) NOT NULL,
    summary TEXT DEFAULT NULL,
    url VARCHAR(1000) DEFAULT NULL,
    published_at DATETIME DEFAULT NULL,
    source VARCHAR(100) NOT NULL,
    importance VARCHAR(16) DEFAULT 'NORMAL' COMMENT 'HIGH / NORMAL / LOW',
    polarity VARCHAR(16) DEFAULT 'NEUTRAL' COMMENT 'POSITIVE / NEGATIVE / NEUTRAL / PENDING',
    content_hash VARCHAR(64) NOT NULL COMMENT '标题+来源+日期+链接的 SHA-256，去重键',
    fetched_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_event_category CHECK (category IN ('NOTICE','NEWS','POLICY')),
    CONSTRAINT chk_md_event_polarity CHECK (polarity IN ('POSITIVE','NEGATIVE','NEUTRAL','PENDING')),
    CONSTRAINT chk_md_event_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_md_event_content_hash (content_hash),
    INDEX idx_md_event_instrument_time (instrument_code, published_at DESC),
    INDEX idx_md_event_category_time (category, published_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件缓存（新闻/公告/政策，不参与普通资讯情绪打分）';

-- =====================================================================
-- 9. 分析缓存
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_analysis_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instrument_code VARCHAR(64) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL COMMENT 'facts 快照 ID',
    rules_version VARCHAR(32) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_md_analysis_cache_identity (instrument_code, snapshot_hash, rules_version, prompt_version),
    INDEX idx_md_analysis_cache_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='规则结果与 LLM 解读缓存';

-- =====================================================================
-- 10. 同步任务
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_sync_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_key VARCHAR(160) NOT NULL COMMENT '去重键：TASK:instrument:businessDate',
    task VARCHAR(40) NOT NULL COMMENT 'STOCK_DETAIL / INDEX_DETAIL / DAILY_MARKET_SNAPSHOT / DAILY_INDEX_PROFILE / DAILY_INDUSTRY / NEWS_REFRESH',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / DONE / FAILED / SKIPPED',
    priority INT NOT NULL DEFAULT 50 COMMENT '数字越大越先跑；用户任务 100，批量任务 10',
    instrument_code VARCHAR(64) DEFAULT NULL,
    payload_json TEXT DEFAULT NULL,
    cursor VARCHAR(255) DEFAULT NULL COMMENT '续跑游标',
    rows_affected INT DEFAULT NULL,
    error VARCHAR(1000) DEFAULT NULL,
    retry_after DATETIME(3) DEFAULT NULL COMMENT '限流冷却结束前不重排',
    started_at DATETIME(3) DEFAULT NULL,
    finished_at DATETIME(3) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_md_sync_job_status CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','SKIPPED')),
    UNIQUE KEY uk_md_sync_job_key (job_key),
    INDEX idx_md_sync_job_pick (status, retry_after, priority),
    INDEX idx_md_sync_job_instrument (instrument_code, task)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='同步任务队列';

-- =====================================================================
-- 11. 跨进程源状态（限流 + 冷却）
-- =====================================================================
CREATE TABLE IF NOT EXISTS md_source_state (
    provider VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'eastmoney:interactive / eastmoney:batch / tencent / csindex ...',
    next_allowed_at DATETIME(3) DEFAULT NULL COMMENT '下一次允许发车的时刻',
    cooldown_until DATETIME(3) DEFAULT NULL COMMENT '冷却截止；未到期零外呼',
    last_error VARCHAR(500) DEFAULT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='跨进程限流与冷却状态';
