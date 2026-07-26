-- MySQL 8 manual migration. Back up the database before execution.

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

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'market_valuation_history'
          AND column_name = 'percentile_method'
    ),
    'SELECT 1',
    'ALTER TABLE market_valuation_history ADD COLUMN percentile_method VARCHAR(64) NULL AFTER pe_percentile'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

UPDATE market_valuation_history
SET percentile_method = CASE
    WHEN source LIKE '%中证指数官网PE(TTM)，滚动10年分位%'
        THEN 'CSI_PE_TTM_ROLLING_10Y'
    WHEN source LIKE '%蛋卷%'
        THEN 'DANJUAN_PE_TTM_PROVIDER'
    ELSE 'LEGACY_UNKNOWN'
END
WHERE percentile_method IS NULL OR percentile_method = '';

ALTER TABLE market_valuation_history
    MODIFY COLUMN percentile_method VARCHAR(64) NOT NULL COMMENT 'PE 分位计算口径';

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'market_valuation_history'
          AND index_name = 'uk_index_trade_date'
    ),
    'ALTER TABLE market_valuation_history DROP INDEX uk_index_trade_date',
    'SELECT 1'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'market_valuation_history'
          AND index_name = 'uk_index_trade_date_method'
    ),
    'SELECT 1',
    'ALTER TABLE market_valuation_history ADD UNIQUE KEY uk_index_trade_date_method (index_code, trade_date, percentile_method)'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'market_valuation_history'
          AND index_name = 'idx_index_method_trade_date'
    ),
    'SELECT 1',
    'ALTER TABLE market_valuation_history ADD INDEX idx_index_method_trade_date (index_code, percentile_method, trade_date DESC)'
);
PREPARE migration_statement FROM @sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
