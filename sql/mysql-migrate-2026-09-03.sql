-- ============================================================
--  SimpleWMS MySQL 在线迁移(2026-09-03)
--  适用: 已经跑着的老版本容器,只对已存在的 simple_wms 库
--  同步多货入库/出库单功能
-- ============================================================

USE simple_wms;

-- 1. 给 inventory_log 补 order_id 列(老库)
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'simple_wms'
      AND TABLE_NAME   = 'inventory_log'
      AND COLUMN_NAME  = 'order_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE inventory_log ADD COLUMN order_id INT DEFAULT NULL, ADD KEY idx_log_order (order_id)',
    'SELECT "order_id already exists" AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 入库单 + 明细
CREATE TABLE IF NOT EXISTS inbound_order (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL UNIQUE,
    supplier        VARCHAR(128) DEFAULT NULL,
    external_no     VARCHAR(128) DEFAULT NULL,
    remark          VARCHAR(255) DEFAULT NULL,
    operator        VARCHAR(64)  NOT NULL,
    total_quantity  INT          NOT NULL DEFAULT 0,
    item_count      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_inbound_order_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inbound_order_item (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_id        INT          NOT NULL,
    product_id      INT          NOT NULL,
    sku             VARCHAR(64)  NOT NULL,
    product_name    VARCHAR(128) NOT NULL,
    quantity        INT          NOT NULL,
    unit_price      DECIMAL(12,2) NOT NULL DEFAULT 0,
    before_qty      INT          NOT NULL DEFAULT 0,
    after_qty       INT          NOT NULL DEFAULT 0,
    row_remark      VARCHAR(255) DEFAULT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_inbound_item_order (order_id),
    KEY idx_inbound_item_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 出库单 + 明细
CREATE TABLE IF NOT EXISTS outbound_order (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL UNIQUE,
    customer        VARCHAR(128) DEFAULT NULL,
    external_no     VARCHAR(128) DEFAULT NULL,
    remark          VARCHAR(255) DEFAULT NULL,
    operator        VARCHAR(64)  NOT NULL,
    total_quantity  INT          NOT NULL DEFAULT 0,
    item_count      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_outbound_order_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS outbound_order_item (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_id        INT          NOT NULL,
    product_id      INT          NOT NULL,
    sku             VARCHAR(64)  NOT NULL,
    product_name    VARCHAR(128) NOT NULL,
    quantity        INT          NOT NULL,
    unit_price      DECIMAL(12,2) NOT NULL DEFAULT 0,
    before_qty      INT          NOT NULL DEFAULT 0,
    after_qty       INT          NOT NULL DEFAULT 0,
    row_remark      VARCHAR(255) DEFAULT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_outbound_item_order (order_id),
    KEY idx_outbound_item_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 给 inbound_order / outbound_order 补 status 列(老库)
--    状态机: DRAFT=草稿 / COMMITTED=已提交 / CANCELLED=已取消
--    老数据默认值 DRAFT,与 InboundOrder.STATUS_DRAFT 保持一致
--    同时给 status 加索引,方便按状态筛选

-- 4.1 inbound_order.status
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'simple_wms'
      AND TABLE_NAME   = 'inbound_order'
      AND COLUMN_NAME  = 'status'
);
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'simple_wms'
      AND TABLE_NAME   = 'inbound_order'
      AND INDEX_NAME   = 'idx_inbound_order_status'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE inbound_order ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''DRAFT'' AFTER item_count',
    'SELECT "inbound_order.status already exists" AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE inbound_order ADD KEY idx_inbound_order_status (status)',
    'SELECT "idx_inbound_order_status already exists" AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4.2 outbound_order.status
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'simple_wms'
      AND TABLE_NAME   = 'outbound_order'
      AND COLUMN_NAME  = 'status'
);
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'simple_wms'
      AND TABLE_NAME   = 'outbound_order'
      AND INDEX_NAME   = 'idx_outbound_order_status'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE outbound_order ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''DRAFT'' AFTER item_count',
    'SELECT "outbound_order.status already exists" AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE outbound_order ADD KEY idx_outbound_order_status (status)',
    'SELECT "idx_outbound_order_status already exists" AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. 确认
SHOW TABLES;
