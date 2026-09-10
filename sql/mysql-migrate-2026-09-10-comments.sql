-- ============================================================
--  SimpleWMS MySQL 在线迁移(2026-09-10)
--  内容:为已有的 simple_wms 库补齐「表注释 + 字段注释」
--        (数据库设计规范要求;新库由 sql/mysql-schema.sql 直接建成带注释的)
--
--  适用: 已经在跑、且建表时没有 COMMENT 的库。
--  可重复执行:每次都是把注释设成同一份值,幂等。
--
--  执行: docker exec -i simplewms-mysql \
--          mysql -uroot -p$MYSQL_ROOT_PASSWORD < sql/mysql-migrate-2026-09-10-comments.sql
-- ============================================================

USE simple_wms;

-- ---------- app_user: 系统用户(登录账号) ----------
ALTER TABLE app_user COMMENT='系统用户(登录账号)';
ALTER TABLE app_user MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE app_user MODIFY COLUMN `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录用户名(唯一)';
ALTER TABLE app_user MODIFY COLUMN `password_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码哈希(BCrypt,不可逆)';
ALTER TABLE app_user MODIFY COLUMN `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色:ADMIN=管理员 / OPERATOR=操作员';
ALTER TABLE app_user MODIFY COLUMN `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用:1=启用 0=禁用(禁用后 token 立即失效)';
ALTER TABLE app_user MODIFY COLUMN `must_change_pw` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否强制下次登录改密:1=是 0=否(为 1 时除改密外接口一律 403)';
ALTER TABLE app_user MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE app_user MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- location: 库位(货架/货位) ----------
ALTER TABLE location COMMENT='库位(货架/货位)';
ALTER TABLE location MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE location MODIFY COLUMN `code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '库位编码(唯一)';
ALTER TABLE location MODIFY COLUMN `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '库位名称';
ALTER TABLE location MODIFY COLUMN `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注';
ALTER TABLE location MODIFY COLUMN `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用:1=启用 0=停用';
ALTER TABLE location MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE location MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- supplier: 供应商 ----------
ALTER TABLE supplier COMMENT='供应商';
ALTER TABLE supplier MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE supplier MODIFY COLUMN `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商名称';
ALTER TABLE supplier MODIFY COLUMN `contact` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系人';
ALTER TABLE supplier MODIFY COLUMN `phone` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系电话';
ALTER TABLE supplier MODIFY COLUMN `email` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱';
ALTER TABLE supplier MODIFY COLUMN `address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '地址';
ALTER TABLE supplier MODIFY COLUMN `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注';
ALTER TABLE supplier MODIFY COLUMN `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用:1=启用 0=停用';
ALTER TABLE supplier MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE supplier MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- product: 商品(物料主数据) ----------
ALTER TABLE product COMMENT='商品(物料主数据)';
ALTER TABLE product MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE product MODIFY COLUMN `sku` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码(SKU,唯一)';
ALTER TABLE product MODIFY COLUMN `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称';
ALTER TABLE product MODIFY COLUMN `category` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '分类';
ALTER TABLE product MODIFY COLUMN `unit` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pcs' COMMENT '计量单位(如 pcs)';
ALTER TABLE product MODIFY COLUMN `location` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '默认库位(出库锁定时按此库位取货)';
ALTER TABLE product MODIFY COLUMN `quantity` int NOT NULL DEFAULT '0' COMMENT '当前库存数量(由入库/出库自动维护)';
ALTER TABLE product MODIFY COLUMN `unit_price` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价';
ALTER TABLE product MODIFY COLUMN `low_stock` int NOT NULL DEFAULT '10' COMMENT '低库存预警阈值(quantity <= 该值 时列表标红)';
ALTER TABLE product MODIFY COLUMN `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注';
ALTER TABLE product MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE product MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- product_supplier: 商品-供应商 供货关系(多对多) ----------
ALTER TABLE product_supplier COMMENT='商品-供应商 供货关系(多对多)';
ALTER TABLE product_supplier MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE product_supplier MODIFY COLUMN `product_id` int NOT NULL COMMENT '商品ID → product.id';
ALTER TABLE product_supplier MODIFY COLUMN `supplier_id` int NOT NULL COMMENT '供应商ID → supplier.id';
ALTER TABLE product_supplier MODIFY COLUMN `is_default` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否默认供应商:1=是 0=否';
ALTER TABLE product_supplier MODIFY COLUMN `unit_price` decimal(12,2) DEFAULT NULL COMMENT '该供应商供货单价';
ALTER TABLE product_supplier MODIFY COLUMN `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注';
ALTER TABLE product_supplier MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------- inbound_order: 入库单(主表) ----------
ALTER TABLE inbound_order COMMENT='入库单(主表)';
ALTER TABLE inbound_order MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE inbound_order MODIFY COLUMN `order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '入库单号(唯一,格式 IN-yyyyMMddHHmmss-序号)';
ALTER TABLE inbound_order MODIFY COLUMN `supplier` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '供应商名称(下单时的快照)';
ALTER TABLE inbound_order MODIFY COLUMN `external_no` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '外部单号(对方单据号)';
ALTER TABLE inbound_order MODIFY COLUMN `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注';
ALTER TABLE inbound_order MODIFY COLUMN `operator` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作人';
ALTER TABLE inbound_order MODIFY COLUMN `total_quantity` int NOT NULL DEFAULT '0' COMMENT '总件数(由明细汇总)';
ALTER TABLE inbound_order MODIFY COLUMN `item_count` int NOT NULL DEFAULT '0' COMMENT '明细行数(由明细汇总)';
ALTER TABLE inbound_order MODIFY COLUMN `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=草稿 / COMMITTED=已入库 / CANCELLED=已取消';
ALTER TABLE inbound_order MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------- inbound_order_item: 入库单明细行 ----------
ALTER TABLE inbound_order_item COMMENT='入库单明细行';
ALTER TABLE inbound_order_item MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE inbound_order_item MODIFY COLUMN `order_id` int NOT NULL COMMENT '所属入库单 → inbound_order.id';
ALTER TABLE inbound_order_item MODIFY COLUMN `product_id` int NOT NULL COMMENT '商品ID → product.id';
ALTER TABLE inbound_order_item MODIFY COLUMN `sku` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码(快照)';
ALTER TABLE inbound_order_item MODIFY COLUMN `product_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称(快照)';
ALTER TABLE inbound_order_item MODIFY COLUMN `quantity` int NOT NULL COMMENT '入库数量';
ALTER TABLE inbound_order_item MODIFY COLUMN `unit_price` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价';
ALTER TABLE inbound_order_item MODIFY COLUMN `before_qty` int NOT NULL DEFAULT '0' COMMENT '操作前库存数量';
ALTER TABLE inbound_order_item MODIFY COLUMN `after_qty` int NOT NULL DEFAULT '0' COMMENT '操作后库存数量';
ALTER TABLE inbound_order_item MODIFY COLUMN `row_remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '行备注';
ALTER TABLE inbound_order_item MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE inbound_order_item MODIFY COLUMN `supplier` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '行级供应商';
ALTER TABLE inbound_order_item MODIFY COLUMN `location` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '入库库位(决定生成的库存单元落在哪个库位)';

