-- ============================================================
--  SimpleWMS 数据库初始化脚本(H2 / MySQL 兼容)
--
--  用途:应用以默认 profile(非 mysql)启动时,由 Spring 的
--    spring.sql.init.schema-locations=classpath:schema.sql
--  在空库上自动建表。Docker 部署走 MySQL,用的是 sql/mysql-schema.sql。
--
--  本文件与 sql/mysql-schema.sql / 线上库结构保持一致:
--    表、字段、索引、默认值、注释均 1:1;只去掉 MySQL 专属的
--    ENGINE / CHARSET / COLLATE(H2 用 MODE=MySQL 兼容其余语法)。
--
--  ⚠️ 改结构时请同时维护 sql/mysql-schema.sql 与增量迁移
--     sql/mysql-migrate-YYYY-MM-DD*.sql,避免两条初始化路径漂移。
-- ============================================================

-- ============================================================
--  商品
-- ============================================================
CREATE TABLE IF NOT EXISTS product (
    id           int NOT NULL AUTO_INCREMENT COMMENT '主键',
    sku          varchar(64) NOT NULL COMMENT '商品编码(SKU,唯一)',
    name         varchar(128) NOT NULL COMMENT '商品名称',
    category     varchar(64) NOT NULL DEFAULT 'default' COMMENT '分类',
    unit         varchar(16) NOT NULL DEFAULT 'pcs' COMMENT '计量单位(如 pcs)',
    location     varchar(64) NOT NULL DEFAULT 'default' COMMENT '默认库位(出库锁定时按此库位取货)',
    quantity     int NOT NULL DEFAULT '0' COMMENT '当前库存数量(由入库/出库自动维护)',
    unit_price   decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价',
    low_stock    int NOT NULL DEFAULT '10' COMMENT '低库存预警阈值(quantity <= 该值 时列表标红)',
    remark       varchar(255) DEFAULT NULL COMMENT '备注',
    created_at   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_sku` (`sku`)
) COMMENT='商品(物料主数据)';

-- ============================================================
--  商品-供应商 供货关系
-- ============================================================
CREATE TABLE IF NOT EXISTS product_supplier (
    id            int NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id    int NOT NULL COMMENT '商品ID → product.id',
    supplier_id   int NOT NULL COMMENT '供应商ID → supplier.id',
    is_default    TINYINT NOT NULL DEFAULT '0' COMMENT '是否默认供应商:1=是 0=否',
    unit_price    decimal(12,2) DEFAULT NULL COMMENT '该供应商供货单价',
    remark        varchar(255) DEFAULT NULL COMMENT '备注',
    created_at    timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_supplier` (`product_id`,`supplier_id`)
) COMMENT='商品-供应商 供货关系(多对多)';

-- ============================================================
--  库位
-- ============================================================
CREATE TABLE IF NOT EXISTS location (
    id           int NOT NULL AUTO_INCREMENT COMMENT '主键',
    code         varchar(32) NOT NULL COMMENT '库位编码(唯一)',
    name         varchar(64) NOT NULL COMMENT '库位名称',
    remark       varchar(255) DEFAULT NULL COMMENT '备注',
    enabled      TINYINT NOT NULL DEFAULT '1' COMMENT '是否启用:1=启用 0=停用',
    created_at   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) COMMENT='库位(货架/货位)';

-- ============================================================
--  供应商
-- ============================================================
CREATE TABLE IF NOT EXISTS supplier (
    id           int NOT NULL AUTO_INCREMENT COMMENT '主键',
    name         varchar(128) NOT NULL COMMENT '供应商名称',
    contact      varchar(64) DEFAULT NULL COMMENT '联系人',
    phone        varchar(32) DEFAULT NULL COMMENT '联系电话',
    email        varchar(128) DEFAULT NULL COMMENT '邮箱',
    address      varchar(255) DEFAULT NULL COMMENT '地址',
    remark       varchar(255) DEFAULT NULL COMMENT '备注',
    enabled      TINYINT NOT NULL DEFAULT '1' COMMENT '是否启用:1=启用 0=停用',
    created_at   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) COMMENT='供应商';

