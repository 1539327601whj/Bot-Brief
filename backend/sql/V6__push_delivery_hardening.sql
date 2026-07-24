-- MySQL 8 manual migration. Execute exactly once after backing up the database.
ALTER TABLE push_channel
    MODIFY COLUMN target VARCHAR(2500) NOT NULL COMMENT '加密后的邮箱地址或 webhook URL',
    MODIFY COLUMN secret VARCHAR(1000) NULL COMMENT '加密后的钉钉/飞书签名密钥';

ALTER TABLE push_log
    ADD COLUMN dispatch_key VARCHAR(191) NULL AFTER error_message,
    ADD UNIQUE INDEX uk_push_log_dispatch_key (dispatch_key);

CREATE INDEX idx_subscription_morning_due
    ON subscription (enabled, morning_enabled, morning_time);
CREATE INDEX idx_subscription_evening_due
    ON subscription (enabled, evening_enabled, evening_time);
