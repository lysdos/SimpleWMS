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
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_inbound_order_created (created_at),
    KEY idx_inbound_order_status  (status)
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
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_outbound_order_created (created_at),
    KEY idx_outbound_order_status  (status)
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

-- ============================================================
--  权限:把 simple_wms 库的所有权限给 wms 业务用户
--
--  MySQL 8 镜像在 .env 里写 MYSQL_USER / MYSQL_PASSWORD 时
--  会自动 CREATE USER,但**不会自动 GRANT 任何库**,本脚本末尾
--  补一刀把权限补上。这样即便将来加 IP 白名单 / 改认证插件,
--  业务用户也能正常连接。
--
--  限制范围:
--    - 库:只给 simple_wms,不给 information_schema/mysql 等
--    - 主机:'%' 表示任意主机(容器外按 docker 网络 IP 访问);
--      生产可改为 '172.22.0.%' 之类精确网段
--
--  改 .env 里 MYSQL_PASSWORD 时:
--    - MySQL 镜像只会在**首次启动(空 data dir)**时建用户并应用新密码
--    - 已存在的 volume 不会更新用户密码,需要 docker compose down -v 重置
--    - 所以本脚本不再 CREATE USER,只 GRANT(用户由镜像创建,密码以 .env 为准)
-- ============================================================

GRANT ALL PRIVILEGES ON simple_wms.* TO 'wms'@'%';
FLUSH PRIVILEGES;