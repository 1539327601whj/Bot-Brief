-- 按四个时间段生成主题段；用户简报按自己选的时刻展示/推送
-- 旧的 topic_schedules JSON（morning/evening 分组）会在下次保存订阅时改写成 items
USE ai_daily;

ALTER TABLE reports
    ADD COLUMN display_time TIME DEFAULT NULL COMMENT '用户选定的展示/推送时刻；公共报为固定展示时刻' AFTER report_date;

UPDATE reports SET display_time = '08:00:00' WHERE user_id = 0 AND edition = 'morning' AND display_time IS NULL;
UPDATE reports SET display_time = '20:00:00' WHERE user_id = 0 AND edition = 'evening' AND display_time IS NULL;
UPDATE reports SET display_time = '18:00:00' WHERE user_id = 0 AND edition LIKE 'market_watch%' AND display_time IS NULL;
UPDATE reports SET display_time = '08:00:00' WHERE display_time IS NULL;

ALTER TABLE reports
    DROP INDEX uk_reports_user_edition_date;

ALTER TABLE reports
    ADD UNIQUE KEY uk_reports_user_edition_date_time (user_id, edition, report_date, display_time);

UPDATE topic_sections SET edition = 'w06_12' WHERE edition = 'morning';
UPDATE topic_sections SET edition = 'w18_24' WHERE edition = 'evening';