-- ============================================================
--  用户(登录账号)
-- ============================================================
CREATE TABLE IF NOT EXISTS app_user (
    id               int NOT NULL AUTO_INCREMENT COMMENT '主键',
    username         varchar(64) NOT NULL COMMENT '登录用户名(唯一)',
    password_hash    varchar(255) NOT NULL COMMENT '密码哈希(BCrypt,不可逆)',
    role             varchar(16) NOT NULL COMMENT '角色:ADMIN=管理员 / OPERATOR=操作员',
    enabled          TINYINT NOT NULL DEFAULT '1' COMMENT '是否启用:1=启用 0=禁用(禁用后 token 立即失效)',
    must_change_pw   TINYINT NOT NULL DEFAULT '0' COMMENT '是否强制下次登录改密:1=是 0=否(为 1 时除改密外接口一律 403)',
    created_at       timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_user_username` (`username`)
) COMMENT='系统用户(登录账号)';

-- ============================================================
--  入库单
-- ============================================================
CREATE TABLE IF NOT EXISTS inbound_order (
    id               int NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no         varchar(64) NOT NULL COMMENT '入库单号(唯一,格式 IN-yyyyMMddHHmmss-序号)',
    supplier         varchar(128) DEFAULT NULL COMMENT '供应商名称(下单时的快照)',
    external_no      varchar(128) DEFAULT NULL COMMENT '外部单号(对方单据号)',
    remark           varchar(255) DEFAULT NULL COMMENT '备注',
    operator         varchar(64) NOT NULL COMMENT '操作人',
    total_quantity   int NOT NULL DEFAULT '0' COMMENT '总件数(由明细汇总)',
    item_count       int NOT NULL DEFAULT '0' COMMENT '明细行数(由明细汇总)',
    status           varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=草稿 / COMMITTED=已入库 / CANCELLED=已取消',
    created_at       timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inbound_order_no` (`order_no`),
    KEY `idx_inbound_order_created` (`created_at`),
    KEY `idx_inbound_order_status` (`status`)
) COMMENT='入库单(主表)';

-- ============================================================
--  入库单明细
-- ============================================================
CREATE TABLE IF NOT EXISTS inbound_order_item (
    id             int NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id       int NOT NULL COMMENT '所属入库单 → inbound_order.id',
    product_id     int NOT NULL COMMENT '商品ID → product.id',
    sku            varchar(64) NOT NULL COMMENT '商品编码(快照)',
    product_name   varchar(128) NOT NULL COMMENT '商品名称(快照)',
    quantity       int NOT NULL COMMENT '入库数量',
    unit_price     decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价',
    before_qty     int NOT NULL DEFAULT '0' COMMENT '操作前库存数量',
    after_qty      int NOT NULL DEFAULT '0' COMMENT '操作后库存数量',
    row_remark     varchar(255) DEFAULT NULL COMMENT '行备注',
    created_at     timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    supplier       varchar(128) DEFAULT NULL COMMENT '行级供应商',
    location       varchar(32) DEFAULT NULL COMMENT '入库库位(决定生成的库存单元落在哪个库位)',
    PRIMARY KEY (`id`),
    KEY `idx_inbound_item_order` (`order_id`),
    KEY `idx_inbound_item_product` (`product_id`)
) COMMENT='入库单明细行';

