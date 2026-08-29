USE ai_daily;

CREATE TABLE IF NOT EXISTS ops_heartbeat (
    name VARCHAR(64) PRIMARY KEY,
    last_seen DATETIME NOT NULL,
    detail VARCHAR(255) DEFAULT NULL
) COMMENT='内部任务心跳，如订阅 poller';

CREATE TABLE IF NOT EXISTS topic_generation_status (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    section_date DATE NOT NULL,
    window_key VARCHAR(32) NOT NULL,
    topic_key VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT 'ready / skipped_no_news / failed',
    message VARCHAR(255) DEFAULT NULL,
    run_id VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_generation_status (section_date, window_key, topic_key),
    KEY idx_generation_status_date (section_date)
) COMMENT='主题段生成结果，供网页展示准备中/无资讯/失败';
