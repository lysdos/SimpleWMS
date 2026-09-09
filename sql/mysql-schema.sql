-- ============================================================
--  SimpleWMS MySQL Schema
--  适用: MySQL 5.7+ / 8.0+
--  本文件 = 库结构真实定义,容器首次启动时由
--    /docker-entrypoint-initdb.d/01-schema.sql 自动跑一次
--  字段 / 索引 / 字符集 / 默认值均与当前线上库 1:1 对齐
--    (由 mysqldump --no-data 导出后精简而来)
-- ============================================================

CREATE DATABASE IF NOT EXISTS simple_wms
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE simple_wms;

-- ============================================================
--  用户
-- ============================================================
CREATE TABLE IF NOT EXISTS app_user (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    must_change_pw  TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
--  库位
-- ============================================================
CREATE TABLE IF NOT EXISTS location (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    remark      VARCHAR(255) DEFAULT NULL,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
--  供应商
-- ============================================================
CREATE TABLE IF NOT EXISTS supplier (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    contact     VARCHAR(64)  DEFAULT NULL,
    phone       VARCHAR(32)  DEFAULT NULL,
    email       VARCHAR(128) DEFAULT NULL,
    address     VARCHAR(255) DEFAULT NULL,
    remark      VARCHAR(255) DEFAULT NULL,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
--  商品
--  category / unit / location 默认值用纯 ASCII(避免 MySQL 8.0 utf8mb4
--  双重编码导致的乱码),由前端 / 业务按需维护。
-- ============================================================
CREATE TABLE IF NOT EXISTS product (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    sku         VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    category    VARCHAR(64)  NOT NULL DEFAULT 'default',
    unit        VARCHAR(16)  NOT NULL DEFAULT 'pcs',
    location    VARCHAR(64)  NOT NULL DEFAULT 'default',
    quantity    INT          NOT NULL DEFAULT 0,
    unit_price  DECIMAL(12,2) NOT NULL DEFAULT 0,
    low_stock   INT          NOT NULL DEFAULT 10,
    remark      VARCHAR(255) DEFAULT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_product_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 商品 - 供应商 多对多关联
CREATE TABLE IF NOT EXISTS product_supplier (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    product_id  INT          NOT NULL,
    supplier_id INT          NOT NULL,
    is_default  TINYINT(1)   NOT NULL DEFAULT 0,
    unit_price  DECIMAL(12,2) DEFAULT NULL,
    remark      VARCHAR(255) DEFAULT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_product_supplier (product_id, supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
--  入库单 (DRAFT / COMMITTED / CANCELLED)
-- ============================================================
CREATE TABLE IF NOT EXISTS inbound_order (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL,
    supplier        VARCHAR(128) DEFAULT NULL,
    external_no     VARCHAR(128) DEFAULT NULL,
    remark          VARCHAR(255) DEFAULT NULL,
    operator        VARCHAR(64)  NOT NULL,
    total_quantity  INT          NOT NULL DEFAULT 0,
    item_count      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inbound_order_no (order_no),
    KEY idx_inbound_order_created (created_at),
    KEY idx_inbound_order_status  (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    supplier        VARCHAR(128) DEFAULT NULL,
    location        VARCHAR(32)  DEFAULT NULL,
    KEY idx_inbound_item_order (order_id),
    KEY idx_inbound_item_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
--  出库单 (DRAFT / COMMITTED / CANCELLED)
-- ============================================================
CREATE TABLE IF NOT EXISTS outbound_order (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL,
    customer        VARCHAR(128) DEFAULT NULL,
    external_no     VARCHAR(128) DEFAULT NULL,
    remark          VARCHAR(255) DEFAULT NULL,
    operator        VARCHAR(64)  NOT NULL,
    total_quantity  INT          NOT NULL DEFAULT 0,
    item_count      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_outbound_order_no (order_no),
    KEY idx_outbound_order_created (created_at),
    KEY idx_outbound_order_status  (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    location        VARCHAR(32)  DEFAULT NULL,
    KEY idx_outbound_item_order (order_id),
    KEY idx_outbound_item_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
--  库存单元 (一物一码)
--  status: PENDING(DRAFT 草稿生成但未生效) / IN_STOCK / OUT / SCRAPPED / TRANSFERRED
--  DRAFT 阶段不会写 IN_STOCK,只生成 PENDING,commit 时激活
-- ============================================================
CREATE TABLE IF NOT EXISTS inventory_item (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    code              VARCHAR(24) NOT NULL,
    product_id        INT         NOT NULL,
    sku               VARCHAR(64) NOT NULL,
    product_name      VARCHAR(128) NOT NULL,
    location_code     VARCHAR(32) NOT NULL,
    quantity          INT         NOT NULL DEFAULT 0,
    remaining         INT         NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL DEFAULT 'IN_STOCK',
    inbound_order_id  INT         DEFAULT NULL,
    inbound_item_id   INT         DEFAULT NULL,
    outbound_order_id INT         DEFAULT NULL,
    scrapped_at       TIMESTAMP   NULL DEFAULT NULL,
    scrapped_remark   VARCHAR(255) DEFAULT NULL,
    transferred_at    TIMESTAMP   NULL DEFAULT NULL,
    transferred_from  VARCHAR(32) DEFAULT NULL,
    transferred_to    VARCHAR(32) DEFAULT NULL,
    created_by        VARCHAR(64) DEFAULT NULL,
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_item_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 库存流水
CREATE TABLE IF NOT EXISTS inventory_log (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    type         VARCHAR(16)  NOT NULL,
    product_id   INT          NOT NULL,
    sku          VARCHAR(64)  NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    quantity     INT          NOT NULL,
    before_qty   INT          NOT NULL DEFAULT 0,
    after_qty    INT          NOT NULL DEFAULT 0,
    remark       VARCHAR(255) DEFAULT NULL,
    operator     VARCHAR(64)  DEFAULT NULL,
    order_id     INT          DEFAULT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_log_product (product_id),
    KEY idx_log_created (created_at),
    KEY idx_log_type    (type),
    KEY idx_log_order   (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
--  盘点单 (DRAFT / COMMITTED)
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_check (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    check_no        VARCHAR(64)  NOT NULL,
    scope           VARCHAR(16)  NOT NULL DEFAULT 'ALL',
    scope_keyword   VARCHAR(128) DEFAULT NULL,
    remark          VARCHAR(255) DEFAULT NULL,
    operator        VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    total_count     INT          NOT NULL DEFAULT 0,
    counted_count   INT          NOT NULL DEFAULT 0,
    diff_count      INT          NOT NULL DEFAULT 0,
    committed_at    TIMESTAMP    NULL DEFAULT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_check_no (check_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS stock_check_item (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    check_id          INT         NOT NULL,
    inventory_item_id INT         NOT NULL,
    code              VARCHAR(24) NOT NULL,
    sku               VARCHAR(64) NOT NULL,
    product_name      VARCHAR(128) NOT NULL,
    location_code     VARCHAR(32) NOT NULL,
    system_qty        INT         NOT NULL,
    counted_qty       INT         DEFAULT NULL,
    diff              INT         DEFAULT NULL,
    row_remark        VARCHAR(255) DEFAULT NULL,
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_check_item (check_id, inventory_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
--  权限:把 simple_wms 库的所有权限给 wms 业务用户
--
--  MySQL 8 镜像在 .env 里写 MYSQL_USER / MYSQL_PASSWORD 时
--  会自动 CREATE USER,但**不会自动 GRANT 任何库**,本脚本末尾
--  补一刀把权限补上。
--
--  改 .env 里 MYSQL_PASSWORD 时:
--    - MySQL 镜像只会在**首次启动(空 data dir)**时建用户并应用新密码
--    - 已存在的 volume 不会更新用户密码,需要 docker compose down -v 重置
--    - 所以本脚本不再 CREATE USER,只 GRANT(用户由镜像创建)
-- ============================================================

GRANT ALL PRIVILEGES ON simple_wms.* TO 'wms'@'%';
FLUSH PRIVILEGES;
