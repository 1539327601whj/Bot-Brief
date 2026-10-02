-- MySQL 8 manual migration. Back up the database before execution.
-- 与 backend/sql/init.sql 里那一段内容一致，改一处必须同时改另一处
-- （同 ScreenerSchemaRepair.CREATE_SQL；后端启动时也会按 information_schema 补建缺的表）。
--
-- 「低估精选」筛选历史：管理员每点一次「开始筛选」，成功就落一行，带具体时间，
-- 可回看当时的条件、摘要与入选清单，并整页回放（result_json 是当初那次 scan 的返回体）。
--
-- 为什么只增不改、还同时存摘要列 + 完整快照：
--   摘要列（扫描数 / 通过排雷 / 合格指数数 / 入选清单）让列表页不必解析几十 KB 的 JSON；
--   result_json 是为了回答用户真正问的那句「这次和上次差在哪」——只给摘要对不上。
--
-- 为什么写失败不影响筛选结果：历史是审计附属品。写失败时后端会往返回体的降级说明里
-- 挂一句「历史没存下来」，让用户看得见，而不是让一次成功的筛选变成错误页。

CREATE TABLE IF NOT EXISTS screener_scan_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scanned_at DATETIME NOT NULL COMMENT '本次计算时间（Asia/Shanghai）',
    scanned_by_user_id BIGINT DEFAULT NULL COMMENT '触发筛选的用户 ID',
    scanned_by_email VARCHAR(255) DEFAULT NULL COMMENT '触发人展示用',
    mode VARCHAR(20) NOT NULL COMMENT 'index_first / index_only / stock_only',
    bucket VARCHAR(20) NOT NULL COMMENT 'both / steady / growth',
    per_bucket INT NOT NULL COMMENT '每档输出只数',
    params_json LONGTEXT NOT NULL COMMENT '生效参数快照（解析默认值之后，回填条件面板用）',
    scanned_count INT NOT NULL COMMENT '扫描总数',
    after_vetoes INT NOT NULL COMMENT '通过排雷数',
    steady_pool INT NOT NULL COMMENT '稳健档池子',
    growth_pool INT NOT NULL COMMENT '成长档池子',
    shortlist_fetched INT NOT NULL COMMENT '抓取日线只数',
    index_count INT NOT NULL COMMENT '指数池条目数',
    qualified_index_count INT NOT NULL COMMENT '其中本次入选（合格）的指数数',
    selection_json LONGTEXT NOT NULL COMMENT '入选清单：指数/稳健/成长的代码与名称',
    result_json LONGTEXT NOT NULL COMMENT '完整结果快照，可整页恢复',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_screener_history_mode CHECK (mode IN ('index_first','index_only','stock_only')),
    CONSTRAINT chk_screener_history_bucket CHECK (bucket IN ('both','steady','growth')),
    INDEX idx_screener_history_scanned_at (scanned_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选筛选历史';