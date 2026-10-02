-- MySQL 8 manual migration. Back up the database before execution.
--
-- 「低估精选」盘后预取：收盘后把全市场清单 + 基本面取回落库，盘后点击就读这里、不再外呼东财。
-- 存的是东财自己的数据，同一个源、同一个口径，只是取得早——所以不涉及跨口径比较（章程 §2.1/§5.3/§6）。
--
-- 为什么拆成头 + 明细两张：UniverseSnapshot 除了每只标的一行，还带
-- listedCount / missing / capFloor / tradeDate 四个「一次扫描一个」的标量。
-- 塞进明细表会重复几百行，重跑时若各行不一致就产生静默歧义——而口径摘要必须自洽。
-- 拆开之后头表每交易日一行，**同时天然就是预取的幂等闸门**。
--
-- 为什么个股日线不在这里：全池预取日线要每天打 300 次 push2his（300 只各一条），
-- 而日线一次点击只占 16 次外呼——按本项目的使用量是净亏，且把散在全天的量挤成一个
-- 收盘后的大批次，恰恰更像东财会判定为异常的形态。日线保持实时 + 现有内存缓存。

CREATE TABLE IF NOT EXISTS screener_universe_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    trade_date DATE NOT NULL COMMENT '清单所属交易日（东财 latestTradeDate）',
    prefetch_date DATE NOT NULL COMMENT '预取任务执行的自然日',
    pool_size INT NOT NULL COMMENT '本次清单请求的池子大小 N（总市值前 N 只）',
    listed_count INT NOT NULL COMMENT '清单按市值取到的条数',
    missing_count INT NOT NULL COMMENT '清单里有、行情没取到、已从池子剔除的条数',
    cap_floor DECIMAL(24, 4) DEFAULT NULL COMMENT '池内最小总市值（元），可能为 NULL',
    source VARCHAR(100) NOT NULL COMMENT '数据来源',
    fetched_at DATETIME NOT NULL COMMENT '数据抓取时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT chk_screener_universe_counts CHECK (pool_size > 0 AND listed_count >= 0 AND missing_count >= 0),
    CONSTRAINT chk_screener_universe_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_screener_universe_trade_date (trade_date),
    INDEX idx_screener_universe_prefetch_date (prefetch_date),
    INDEX idx_screener_universe_fetched_at (fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选全市场快照头（每交易日一行，兼作幂等闸门）';

-- 字段与 StockRow 一一对应。**全部可空**：章程 §6 要求「缺了就是缺了」，
-- 行情接口没给的字段必须是 NULL，不能写成 0——写成 0 会让它看起来是一家
-- 「PE=0、负债率=0」的好公司，正好穿过排雷规则。
CREATE TABLE IF NOT EXISTS screener_universe_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    snapshot_trade_date DATE NOT NULL COMMENT '所属快照的交易日',
    stock_code VARCHAR(16) NOT NULL COMMENT '交易代码（6 位）',
    stock_name VARCHAR(100) DEFAULT NULL COMMENT '证券简称',
    market TINYINT NOT NULL COMMENT '东财市场标识：1=沪，0=深',
    is_etf TINYINT NOT NULL DEFAULT 0 COMMENT '是否 ETF，原样存解析结果，读侧不重算',
    industry VARCHAR(100) DEFAULT NULL COMMENT '东财行业分类（f100）',
    price DECIMAL(18, 6) DEFAULT NULL COMMENT '最新价（f2）',
    pct_change DECIMAL(12, 4) DEFAULT NULL COMMENT '当日涨跌幅 %（f3）',
    amount DECIMAL(24, 4) DEFAULT NULL COMMENT '当日成交额，元（f6）',
    turnover_rate DECIMAL(12, 4) DEFAULT NULL COMMENT '换手率 %（f8）',
    total_market_cap DECIMAL(24, 4) DEFAULT NULL COMMENT '总市值，元（f20）',
    float_market_cap DECIMAL(24, 4) DEFAULT NULL COMMENT '流通市值，元（f21）',
    pb DECIMAL(18, 6) DEFAULT NULL COMMENT '市净率（f23）',
    pe_ttm DECIMAL(18, 6) DEFAULT NULL COMMENT 'PE(TTM)，亏损可为负（f115）',
    roe DECIMAL(12, 4) DEFAULT NULL COMMENT 'ROE 加权 %（f37）',
    revenue_growth DECIMAL(12, 4) DEFAULT NULL COMMENT '营收同比 %（f41）',
    profit_growth DECIMAL(12, 4) DEFAULT NULL COMMENT '净利同比 %（f46）',
    gross_margin DECIMAL(12, 4) DEFAULT NULL COMMENT '毛利率 %（f49）',
    debt_ratio DECIMAL(12, 4) DEFAULT NULL COMMENT '资产负债率 %（f57）',
    dividend_yield DECIMAL(12, 4) DEFAULT NULL COMMENT '股息率 %（f133）',
    bps DECIMAL(18, 6) DEFAULT NULL COMMENT '每股净资产（f113）',
    list_date DATE DEFAULT NULL COMMENT '上市日期（f26）',
    change_60d DECIMAL(12, 4) DEFAULT NULL COMMENT '60 日涨跌幅 %（f24）',
    ytd_change DECIMAL(12, 4) DEFAULT NULL COMMENT '年初至今涨跌幅 %（f25）',
    source VARCHAR(100) NOT NULL COMMENT '数据来源（基本面一律来自东财 ulist）',
    fetched_at DATETIME NOT NULL COMMENT '数据抓取时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT chk_screener_stock_market CHECK (market IN (0, 1)),
    CONSTRAINT chk_screener_stock_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    UNIQUE KEY uk_screener_universe_stock (snapshot_trade_date, stock_code),
    INDEX idx_screener_universe_stock_code (stock_code, snapshot_trade_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选全市场快照明细（每只标的每交易日一行）';