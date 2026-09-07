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

-- 入库单状态 (DRAFT=草稿 / COMMITTED=已提交 / CANCELLED=已取消)
ALTER TABLE inbound_order ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'DRAFT';
CREATE INDEX IF NOT EXISTS idx_inbound_order_status ON inbound_order(status);

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

-- ============================================================
-- 库位字典(可选;商品 location 字段从本表选择)
-- ============================================================
CREATE TABLE IF NOT EXISTS location (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    remark      VARCHAR(255) DEFAULT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_location_code ON location(code);
CREATE INDEX IF NOT EXISTS idx_location_enabled ON location(enabled);

-- ============================================================
-- 库存单元(每个入库批次 = 一个实物单元,带 24 位唯一编码)
-- ============================================================
CREATE TABLE IF NOT EXISTS inventory_item (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    code                VARCHAR(24)  NOT NULL UNIQUE,
    product_id          INT          NOT NULL,
    sku                 VARCHAR(64)  NOT NULL,
    product_name        VARCHAR(128) NOT NULL,
    location_code       VARCHAR(32)  NOT NULL,
    quantity            INT          NOT NULL DEFAULT 0,
    remaining           INT          NOT NULL DEFAULT 0,
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_STOCK',
    -- IN_STOCK / OUT / SCRAPPED / TRANSFERRED
    inbound_order_id    INT          DEFAULT NULL,
    inbound_item_id     INT          DEFAULT NULL,
    outbound_order_id   INT          DEFAULT NULL,
    scrapped_at         TIMESTAMP    DEFAULT NULL,
    scrapped_remark     VARCHAR(255) DEFAULT NULL,
    transferred_at      TIMESTAMP    DEFAULT NULL,
    transferred_from    VARCHAR(32)  DEFAULT NULL,
    transferred_to      VARCHAR(32)  DEFAULT NULL,
    created_by          VARCHAR(64)  DEFAULT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_invitem_sku      ON inventory_item(sku);
CREATE INDEX IF NOT EXISTS idx_invitem_location ON inventory_item(location_code);
CREATE INDEX IF NOT EXISTS idx_invitem_status   ON inventory_item(status);
CREATE INDEX IF NOT EXISTS idx_invitem_inbound  ON inventory_item(inbound_order_id);
CREATE INDEX IF NOT EXISTS idx_invitem_outbound ON inventory_item(outbound_order_id);

-- ============================================================
-- 库存盘点
--   盘点流程:创建盘点单 → 自动快照 inventory_item → 用户填实盘数 → 提交生成差异报告
--   status: DRAFT(草稿) / COMMITTED(已提交,生成差异) / CANCELLED(已取消)
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_check (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    check_no         VARCHAR(64)  NOT NULL UNIQUE,
    scope            VARCHAR(16)  NOT NULL DEFAULT 'ALL',
    -- ALL / SKU / LOCATION
    scope_keyword    VARCHAR(128) DEFAULT NULL,
    remark           VARCHAR(255) DEFAULT NULL,
    operator         VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    total_count      INT          NOT NULL DEFAULT 0,
    counted_count    INT          NOT NULL DEFAULT 0,
    diff_count       INT          NOT NULL DEFAULT 0,
    committed_at     TIMESTAMP    DEFAULT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_stock_check_created ON stock_check(created_at);
CREATE INDEX IF NOT EXISTS idx_stock_check_status  ON stock_check(status);

CREATE TABLE IF NOT EXISTS stock_check_item (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    check_id          INT          NOT NULL,
    inventory_item_id INT          NOT NULL,
    code              VARCHAR(24)  NOT NULL,
    sku               VARCHAR(64)  NOT NULL,
    product_name      VARCHAR(128) NOT NULL,
    location_code     VARCHAR(32)  NOT NULL,
    system_qty        INT          NOT NULL,         -- 快照时系统数
    counted_qty       INT          DEFAULT NULL,     -- 用户填的实盘数
    diff              INT          DEFAULT NULL,     -- 差异 = counted - system
    row_remark        VARCHAR(255) DEFAULT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_check_item UNIQUE (check_id, inventory_item_id)
);
CREATE INDEX IF NOT EXISTS idx_stock_check_item_check ON stock_check_item(check_id);