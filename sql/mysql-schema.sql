-- ============================================================
--  SimpleWMS MySQL 部署脚本
--  适用: MySQL 5.7+ / 8.0+
-- ============================================================

CREATE DATABASE IF NOT EXISTS simple_wms
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE simple_wms;

-- 商品表
CREATE TABLE IF NOT EXISTS product (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    sku          VARCHAR(64)   NOT NULL UNIQUE,
    name         VARCHAR(128)  NOT NULL,
    category     VARCHAR(64)   NOT NULL DEFAULT '默认',
    unit         VARCHAR(16)   NOT NULL DEFAULT '件',
    location     VARCHAR(64)   NOT NULL DEFAULT '默认库位',
    quantity     INT           NOT NULL DEFAULT 0,
    unit_price   DECIMAL(12,2) NOT NULL DEFAULT 0,
    low_stock    INT           NOT NULL DEFAULT 10,
    remark       VARCHAR(255)  DEFAULT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 流水表
CREATE TABLE IF NOT EXISTS inventory_log (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    type         VARCHAR(16)   NOT NULL,
    product_id   INT           NOT NULL,
    sku          VARCHAR(64)   NOT NULL,
    product_name VARCHAR(128)  NOT NULL,
    quantity     INT           NOT NULL,
    before_qty   INT           NOT NULL DEFAULT 0,
    after_qty    INT           NOT NULL DEFAULT 0,
    remark       VARCHAR(255)  DEFAULT NULL,
    operator     VARCHAR(64)   DEFAULT NULL,
    order_id     INT           DEFAULT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_log_product (product_id),
    KEY idx_log_created (created_at),
    KEY idx_log_type    (type),
    KEY idx_log_order   (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 入库单 (多货一次性入库)
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

-- 出库单 (多货一次性出库)
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