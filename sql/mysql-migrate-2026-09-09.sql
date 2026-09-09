-- ============================================================
--  SimpleWMS MySQL 在线迁移(2026-09-09)
--  适用: 已经跑着的老版本容器,只对已存在的 simple_wms 库
--  增加 UHF 出库单派单字段
-- ============================================================

USE simple_wms;

-- 1. outbound_order 加 UHF 派单相关列
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'outbound_order'
      AND column_name  = 'uhf_dispatched_at'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE outbound_order ADD COLUMN uhf_dispatched_at TIMESTAMP NULL DEFAULT NULL AFTER status',
    'SELECT "uhf_dispatched_at 已存在, 跳过" AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'outbound_order'
      AND column_name  = 'uhf_dispatched_by'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE outbound_order ADD COLUMN uhf_dispatched_by VARCHAR(64) NULL DEFAULT NULL AFTER uhf_dispatched_at',
    'SELECT "uhf_dispatched_by 已存在, 跳过" AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'outbound_order'
      AND column_name  = 'uhf_device_code'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE outbound_order ADD COLUMN uhf_device_code VARCHAR(64) NULL DEFAULT NULL AFTER uhf_dispatched_by',
    'SELECT "uhf_device_code 已存在, 跳过" AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'outbound_order'
      AND column_name  = 'uhf_claimed_at'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE outbound_order ADD COLUMN uhf_claimed_at TIMESTAMP NULL DEFAULT NULL AFTER uhf_device_code',
    'SELECT "uhf_claimed_at 已存在, 跳过" AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 加索引(派单拉取用)
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name   = 'outbound_order'
      AND index_name   = 'idx_outbound_order_uhf_dispatched'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE outbound_order ADD KEY idx_outbound_order_uhf_dispatched (uhf_dispatched_at)',
    'SELECT "idx_outbound_order_uhf_dispatched 已存在, 跳过" AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '✅ 2026-09-09 迁移完成(outbound_order UHF 派单字段)' AS done;
