-- V5 公开 Demo 账号：手工执行迁移，可重复执行
-- 先执行 V2__multi_tenant.sql；脚本会为旧库补列，并兼容已包含该列的新库。

SET @add_account_type = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN account_type VARCHAR(20) NULL COMMENT ''账号类型 NORMAL|DEMO'' AFTER role',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'account_type'
);
PREPARE add_account_type_stmt FROM @add_account_type;
EXECUTE add_account_type_stmt;
DEALLOCATE PREPARE add_account_type_stmt;

UPDATE users
SET account_type = 'NORMAL'
WHERE account_type IS NULL;

ALTER TABLE users
    MODIFY COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '账号类型 NORMAL|DEMO' AFTER role;

SET @add_account_type_constraint = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE users ADD CONSTRAINT chk_users_account_type CHECK (account_type IN (''NORMAL'', ''DEMO''))',
        'SELECT 1'
    )
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'users'
      AND CONSTRAINT_NAME = 'chk_users_account_type'
      AND CONSTRAINT_TYPE = 'CHECK'
);
PREPARE add_account_type_constraint_stmt FROM @add_account_type_constraint;
EXECUTE add_account_type_constraint_stmt;
DEALLOCATE PREPARE add_account_type_constraint_stmt;
