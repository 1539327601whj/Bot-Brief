-- ============================================================
-- V4 店铺 CSV 导入：商品外部 ID 幂等键
-- 执行方式：在 TiDB / MySQL 里直接运行本脚本
-- 执行前请确认同一店铺内没有重复的非空 external_product_id
-- ============================================================

CREATE UNIQUE INDEX uk_shop_product_user_store_external
    ON shop_product(user_id, store_id, external_product_id);
