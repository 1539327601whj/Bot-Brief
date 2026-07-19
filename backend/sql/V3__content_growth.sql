-- ============================================================
-- V3 内容增长模块：内容账号、作品数据、AI 分析、竞品账号
-- 执行方式：在 TiDB / MySQL 里直接运行本脚本
-- 幂等：可重复执行
-- ============================================================

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
