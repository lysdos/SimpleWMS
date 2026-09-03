-- ============================================================
--  SimpleWMS 数据库初始化脚本
--  兼容 H2 (MODE=MySQL) 与 MySQL 5.7+/8.0+
-- ============================================================

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
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_log_product ON inventory_log(product_id);
CREATE INDEX IF NOT EXISTS idx_log_created ON inventory_log(created_at);
CREATE INDEX IF NOT EXISTS idx_log_type    ON inventory_log(type);

-- ============================================================
-- 入库单 (多货一次性入库,允许不同行来自不同供应商)
-- ============================================================
CREATE TABLE IF NOT EXISTS inbound_order (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL UNIQUE,
    external_no     VARCHAR(128) DEFAULT NULL,
    remark          VARCHAR(255) DEFAULT NULL,
    operator        VARCHAR(64)  NOT NULL,
    total_quantity  INT          NOT NULL DEFAULT 0,
    item_count      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inbound_order_created ON inbound_order(created_at);

CREATE TABLE IF NOT EXISTS inbound_order_item (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_id        INT          NOT NULL,
    product_id      INT          NOT NULL,
    sku             VARCHAR(64)  NOT NULL,
    product_name    VARCHAR(128) NOT NULL,
    supplier        VARCHAR(128) DEFAULT NULL,
    quantity        INT          NOT NULL,
    unit_price      DECIMAL(12,2) NOT NULL DEFAULT 0,
    before_qty      INT          NOT NULL DEFAULT 0,
    after_qty       INT          NOT NULL DEFAULT 0,
    row_remark      VARCHAR(255) DEFAULT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inbound_item_order ON inbound_order_item(order_id);
CREATE INDEX IF NOT EXISTS idx_inbound_item_product ON inbound_order_item(product_id);

-- ============================================================
-- 出库单 (多货一次性出库)
-- ============================================================
CREATE TABLE IF NOT EXISTS outbound_order (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL UNIQUE,
    customer        VARCHAR(128) DEFAULT NULL,
    external_no     VARCHAR(128) DEFAULT NULL,
    remark          VARCHAR(255) DEFAULT NULL,
    operator        VARCHAR(64)  NOT NULL,
    total_quantity  INT          NOT NULL DEFAULT 0,
    item_count      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_outbound_order_created ON outbound_order(created_at);

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
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_outbound_item_order ON outbound_order_item(order_id);
CREATE INDEX IF NOT EXISTS idx_outbound_item_product ON outbound_order_item(product_id);

-- ============================================================
-- 用户表 (用户与角色)
-- ============================================================
CREATE TABLE IF NOT EXISTS app_user (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_pw  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_app_user_username ON app_user(username);

-- ============================================================
-- 供应商主数据
-- ============================================================
CREATE TABLE IF NOT EXISTS supplier (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    contact     VARCHAR(64)  DEFAULT NULL,
    phone       VARCHAR(32)  DEFAULT NULL,
    email       VARCHAR(128) DEFAULT NULL,
    address     VARCHAR(255) DEFAULT NULL,
    remark      VARCHAR(255) DEFAULT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_name ON supplier(name);
CREATE INDEX IF NOT EXISTS idx_supplier_enabled ON supplier(enabled);

-- ============================================================
-- 商品-供应商多对多关联(可选默认)
-- ============================================================
CREATE TABLE IF NOT EXISTS product_supplier (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    product_id   INT          NOT NULL,
    supplier_id  INT          NOT NULL,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    unit_price   DECIMAL(12,2) DEFAULT NULL,
    remark       VARCHAR(255) DEFAULT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_supplier UNIQUE (product_id, supplier_id)
);
CREATE INDEX IF NOT EXISTS idx_ps_product  ON product_supplier(product_id);
CREATE INDEX IF NOT EXISTS idx_ps_supplier ON product_supplier(supplier_id);
CREATE INDEX IF NOT EXISTS idx_ps_default  ON product_supplier(product_id, is_default);