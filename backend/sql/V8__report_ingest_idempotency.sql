USE ai_daily;

ALTER TABLE reports
    ADD COLUMN ingest_key VARCHAR(100) DEFAULT NULL COMMENT '报告入库幂等键' AFTER run_id,
    ADD UNIQUE KEY uk_reports_ingest_key (ingest_key);
