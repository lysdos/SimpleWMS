-- ============================================================
--  SimpleWMS MySQL Schema(初始化建表脚本)
--  适用: MySQL 5.7+ / 8.0+
--
--  本文件 = 库结构的真实定义,容器首次启动时由
--    /docker-entrypoint-initdb.d/01-schema.sql 自动执行一次。
--  字段 / 索引 / 字符集 / 默认值均与当前线上库 1:1 对齐
--    (由 mysqldump --no-data 导出结构后补注释而来)
--
--  所有表与字段均带 COMMENT,便于用 Navicat / DBeaver /
--  information_schema 直接查看语义(数据库设计规范要求)。
--  本文件是全项目唯一的库结构定义(H2 已移除,应用不再执行建表脚本)。
--  开发阶段改结构直接改本文件:全新库(docker compose down -v 后重启)即按新结构建表;
--  现有开发库手工执行一次对应的 ALTER 即可,不保留迁移脚本(避免无用文件堆积)。
--  待正式上线后,再改为"本文件 + sql/mysql-migrate-YYYY-MM-DD*.sql 增量迁移"模式。
-- ============================================================

-- 关键:docker entrypoint 首次建表用的 mysql 客户端默认 latin1(容器 locale 为
-- POSIX),中文 COMMENT 会被双重编码存坏。SET NAMES 强制本连接用 utf8mb4。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS simple_wms
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE simple_wms;

