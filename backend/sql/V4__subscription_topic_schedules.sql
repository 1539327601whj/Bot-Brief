-- ============================================================
-- V4 订阅主题时间配置
-- ============================================================

ALTER TABLE subscription
    ADD COLUMN IF NOT EXISTS topic_schedules JSON DEFAULT NULL COMMENT '早/晚间版按主题配置的推送时间';
