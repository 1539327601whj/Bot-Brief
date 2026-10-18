-- MySQL 8 manual migration. Back up the database before execution.
-- 与 backend/sql/init.sql 里同名表保持一致，改一处必须同时改另一处。
--
-- 「代码查询 v2」的本地数据底座：把指数 / ETF / 个股的行情、估值、财务、资金流、
-- 行业与事件逐步搬到本地，重复查询不再打源（章程 automation/agents/code_lookup_analysis_rules.md）。
--
-- 几条设计约束，改表前先读：
--   1. **一张表只允许一个主写方**：Java 写用户查询触发的个股按需数据，Python warehouse
--      写指数/ETF/行业/新闻/批量回填。重叠表靠唯一键幂等 upsert，旧 fetched_at 不覆盖新数据。
--   2. **缺失一律 NULL，绝不写 0**。0 会让「没取到」在打分时伪装成一个极差的值。
--   3. instrument_code 是**带类型前缀的稳定键**：STOCK:300274 / ETF:510300 / INDEX:000300。
--      6 位代码会跨类型撞车，裸代码不能当主键。
--   4. 每张日表都有 source 与 fetched_at；同一序列只允许一个 source，跨源冲突不覆盖。
--   5. 所有建表语句都是 CREATE TABLE IF NOT EXISTS，可重复执行；本文件不含 DROP/TRUNCATE。
--
-- 权限说明：warehouse 服务使用独立 DB 用户，只授 md_* 的 SELECT/INSERT/UPDATE，
-- 不授 DDL/DELETE。CREATE USER / GRANT 不写在本迁移里（应用用户未必有授权权限），
-- 见 README 的「warehouse DB 用户」一节。

-- =====================================================================
-- 0. 已有指数估值表的兼容扩展
-- =====================================================================
-- 指数 PE 历史继续读 market_valuation_history（已有唯一键与分位方法白名单），
-- 不新建 md_index_valuation_daily 重复存一份。PB / 股息率原先没有列，这里补上。
-- WarehouseSchemaGuard 只检查、不自动 ALTER：缺列时 v2 拒绝启动并提示执行本文件。

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'market_valuation_history'
          AND column_name = 'pb'
    ),
    'SELECT 1',
    'ALTER TABLE market_valuation_history ADD COLUMN pb DECIMAL(12, 4) NULL AFTER pe_percentile'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'market_valuation_history'
          AND column_name = 'dividend_yield'
    ),
    'SELECT 1',
    'ALTER TABLE market_valuation_history ADD COLUMN dividend_yield DECIMAL(12, 4) NULL AFTER pb'
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
    `cursor` VARCHAR(255) DEFAULT NULL COMMENT '续跑游标',
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