-- ============================================================
--  出库单
-- ============================================================
CREATE TABLE IF NOT EXISTS outbound_order (
    id                  int NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no            varchar(64) NOT NULL COMMENT '出库单号(唯一,格式 OUT-yyyyMMddHHmmss-序号)',
    customer            varchar(128) DEFAULT NULL COMMENT '客户 / 领用方',
    external_no         varchar(128) DEFAULT NULL COMMENT '外部单号(对方单据号)',
    remark              varchar(255) DEFAULT NULL COMMENT '备注',
    operator            varchar(64) NOT NULL COMMENT '操作人',
    total_quantity      int NOT NULL DEFAULT '0' COMMENT '总件数(由明细汇总)',
    item_count          int NOT NULL DEFAULT '0' COMMENT '明细行数(由明细汇总)',
    status              varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=草稿 / COMMITTED=已出库 / CANCELLED=已取消',
    uhf_dispatched_at   timestamp NULL DEFAULT NULL COMMENT '下发到 UHF 设备的时间(NULL=未下发)',
    uhf_dispatched_by   varchar(64) DEFAULT NULL COMMENT '下发操作人',
    uhf_device_code     varchar(64) DEFAULT NULL COMMENT '目标 UHF 设备号(如 MOCK-UHF-001)',
    uhf_claimed_at      timestamp NULL DEFAULT NULL COMMENT 'UHF 设备认领时间(NULL=未认领)',
    created_at          timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbound_order_no` (`order_no`),
    KEY `idx_outbound_order_created` (`created_at`),
    KEY `idx_outbound_order_status` (`status`),
    KEY `idx_outbound_order_uhf_dispatched` (`uhf_dispatched_at`)
) COMMENT='出库单(主表)';

-- ============================================================
--  出库单明细
-- ============================================================
CREATE TABLE IF NOT EXISTS outbound_order_item (
    id             int NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id       int NOT NULL COMMENT '所属出库单 → outbound_order.id',
    product_id     int NOT NULL COMMENT '商品ID → product.id',
    sku            varchar(64) NOT NULL COMMENT '商品编码(快照)',
    product_name   varchar(128) NOT NULL COMMENT '商品名称(快照)',
    quantity       int NOT NULL COMMENT '出库数量',
    unit_price     decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价',
    before_qty     int NOT NULL DEFAULT '0' COMMENT '操作前库存数量',
    after_qty      int NOT NULL DEFAULT '0' COMMENT '操作后库存数量',
    row_remark     varchar(255) DEFAULT NULL COMMENT '行备注',
    created_at     timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    location       varchar(32) DEFAULT NULL COMMENT '取货库位(按此库位锁定库存单元)',
    PRIMARY KEY (`id`),
    KEY `idx_outbound_item_order` (`order_id`),
    KEY `idx_outbound_item_product` (`product_id`)
) COMMENT='出库单明细行';

-- ============================================================
--  库存单元(一物一码)
-- ============================================================
CREATE TABLE IF NOT EXISTS inventory_item (
    id                  int NOT NULL AUTO_INCREMENT COMMENT '主键',
    code                varchar(24) NOT NULL COMMENT '库存单元编码(唯一)。一物一码;UHF 场景即标签 EPC(24 位 hex)',
    product_id          int NOT NULL COMMENT '商品ID → product.id',
    sku                 varchar(64) NOT NULL COMMENT '商品编码(快照)',
    product_name        varchar(128) NOT NULL COMMENT '商品名称(快照)',
    location_code       varchar(32) NOT NULL COMMENT '所在库位 → location.code',
    quantity            int NOT NULL DEFAULT '0' COMMENT '数量。标准单件=1(一物一码);老数据可能 >1 表示整批',
    remaining           int NOT NULL DEFAULT '0' COMMENT '剩余可用数量。1=整件可用,0=已出/已耗尽',
    status              varchar(16) NOT NULL DEFAULT 'IN_STOCK' COMMENT '状态:IN_STOCK=在库 / RESERVED=已被出库单锁定 / OUT=已出库 / SCRAPPED=已报废 / TRANSFERRED=已调拨 / PENDING=未入库(待提交)',
    inbound_order_id    int DEFAULT NULL COMMENT '来源入库单 → inbound_order.id',
    inbound_item_id     int DEFAULT NULL COMMENT '来源入库明细行 → inbound_order_item.id',
    outbound_order_id   int DEFAULT NULL COMMENT '关联出库单 → outbound_order.id(被锁定或已出库时)',
    scrapped_at         timestamp NULL DEFAULT NULL COMMENT '报废时间',
    scrapped_remark     varchar(255) DEFAULT NULL COMMENT '报废备注',
    transferred_at      timestamp NULL DEFAULT NULL COMMENT '调拨时间',
    transferred_from    varchar(32) DEFAULT NULL COMMENT '调出库位',
    transferred_to      varchar(32) DEFAULT NULL COMMENT '调入库位',
    created_by          varchar(64) DEFAULT NULL COMMENT '创建人',
    created_at          timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inventory_item_code` (`code`)
) COMMENT='库存单元(一物一码;每行 = 1 个实体件/EPC)';