-- ============================================================
--  用户
-- ============================================================
CREATE TABLE IF NOT EXISTS app_user (
    id               INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    username         VARCHAR(64) NOT NULL COMMENT '登录用户名(唯一)',
    password_hash    VARCHAR(255) NOT NULL COMMENT '密码哈希(BCrypt,不可逆)',
    role             VARCHAR(16) NOT NULL COMMENT '角色:ADMIN=管理员 / OPERATOR=操作员',
    enabled          TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用:1=启用 0=禁用(禁用后 token 立即失效)',
    must_change_pw   TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否强制下次登录改密:1=是 0=否(为 1 时除改密外接口一律 403)',
    created_by       VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by       VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户(登录账号)';

-- ============================================================
--  仓库
-- ============================================================
--  仓库是库位的上层分组:每个库位归属一个仓库(location.warehouse_id)。
--  库位编码仍**全局唯一**(不随仓库重复),所以 inventory_item.location_code /
--  inbound_order_item.location / outbound_order_item.location 等既有的
--  "按库位码定位"逻辑无需改动;要按仓库维度筛选时 join 本表即可。
CREATE TABLE IF NOT EXISTS warehouse (
    id           INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    code         VARCHAR(32) NOT NULL COMMENT '仓库编码(唯一)',
    name         VARCHAR(64) NOT NULL COMMENT '仓库名称',
    remark       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    enabled      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用:1=启用 0=停用',
    created_by   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by   VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_warehouse_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='仓库(库位的上层分组)';

-- ============================================================
--  库位
-- ============================================================
CREATE TABLE IF NOT EXISTS location (
    id           INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    code         VARCHAR(32) NOT NULL COMMENT '库位编码(全局唯一,跨仓库不重复)',
    name         VARCHAR(64) NOT NULL COMMENT '库位名称',
    warehouse_id INT DEFAULT NULL COMMENT '所属仓库 → warehouse.id(空=未分配)',
    remark       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    enabled      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用:1=启用 0=停用',
    created_by   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by   VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_location_code` (`code`),
    KEY `idx_location_warehouse` (`warehouse_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库位(货架/货位;归属仓库)';

-- ============================================================
--  供应商
-- ============================================================
CREATE TABLE IF NOT EXISTS supplier (
    id           INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    name         VARCHAR(128) NOT NULL COMMENT '供应商名称',
    contact      VARCHAR(64) DEFAULT NULL COMMENT '联系人',
    phone        VARCHAR(32) DEFAULT NULL COMMENT '联系电话',
    email        VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    address      VARCHAR(255) DEFAULT NULL COMMENT '地址',
    remark       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    enabled      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用:1=启用 0=停用',
    created_by   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by   VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 供应商名称唯一。此前只有 PRIMARY KEY,同名能建出来,而 SupplierService.create
    -- 里的 DuplicateKeyException 分支因此是**死代码**(永远不触发)。
    -- 名称在下单弹窗里是下拉框的 value,重名会让两个选项长得一模一样、选谁都写同一个名字。
    -- ⚠️ 老库若有历史重名,ALTER 会失败 —— DataSeeder.ensureSupplierNameUnique()
    --    会先体检并跳过,不阻断启动。
    UNIQUE KEY `uk_supplier_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商';

-- ============================================================
--  商品
-- ============================================================
CREATE TABLE IF NOT EXISTS product (
    id           INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    sku          VARCHAR(64) NOT NULL COMMENT '商品编码(SKU,唯一)',
    name         VARCHAR(128) NOT NULL COMMENT '商品名称',
    category     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '分类',
    unit         VARCHAR(16) NOT NULL DEFAULT 'pcs' COMMENT '计量单位(如 pcs)',
    location     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '默认库位(出库锁定时按此库位取货)',
    quantity     INT NOT NULL DEFAULT 0 COMMENT '当前库存数量(由入库/出库自动维护)',
    unit_price   DECIMAL(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价',
    low_stock    INT NOT NULL DEFAULT 10 COMMENT '低库存预警阈值(quantity <= 该值 时列表标红)',
    default_shelf_life_days INT DEFAULT NULL COMMENT '默认保质期天数(入库时预填明细行的保质期,可空)',
    expiry_warn_days INT DEFAULT NULL COMMENT '临期预警天数(NULL=默认30天;剩余天数<=该值的在库批次预警)',
    remark       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    created_by   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by   VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_sku` (`sku`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品(物料主数据)';

-- ============================================================
--  商品-供应商 供货关系
-- ============================================================
CREATE TABLE IF NOT EXISTS product_supplier (
    id            INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id    INT NOT NULL COMMENT '商品ID → product.id',
    supplier_id   INT NOT NULL COMMENT '供应商ID → supplier.id',
    is_default    TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认供应商:1=是 0=否',
    unit_price    DECIMAL(12,2) DEFAULT NULL COMMENT '该供应商供货单价',
    remark        VARCHAR(255) DEFAULT NULL COMMENT '备注',
    created_by    VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by    VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_supplier` (`product_id`,`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品-供应商 供货关系(多对多)';

-- ============================================================
--  入库单
-- ============================================================
CREATE TABLE IF NOT EXISTS inbound_order (
    id               INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no         VARCHAR(64) NOT NULL COMMENT '入库单号(唯一,格式 IN-yyyyMMddHHmmss-序号)',
    supplier         VARCHAR(128) DEFAULT NULL COMMENT '供应商名称(下单时的快照)',
    external_no      VARCHAR(128) DEFAULT NULL COMMENT '外部单号(对方单据号)',
    remark           VARCHAR(255) DEFAULT NULL COMMENT '备注',
    created_by       VARCHAR(64) NOT NULL COMMENT '创建人',
    total_quantity   INT NOT NULL DEFAULT 0 COMMENT '总件数(由明细汇总)',
    item_count       INT NOT NULL DEFAULT 0 COMMENT '明细行数(由明细汇总)',
    status           VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=草稿 / COMMITTED=已入库 / CANCELLED=已取消',
    updated_by       VARCHAR(64) DEFAULT NULL COMMENT '更新人(系统自动更新时为空)',
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inbound_order_no` (`order_no`),
    KEY `idx_inbound_order_created` (`created_at`),
    KEY `idx_inbound_order_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库单(主表)';

-- ============================================================
--  入库单明细
-- ============================================================
CREATE TABLE IF NOT EXISTS inbound_order_item (
    id             INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id       INT NOT NULL COMMENT '所属入库单 → inbound_order.id',
    product_id     INT NOT NULL COMMENT '商品ID → product.id',
    sku            VARCHAR(64) NOT NULL COMMENT '商品编码(快照)',
    product_name   VARCHAR(128) NOT NULL COMMENT '商品名称(快照)',
    quantity       INT NOT NULL COMMENT '入库数量',
    unit_price     DECIMAL(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价',
    before_qty     INT NOT NULL DEFAULT 0 COMMENT '操作前库存数量',
    after_qty      INT NOT NULL DEFAULT 0 COMMENT '操作后库存数量',
    row_remark     VARCHAR(255) DEFAULT NULL COMMENT '行备注',
    created_by     VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by     VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    supplier       VARCHAR(128) DEFAULT NULL COMMENT '行级供应商',
    location       VARCHAR(32) DEFAULT NULL COMMENT '入库库位(决定生成的库存单元落在哪个库位)',
    production_date DATE DEFAULT NULL COMMENT '生产日期(该行=一个生产批次)',
    shelf_life_days INT DEFAULT NULL COMMENT '保质期天数(该行批次)',
    PRIMARY KEY (`id`),
    KEY `idx_inbound_item_order` (`order_id`),
    KEY `idx_inbound_item_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库单明细行';

-- ============================================================
--  出库单
-- ============================================================
CREATE TABLE IF NOT EXISTS outbound_order (
    id                  INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no            VARCHAR(64) NOT NULL COMMENT '出库单号(唯一,格式 OUT-yyyyMMddHHmmss-序号)',
    customer            VARCHAR(128) DEFAULT NULL COMMENT '客户 / 领用方',
    external_no         VARCHAR(128) DEFAULT NULL COMMENT '外部单号(对方单据号)',
    remark              VARCHAR(255) DEFAULT NULL COMMENT '备注',
    created_by          VARCHAR(64) NOT NULL COMMENT '创建人',
    total_quantity      INT NOT NULL DEFAULT 0 COMMENT '总件数(由明细汇总)',
    item_count          INT NOT NULL DEFAULT 0 COMMENT '明细行数(由明细汇总)',
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=草稿 / COMMITTED=已出库 / CANCELLED=已取消',
    uhf_dispatched_at   TIMESTAMP NULL DEFAULT NULL COMMENT '下发到 UHF 设备的时间(NULL=未下发)',
    uhf_dispatched_by   VARCHAR(64) DEFAULT NULL COMMENT '下发操作人',
    uhf_device_code     VARCHAR(64) DEFAULT NULL COMMENT '目标 UHF 设备号(如 MOCK-UHF-001)',
    uhf_claimed_at      TIMESTAMP NULL DEFAULT NULL COMMENT 'UHF 设备认领时间(NULL=未认领)',
    updated_by          VARCHAR(64) DEFAULT NULL COMMENT '更新人(系统自动更新时为空)',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbound_order_no` (`order_no`),
    KEY `idx_outbound_order_created` (`created_at`),
    KEY `idx_outbound_order_status` (`status`),
    KEY `idx_outbound_order_uhf_dispatched` (`uhf_dispatched_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库单(主表)';

-- ============================================================
--  出库单明细
-- ============================================================
CREATE TABLE IF NOT EXISTS outbound_order_item (
    id             INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id       INT NOT NULL COMMENT '所属出库单 → outbound_order.id',
    product_id     INT NOT NULL COMMENT '商品ID → product.id',
    sku            VARCHAR(64) NOT NULL COMMENT '商品编码(快照)',
    product_name   VARCHAR(128) NOT NULL COMMENT '商品名称(快照)',
    quantity       INT NOT NULL COMMENT '出库数量',
    unit_price     DECIMAL(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价',
    before_qty     INT NOT NULL DEFAULT 0 COMMENT '操作前库存数量',
    after_qty      INT NOT NULL DEFAULT 0 COMMENT '操作后库存数量',
    row_remark     VARCHAR(255) DEFAULT NULL COMMENT '行备注',
    created_by     VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by     VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    location       VARCHAR(32) DEFAULT NULL COMMENT '取货库位(限定从此库位锁货);空=自动,跨全部库位按 FEFO 锁,实际取货分布看锁到的库存单元',
    PRIMARY KEY (`id`),
    KEY `idx_outbound_item_order` (`order_id`),
    KEY `idx_outbound_item_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库单明细行';

-- ============================================================
--  库存单元(一物一码)
-- ============================================================
CREATE TABLE IF NOT EXISTS inventory_item (
    id                  INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    code                VARCHAR(24) NOT NULL COMMENT '库存单元编码(唯一)。一物一码;UHF 场景即标签 EPC(24 位 hex)',
    product_id          INT NOT NULL COMMENT '商品ID → product.id',
    sku                 VARCHAR(64) NOT NULL COMMENT '商品编码(快照)',
    product_name        VARCHAR(128) NOT NULL COMMENT '商品名称(快照)',
    location_code       VARCHAR(32) NOT NULL COMMENT '所在库位 → location.code',
    quantity            INT NOT NULL DEFAULT 0 COMMENT '数量。标准单件=1(一物一码);老数据可能 >1 表示整批',
    remaining           INT NOT NULL DEFAULT 0 COMMENT '剩余可用数量。1=整件可用,0=已出/已耗尽',
    status              VARCHAR(16) NOT NULL DEFAULT 'IN_STOCK' COMMENT '状态:IN_STOCK=在库 / RESERVED=已被出库单锁定 / OUT=已出库 / SCRAPPED=已报废 / TRANSFERRED=已调拨 / PENDING=未入库(待提交)',
    inbound_order_id    INT DEFAULT NULL COMMENT '来源入库单 → inbound_order.id',
    inbound_item_id     INT DEFAULT NULL COMMENT '来源入库明细行 → inbound_order_item.id',
    outbound_order_id   INT DEFAULT NULL COMMENT '关联出库单 → outbound_order.id(被锁定或已出库时)',
    scrapped_at         TIMESTAMP NULL DEFAULT NULL COMMENT '报废时间',
    scrapped_remark     VARCHAR(255) DEFAULT NULL COMMENT '报废备注',
    transferred_at      TIMESTAMP NULL DEFAULT NULL COMMENT '调拨时间',
    transferred_from    VARCHAR(32) DEFAULT NULL COMMENT '调出库位',
    transferred_to      VARCHAR(32) DEFAULT NULL COMMENT '调入库位',
    production_date     DATE DEFAULT NULL COMMENT '生产日期(批次,可空)',
    shelf_life_days     INT DEFAULT NULL COMMENT '保质期天数(批次,可空)',
    expiry_date         DATE DEFAULT NULL COMMENT '到期日=生产日期+保质期天数(可空;空=不参与效期预警)',
    created_by          VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by          VARCHAR(64) DEFAULT NULL COMMENT '更新人(系统自动更新时为空)',
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inventory_item_code` (`code`),
    -- 本表是**一件一行**,会随库存件数线性增长,所以二级索引不能省(2026-09-24 补;
    -- 此前只有主键 + code,所有按商品/状态/单据的查询都是全表扫描):
    KEY `idx_item_product_status` (`product_id`, `status`),  -- 出库预留 FEFO 选货(最热)
    KEY `idx_item_outbound` (`outbound_order_id`),           -- 出库单详情 / 扫码定位
    KEY `idx_item_inbound` (`inbound_order_id`),             -- 入库单详情 / 提交 / 导出
    KEY `idx_item_inbound_item` (`inbound_item_id`),         -- 提交时按明细回填单元
    KEY `idx_item_status_expiry` (`status`, `expiry_date`),  -- 仪表盘临期 / 状态筛选
    KEY `idx_item_location` (`location_code`)                -- 库存页按库位/仓库筛
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存单元(一物一码;每行 = 1 个实体件/EPC)';

-- ============================================================
--  库存流水
-- ============================================================
CREATE TABLE IF NOT EXISTS inventory_log (
    id             INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    type           VARCHAR(16) NOT NULL COMMENT '流水类型:IN=入库 / OUT=出库 / SCAN=UHF 扫码确认 / CHECK=盘点调整 / SCRAP=报废 / TRANSFER=调拨',
    product_id     INT NOT NULL COMMENT '商品ID → product.id',
    sku            VARCHAR(64) NOT NULL COMMENT '商品编码(快照)',
    product_name   VARCHAR(128) NOT NULL COMMENT '商品名称(快照)',
    quantity       INT NOT NULL COMMENT '变动数量(SCAN 类型固定为 0,仅作确认标记)',
    before_qty     INT NOT NULL DEFAULT 0 COMMENT '变动前库存数量',
    after_qty      INT NOT NULL DEFAULT 0 COMMENT '变动后库存数量',
    remark         VARCHAR(255) DEFAULT NULL COMMENT '备注。SCAN 类型格式为 "UHF 扫码确认, epc=<EPC>"(重复扫描按此串去重)',
    created_by     VARCHAR(64) DEFAULT NULL COMMENT '创建人(即该事件的执行者)',
    order_id       INT DEFAULT NULL COMMENT '关联单据ID(入库单/出库单/盘点单)',
    updated_by     VARCHAR(64) DEFAULT NULL COMMENT '更新人(流水不可变,恒为空)',
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(流水不可变,恒等于创建时间)',
    PRIMARY KEY (`id`),
    KEY `idx_log_product` (`product_id`),
    KEY `idx_log_created` (`created_at`),
    KEY `idx_log_type` (`type`),
    KEY `idx_log_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存流水(入库/出库/扫码确认/盘点等事件留痕)';

-- ============================================================
--  盘点单
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_check (
    id              INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    check_no        VARCHAR(64) NOT NULL COMMENT '盘点单号(唯一,格式 CK-yyyyMMddHHmmss-序号)',
    scope           VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT '盘点范围:ALL=全部在库 / SKU=按SKU / LOCATION=按单个库位(创建入口已移除,仅为读取历史单保留) / WAREHOUSE=按仓库(可限定该仓下的若干库位)',
    scope_keyword   VARCHAR(512) DEFAULT NULL COMMENT '范围关键字(scope=SKU 时为多 SKU;LOCATION 时为库位编码;WAREHOUSE 时为**人读的范围描述**,如"主仓库 / A-01-01 / B-01-01",未限定库位则只是仓库名 —— 该模式不解析此列,只用于展示。输入上限 500 字符)',
    warehouse_id    INT DEFAULT NULL COMMENT '盘点范围所属仓库 → warehouse.id(仅 scope=WAREHOUSE 时写入,扫码时据此判断跨仓的码:单元在库→记盘盈并写行备注来源,非在库→422;其他模式为空)',
    remark          VARCHAR(255) DEFAULT NULL COMMENT '备注',
    created_by      VARCHAR(64) NOT NULL COMMENT '创建人',
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=盘点中 / COMMITTED=已提交 / CANCELLED=已取消',
    total_count     INT NOT NULL DEFAULT 0 COMMENT '应盘件数',
    counted_count   INT NOT NULL DEFAULT 0 COMMENT '已盘件数',
    diff_count      INT NOT NULL DEFAULT 0 COMMENT '差异件数',
    committed_at    TIMESTAMP NULL DEFAULT NULL COMMENT '提交时间',
    updated_by      VARCHAR(64) DEFAULT NULL COMMENT '更新人(系统自动更新时为空)',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stock_check_no` (`check_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='盘点单(主表)';

-- ============================================================
--  盘点单明细
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_check_item (
    id                  INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    check_id            INT NOT NULL COMMENT '所属盘点单 → stock_check.id',
    inventory_item_id   INT NOT NULL COMMENT '库存单元ID → inventory_item.id',
    code                VARCHAR(24) NOT NULL COMMENT '库存单元编码(EPC 快照)',
    sku                 VARCHAR(64) NOT NULL COMMENT '商品编码(快照)',
    product_name        VARCHAR(128) NOT NULL COMMENT '商品名称(快照)',
    location_code       VARCHAR(32) NOT NULL COMMENT '库位(快照)',
    system_qty          INT NOT NULL COMMENT '系统数量(账面)',
    counted_qty         INT DEFAULT NULL COMMENT '实盘数量',
    diff                INT DEFAULT NULL COMMENT '差异 = 实盘 - 系统',
    row_remark          VARCHAR(255) DEFAULT NULL COMMENT '行备注',
    created_by          VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by          VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stock_check_item` (`check_id`,`inventory_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='盘点单明细行';

-- ============================================================
--  权限
--
--  MySQL 官方镜像的 docker-entrypoint 在设置了 MYSQL_DATABASE 时,
--  会自动建库,并把 MYSQL_DATABASE 的全部权限 GRANT 给 MYSQL_USER。
--  本文件是 /docker-entrypoint-initdb.d/ 的初始化脚本,所以正常情况下
--  **不需要**再授权。
--
--  下面这段只是"手工执行本脚本"时的兜底:先判断 wms 用户是否存在,
--  存在才 GRANT(MySQL 8 不允许 GRANT 到不存在的用户,直接写会报错;
--  而 initdb.d 里的脚本一旦报错会中止,导致后续语句不执行)。
--
--  ⚠️ 注意:MySQL 镜像只在**首次启动(空 data dir)**时用 MYSQL_USER /
--     MYSQL_PASSWORD 建用户。改了 .env 里的密码后,已存在的 volume 不会
--     更新,需要 docker compose down -v 重置(会清空数据)。
-- ============================================================

SET @has_wms := (SELECT COUNT(*) FROM mysql.user WHERE user = 'wms');
SET @sql := IF(@has_wms > 0,
    'GRANT ALL PRIVILEGES ON simple_wms.* TO ''wms''@''%''',
    'SELECT ''skip: user wms not found'' AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
FLUSH PRIVILEGES;
