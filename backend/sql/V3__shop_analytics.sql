-- ============================================================
-- V3 店铺分析：店铺 + 商品 + 销售汇总 + 客户画像 + AI 经营日报
-- 执行方式：在 TiDB / MySQL 里直接运行本脚本
-- 幂等：可重复执行
-- ============================================================

CREATE TABLE IF NOT EXISTS shop_store (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    platform VARCHAR(30) NOT NULL DEFAULT 'manual' COMMENT 'taobao|douyin|wechat_shop|pdd|kuaishou|manual',
    store_name VARCHAR(100) NOT NULL,
    external_store_id VARCHAR(100) DEFAULT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_store_user_id (user_id),
    INDEX idx_shop_store_user_platform (user_id, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺表';

CREATE TABLE IF NOT EXISTS shop_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    platform VARCHAR(30) NOT NULL DEFAULT 'manual',
    external_product_id VARCHAR(100) DEFAULT NULL,
    product_name VARCHAR(255) NOT NULL,
    category VARCHAR(100) DEFAULT NULL,
    price DECIMAL(12,2) NOT NULL DEFAULT 0,
    stock INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_product_user_store (user_id, store_id),
    INDEX idx_shop_product_name (product_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺商品表';

CREATE TABLE IF NOT EXISTS shop_sales_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    sales_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    order_count INT NOT NULL DEFAULT 0,
    buyer_count INT NOT NULL DEFAULT 0,
    refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_sales_user_store_date (user_id, store_id, stat_date),
    INDEX idx_shop_sales_user_store (user_id, store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺每日销售汇总';

CREATE TABLE IF NOT EXISTS shop_product_sales_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    sales_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    order_count INT NOT NULL DEFAULT 0,
    quantity_sold INT NOT NULL DEFAULT 0,
    stock INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_product_sales_user_store_product_date (user_id, store_id, product_id, stat_date),
    INDEX idx_shop_product_sales_user_store_date (user_id, store_id, stat_date),
    INDEX idx_shop_product_sales_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品每日销售汇总';

CREATE TABLE IF NOT EXISTS shop_customer_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    new_customer_count INT NOT NULL DEFAULT 0,
    repeat_customer_count INT NOT NULL DEFAULT 0,
    high_value_customer_count INT NOT NULL DEFAULT 0,
    avg_customer_value DECIMAL(14,2) NOT NULL DEFAULT 0,
    female_ratio DECIMAL(5,2) DEFAULT NULL,
    male_ratio DECIMAL(5,2) DEFAULT NULL,
    top_regions JSON DEFAULT NULL,
    age_distribution JSON DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_customer_user_store_date (user_id, store_id, stat_date),
    INDEX idx_shop_customer_user_store (user_id, store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺客户画像日汇总';

CREATE TABLE IF NOT EXISTS shop_ai_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    report_date DATE NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary VARCHAR(500) DEFAULT NULL,
    content LONGTEXT NOT NULL,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'normal',
    generated_by VARCHAR(30) NOT NULL DEFAULT 'rule',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_ai_report_user_store_date (user_id, store_id, report_date),
    INDEX idx_shop_ai_report_user_store (user_id, store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺 AI 经营日报';
