-- 日报业务日期幂等：同一自然日同一版次只保留一条
-- 历史记录暂不回填或删除；report_date 保持可空，待审计后单独处理。
USE ai_daily;

ALTER TABLE reports
    ADD COLUMN report_date DATE DEFAULT NULL COMMENT '报告业务日期（北京时间）' AFTER edition,
    ADD UNIQUE KEY uk_reports_edition_report_date (edition, report_date);