-- ============================================================
--  库存流水
-- ============================================================
CREATE TABLE IF NOT EXISTS inventory_log (
    id             int NOT NULL AUTO_INCREMENT COMMENT '主键',
    type           varchar(16) NOT NULL COMMENT '流水类型:IN=入库 / OUT=出库 / SCAN=UHF 扫码确认 / CHECK=盘点调整',
    product_id     int NOT NULL COMMENT '商品ID → product.id',
    sku            varchar(64) NOT NULL COMMENT '商品编码(快照)',
    product_name   varchar(128) NOT NULL COMMENT '商品名称(快照)',
    quantity       int NOT NULL COMMENT '变动数量(SCAN 类型固定为 0,仅作确认标记)',
    before_qty     int NOT NULL DEFAULT '0' COMMENT '变动前库存数量',
    after_qty      int NOT NULL DEFAULT '0' COMMENT '变动后库存数量',
    remark         varchar(255) DEFAULT NULL COMMENT '备注。SCAN 类型格式为 "UHF 扫码确认, epc=<EPC>"(重复扫描按此串去重)',
    operator       varchar(64) DEFAULT NULL COMMENT '操作人',
    order_id       int DEFAULT NULL COMMENT '关联单据ID(入库单/出库单/盘点单)',
    created_at     timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_log_product` (`product_id`),
    KEY `idx_log_created` (`created_at`),
    KEY `idx_log_type` (`type`),
    KEY `idx_log_order` (`order_id`)
) COMMENT='库存流水(入库/出库/扫码确认/盘点等事件留痕)';

-- ============================================================
--  盘点单
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_check (
    id              int NOT NULL AUTO_INCREMENT COMMENT '主键',
    check_no        varchar(64) NOT NULL COMMENT '盘点单号(唯一,格式 CK-yyyyMMddHHmmss-序号)',
    scope           varchar(16) NOT NULL DEFAULT 'ALL' COMMENT '盘点范围:FULL=全库 / KEYWORD=按关键字 / LOCATION=按库位',
    scope_keyword   varchar(128) DEFAULT NULL COMMENT '范围关键字(scope=KEYWORD 时使用)',
    remark          varchar(255) DEFAULT NULL COMMENT '备注',
    operator        varchar(64) NOT NULL COMMENT '操作人',
    status          varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=盘点中 / COMMITTED=已提交 / CANCELLED=已取消',
    total_count     int NOT NULL DEFAULT '0' COMMENT '应盘件数',
    counted_count   int NOT NULL DEFAULT '0' COMMENT '已盘件数',
    diff_count      int NOT NULL DEFAULT '0' COMMENT '差异件数',
    committed_at    timestamp NULL DEFAULT NULL COMMENT '提交时间',
    created_at      timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stock_check_no` (`check_no`)
) COMMENT='盘点单(主表)';

-- ============================================================
--  盘点单明细
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_check_item (
    id                  int NOT NULL AUTO_INCREMENT COMMENT '主键',
    check_id            int NOT NULL COMMENT '所属盘点单 → stock_check.id',
    inventory_item_id   int NOT NULL COMMENT '库存单元ID → inventory_item.id',
    code                varchar(24) NOT NULL COMMENT '库存单元编码(EPC 快照)',
    sku                 varchar(64) NOT NULL COMMENT '商品编码(快照)',
    product_name        varchar(128) NOT NULL COMMENT '商品名称(快照)',
    location_code       varchar(32) NOT NULL COMMENT '库位(快照)',
    system_qty          int NOT NULL COMMENT '系统数量(账面)',
    counted_qty         int DEFAULT NULL COMMENT '实盘数量',
    diff                int DEFAULT NULL COMMENT '差异 = 实盘 - 系统',
    row_remark          varchar(255) DEFAULT NULL COMMENT '行备注',
    created_at          timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stock_check_item` (`check_id`,`inventory_item_id`)
) COMMENT='盘点单明细行';