-- ---------- outbound_order: 出库单(主表) ----------
ALTER TABLE outbound_order COMMENT='出库单(主表)';
ALTER TABLE outbound_order MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE outbound_order MODIFY COLUMN `order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '出库单号(唯一,格式 OUT-yyyyMMddHHmmss-序号)';
ALTER TABLE outbound_order MODIFY COLUMN `customer` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户 / 领用方';
ALTER TABLE outbound_order MODIFY COLUMN `external_no` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '外部单号(对方单据号)';
ALTER TABLE outbound_order MODIFY COLUMN `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注';
ALTER TABLE outbound_order MODIFY COLUMN `operator` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作人';
ALTER TABLE outbound_order MODIFY COLUMN `total_quantity` int NOT NULL DEFAULT '0' COMMENT '总件数(由明细汇总)';
ALTER TABLE outbound_order MODIFY COLUMN `item_count` int NOT NULL DEFAULT '0' COMMENT '明细行数(由明细汇总)';
ALTER TABLE outbound_order MODIFY COLUMN `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=草稿 / COMMITTED=已出库 / CANCELLED=已取消';
ALTER TABLE outbound_order MODIFY COLUMN `uhf_dispatched_at` timestamp NULL DEFAULT NULL COMMENT '下发到 UHF 设备的时间(NULL=未下发)';
ALTER TABLE outbound_order MODIFY COLUMN `uhf_dispatched_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '下发操作人';
ALTER TABLE outbound_order MODIFY COLUMN `uhf_device_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标 UHF 设备号(如 MOCK-UHF-001)';
ALTER TABLE outbound_order MODIFY COLUMN `uhf_claimed_at` timestamp NULL DEFAULT NULL COMMENT 'UHF 设备认领时间(NULL=未认领)';
ALTER TABLE outbound_order MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------- outbound_order_item: 出库单明细行 ----------
ALTER TABLE outbound_order_item COMMENT='出库单明细行';
ALTER TABLE outbound_order_item MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE outbound_order_item MODIFY COLUMN `order_id` int NOT NULL COMMENT '所属出库单 → outbound_order.id';
ALTER TABLE outbound_order_item MODIFY COLUMN `product_id` int NOT NULL COMMENT '商品ID → product.id';
ALTER TABLE outbound_order_item MODIFY COLUMN `sku` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码(快照)';
ALTER TABLE outbound_order_item MODIFY COLUMN `product_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称(快照)';
ALTER TABLE outbound_order_item MODIFY COLUMN `quantity` int NOT NULL COMMENT '出库数量';
ALTER TABLE outbound_order_item MODIFY COLUMN `unit_price` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '单价';
ALTER TABLE outbound_order_item MODIFY COLUMN `before_qty` int NOT NULL DEFAULT '0' COMMENT '操作前库存数量';
ALTER TABLE outbound_order_item MODIFY COLUMN `after_qty` int NOT NULL DEFAULT '0' COMMENT '操作后库存数量';
ALTER TABLE outbound_order_item MODIFY COLUMN `row_remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '行备注';
ALTER TABLE outbound_order_item MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE outbound_order_item MODIFY COLUMN `location` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '取货库位(按此库位锁定库存单元)';

-- ---------- inventory_item: 库存单元(一物一码;每行 = 1 个实体件/EPC) ----------
ALTER TABLE inventory_item COMMENT='库存单元(一物一码;每行 = 1 个实体件/EPC)';
ALTER TABLE inventory_item MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE inventory_item MODIFY COLUMN `code` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '库存单元编码(唯一)。一物一码;UHF 场景即标签 EPC(24 位 hex)';
ALTER TABLE inventory_item MODIFY COLUMN `product_id` int NOT NULL COMMENT '商品ID → product.id';
ALTER TABLE inventory_item MODIFY COLUMN `sku` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码(快照)';
ALTER TABLE inventory_item MODIFY COLUMN `product_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称(快照)';
ALTER TABLE inventory_item MODIFY COLUMN `location_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所在库位 → location.code';
ALTER TABLE inventory_item MODIFY COLUMN `quantity` int NOT NULL DEFAULT '0' COMMENT '数量。标准单件=1(一物一码);老数据可能 >1 表示整批';
ALTER TABLE inventory_item MODIFY COLUMN `remaining` int NOT NULL DEFAULT '0' COMMENT '剩余可用数量。1=整件可用,0=已出/已耗尽';
ALTER TABLE inventory_item MODIFY COLUMN `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'IN_STOCK' COMMENT '状态:IN_STOCK=在库 / RESERVED=已被出库单锁定 / OUT=已出库 / SCRAPPED=已报废 / TRANSFERRED=已调拨 / PENDING=未入库(待提交)';
ALTER TABLE inventory_item MODIFY COLUMN `inbound_order_id` int DEFAULT NULL COMMENT '来源入库单 → inbound_order.id';
ALTER TABLE inventory_item MODIFY COLUMN `inbound_item_id` int DEFAULT NULL COMMENT '来源入库明细行 → inbound_order_item.id';
ALTER TABLE inventory_item MODIFY COLUMN `outbound_order_id` int DEFAULT NULL COMMENT '关联出库单 → outbound_order.id(被锁定或已出库时)';
ALTER TABLE inventory_item MODIFY COLUMN `scrapped_at` timestamp NULL DEFAULT NULL COMMENT '报废时间';
ALTER TABLE inventory_item MODIFY COLUMN `scrapped_remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '报废备注';
ALTER TABLE inventory_item MODIFY COLUMN `transferred_at` timestamp NULL DEFAULT NULL COMMENT '调拨时间';
ALTER TABLE inventory_item MODIFY COLUMN `transferred_from` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '调出库位';
ALTER TABLE inventory_item MODIFY COLUMN `transferred_to` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '调入库位';
ALTER TABLE inventory_item MODIFY COLUMN `created_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人';
ALTER TABLE inventory_item MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE inventory_item MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- inventory_log: 库存流水(入库/出库/扫码确认/盘点等事件留痕) ----------
ALTER TABLE inventory_log COMMENT='库存流水(入库/出库/扫码确认/盘点等事件留痕)';
ALTER TABLE inventory_log MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE inventory_log MODIFY COLUMN `type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流水类型:IN=入库 / OUT=出库 / SCAN=UHF 扫码确认 / CHECK=盘点调整';
ALTER TABLE inventory_log MODIFY COLUMN `product_id` int NOT NULL COMMENT '商品ID → product.id';
ALTER TABLE inventory_log MODIFY COLUMN `sku` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码(快照)';
ALTER TABLE inventory_log MODIFY COLUMN `product_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称(快照)';
ALTER TABLE inventory_log MODIFY COLUMN `quantity` int NOT NULL COMMENT '变动数量(SCAN 类型固定为 0,仅作确认标记)';
ALTER TABLE inventory_log MODIFY COLUMN `before_qty` int NOT NULL DEFAULT '0' COMMENT '变动前库存数量';
ALTER TABLE inventory_log MODIFY COLUMN `after_qty` int NOT NULL DEFAULT '0' COMMENT '变动后库存数量';
ALTER TABLE inventory_log MODIFY COLUMN `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注。SCAN 类型格式为 "UHF 扫码确认, epc=<EPC>"(重复扫描按此串去重)';
ALTER TABLE inventory_log MODIFY COLUMN `operator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人';
ALTER TABLE inventory_log MODIFY COLUMN `order_id` int DEFAULT NULL COMMENT '关联单据ID(入库单/出库单/盘点单)';
ALTER TABLE inventory_log MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------- stock_check: 盘点单(主表) ----------
ALTER TABLE stock_check COMMENT='盘点单(主表)';
ALTER TABLE stock_check MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE stock_check MODIFY COLUMN `check_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '盘点单号(唯一,格式 CK-yyyyMMddHHmmss-序号)';
ALTER TABLE stock_check MODIFY COLUMN `scope` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ALL' COMMENT '盘点范围:FULL=全库 / KEYWORD=按关键字 / LOCATION=按库位';
ALTER TABLE stock_check MODIFY COLUMN `scope_keyword` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '范围关键字(scope=KEYWORD 时使用)';
ALTER TABLE stock_check MODIFY COLUMN `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注';
ALTER TABLE stock_check MODIFY COLUMN `operator` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作人';
ALTER TABLE stock_check MODIFY COLUMN `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT=盘点中 / COMMITTED=已提交 / CANCELLED=已取消';
ALTER TABLE stock_check MODIFY COLUMN `total_count` int NOT NULL DEFAULT '0' COMMENT '应盘件数';
ALTER TABLE stock_check MODIFY COLUMN `counted_count` int NOT NULL DEFAULT '0' COMMENT '已盘件数';
ALTER TABLE stock_check MODIFY COLUMN `diff_count` int NOT NULL DEFAULT '0' COMMENT '差异件数';
ALTER TABLE stock_check MODIFY COLUMN `committed_at` timestamp NULL DEFAULT NULL COMMENT '提交时间';
ALTER TABLE stock_check MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE stock_check MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- stock_check_item: 盘点单明细行 ----------
ALTER TABLE stock_check_item COMMENT='盘点单明细行';
ALTER TABLE stock_check_item MODIFY COLUMN `id` int NOT NULL AUTO_INCREMENT COMMENT '主键';
ALTER TABLE stock_check_item MODIFY COLUMN `check_id` int NOT NULL COMMENT '所属盘点单 → stock_check.id';
ALTER TABLE stock_check_item MODIFY COLUMN `inventory_item_id` int NOT NULL COMMENT '库存单元ID → inventory_item.id';
ALTER TABLE stock_check_item MODIFY COLUMN `code` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '库存单元编码(EPC 快照)';
ALTER TABLE stock_check_item MODIFY COLUMN `sku` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码(快照)';
ALTER TABLE stock_check_item MODIFY COLUMN `product_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称(快照)';
ALTER TABLE stock_check_item MODIFY COLUMN `location_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '库位(快照)';
ALTER TABLE stock_check_item MODIFY COLUMN `system_qty` int NOT NULL COMMENT '系统数量(账面)';
ALTER TABLE stock_check_item MODIFY COLUMN `counted_qty` int DEFAULT NULL COMMENT '实盘数量';
ALTER TABLE stock_check_item MODIFY COLUMN `diff` int DEFAULT NULL COMMENT '差异 = 实盘 - 系统';
ALTER TABLE stock_check_item MODIFY COLUMN `row_remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '行备注';
ALTER TABLE stock_check_item MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE stock_check_item MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';
