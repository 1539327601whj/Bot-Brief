-- 主题段一次生成、普通用户按勾选拼装；user_id=0 仍是 Demo/行情用的公共简报
USE ai_daily;

ALTER TABLE reports
    ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0 COMMENT '0=公共简报（Demo/行情），其他=用户拼装简报' AFTER id;

ALTER TABLE reports
    DROP INDEX uk_reports_edition_report_date;

ALTER TABLE reports
    ADD UNIQUE KEY uk_reports_user_edition_date (user_id, edition, report_date),
    ADD KEY idx_reports_user_created (user_id, created_at);

CREATE TABLE IF NOT EXISTS topic_sections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    edition VARCHAR(32) NOT NULL COMMENT 'morning / evening',
    section_date DATE NOT NULL COMMENT '业务日期（北京时间）',
    topic_key VARCHAR(64) NOT NULL COMMENT '兴趣主题，与订阅勾选项一致',
    title VARCHAR(255) DEFAULT NULL,
    content MEDIUMTEXT NOT NULL,
    summary VARCHAR(512) DEFAULT NULL,
    run_id VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_topic_section (section_date, edition, topic_key),
    KEY idx_topic_sections_lookup (edition, section_date)
) COMMENT='按主题生成的简报段落，多人勾选同一主题只存一份';
