# 数据库结构 — SimpleWMS

> 范围:`sql/mysql-schema.sql`(全项目唯一库结构定义,MySQL 8+)
> 基线:`main` 分支当前 HEAD(commit 1fc4bc9 / a73ef18 / 7ffcfbd 后)

## 1. 总览 ER

```
  app_user ──(created_by,非外键)──┐
                                  │
  warehouse 1─────N location      │
                       │ 1        │
                       │ N        │
  supplier N───M product_supplier │
                 │                │
                 │ 1              │
                 │ N              │
                 └────── product ─┴───N inventory_log
                          │ 1
                          │ N
        ┌─────────────────┼─────────────────┐
        │                 │                 │
   inbound_order   outbound_order    stock_check
        │ 1:N             │ 1:N             │ 1:N
   inbound_order_item  outbound_order_item  stock_check_item
        └─────────────────┴─────────────────┘
                          │
                          ↓ N
              inventory_item (24 位 code, UNIQUE)
   status: IN_STOCK / PENDING / RESERVED / OUT / SCRAPPED / TRANSFERRED

注:warehouse → location 是唯一的仓库关联(1:N),库位编码全局唯一,
   所以各单据/库存里存的裸库位码仍能唯一定位。
```

## 2. 详细表结构

### 2.1 `app_user` — 用户与角色

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | INT | PK AUTO_INCREMENT | |
| username | VARCHAR(64) | NOT NULL UNIQUE | |
| password_hash | VARCHAR(255) | NOT NULL | BCrypt,length 60 |
| role | VARCHAR(16) | NOT NULL | `ADMIN` / `OPERATOR`(预留) |
| enabled | BOOLEAN | NOT NULL DEFAULT TRUE | |
| must_change_pw | BOOLEAN | NOT NULL DEFAULT FALSE | 首次登录强制改密(预留) |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

索引:`uk_app_user_username`

### 2.2 `supplier` — 供应商主数据

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| name | VARCHAR(128) | NOT NULL UNIQUE |
| contact / phone / email / address | VARCHAR | 联系方式 |
| remark | VARCHAR(255) | |
| enabled | BOOLEAN | NOT NULL DEFAULT TRUE |
| created_at / updated_at | TIMESTAMP | |

### 2.3 `warehouse` — 仓库(库位的上层分组)

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| code | VARCHAR(32) | NOT NULL UNIQUE |
| name | VARCHAR(64) | NOT NULL |
| remark | VARCHAR(255) | |
| enabled | BOOLEAN | NOT NULL DEFAULT TRUE |
| created_by / updated_by | VARCHAR(64) | |
| created_at / updated_at | TIMESTAMP | |

**定位:** 仓库只是库位的分组标签,**库位编码仍全局唯一**(不随仓库重复)。
因此 `inventory_item.location_code`、出入库单明细的 `location` 等"按库位码定位"
的既有逻辑不受影响;要按仓库维度筛选时,`location` 表 join 本表即可。

仓库下还有库位时不允许删除(409)。

### 2.4 `location` — 库位字典

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| code | VARCHAR(32) | NOT NULL UNIQUE(全局唯一,跨仓库不重复) |
| name | VARCHAR(64) | NOT NULL |
| warehouse_id | INT | 归属仓库 → warehouse.id(NULL = 未分配) |
| remark | VARCHAR(255) | |
| enabled | BOOLEAN | NOT NULL DEFAULT TRUE |
| created_at / updated_at | TIMESTAMP | |

`product.location` 字段从此表选择。`warehouse_id` 上建索引 `idx_location_warehouse`。

### 2.5 `product` — 商品主数据

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | INT | PK | |
| sku | VARCHAR(64) | NOT NULL UNIQUE | |
| name | VARCHAR(128) | NOT NULL | |
| category | VARCHAR(64) | NOT NULL DEFAULT '默认' | |
| unit | VARCHAR(16) | NOT NULL DEFAULT '件' | |
| location | VARCHAR(64) | NOT NULL DEFAULT '默认库位' | 冗余,商品当前位置 |
| quantity | INT | NOT NULL DEFAULT 0 | 累计在库数量(简化统计用,真实状态以 `inventory_item` 为准) |
| unit_price | DECIMAL(12,2) | NOT NULL DEFAULT 0 | |
| low_stock | INT | NOT NULL DEFAULT 10 | 低库存阈值 |
| remark | VARCHAR(255) | | |
| created_at / updated_at | TIMESTAMP | | |

### 2.6 `product_supplier` — 商品-供应商多对多

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| product_id | INT | NOT NULL FK→product |
| supplier_id | INT | NOT NULL FK→supplier |
| is_default | BOOLEAN | NOT NULL DEFAULT FALSE | 唯一默认供应商(product_id 内) |
| unit_price | DECIMAL(12,2) | 该供应商的供货价(可与商品基础价不同) |
| remark | VARCHAR(255) | |
| created_at | TIMESTAMP | |

UNIQUE(product_id, supplier_id)

### 2.7 `inventory_log` — 出入库流水

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| type | VARCHAR(16) | `IN` / `OUT` / `SCAN`(UHF 扫码确认)/ `CHECK`(盘点)/ `SCRAP`(报废)/ `TRANSFER`(调拨) |
| product_id | INT | FK→product |
| sku / product_name | VARCHAR | 冗余(防止商品改名后历史流水失真) |
| quantity | INT | 正数(出库负数在旧实现有,现统一存正数,type 区分方向) |
| before_qty / after_qty | INT | 事务前后快照 |
| remark | VARCHAR(255) | |
| operator | VARCHAR(64) | |
| order_id | INT | 关联入库单/出库单 id |
| created_at | TIMESTAMP | 索引 |

### 2.8 `inventory_item` — 库存单元(每件实物一行,核心表)

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | INT | PK | |
| **code** | **VARCHAR(24)** | **NOT NULL UNIQUE** | **每件实物一个唯一码,系统生成,贴实物** |
| product_id / sku / product_name | | FK + 冗余 | |
| location_code | VARCHAR(32) | NOT NULL | 入库时按**明细行的 location**(缺省则商品 `product.location`,再缺省则兜底库位「默认库位」)冗余记录。同一商品的不同单元可以在**不同库位** |
| quantity | INT | NOT NULL DEFAULT 1 | 一物一码场景永远 = 1 |
| remaining | INT | NOT NULL DEFAULT 1 | 出库部分扣减后剩余;remaining=0 → status=OUT |
| **status** | VARCHAR(16) | NOT NULL DEFAULT 'IN_STOCK' | `IN_STOCK` / `PENDING`(待入库)/ `RESERVED`(已预留)/ `OUT` / `SCRAPPED` / `TRANSFERRED` |
| inbound_order_id | INT | FK→inbound_order | 入库关联 |
| inbound_item_id | INT | FK→inbound_order_item | 行级关联(commit 时回填) |
| outbound_order_id | INT | FK→outbound_order | 出库关联 |
| scrapped_at / scrapped_remark | TIMESTAMP / VARCHAR | 报废信息 | |
| transferred_at / transferred_from / transferred_to | | 调拨信息 | |
| created_by | VARCHAR(64) | 操作员 | |
| created_at / updated_at | TIMESTAMP | |

索引:`idx_invitem_sku`、`idx_invitem_location`、`idx_invitem_status`、`idx_invitem_inbound`、`idx_invitem_outbound`

### 2.9 `inbound_order` — 入库单

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| order_no | VARCHAR(64) | NOT NULL UNIQUE |
| external_no | VARCHAR(128) | 客户/上游系统单号 |
| remark | VARCHAR(255) | |
| operator | VARCHAR(64) | |
| total_quantity | INT | NOT NULL DEFAULT 0 |
| item_count | INT | NOT NULL DEFAULT 0 |
| **status** | VARCHAR(16) | **NOT NULL DEFAULT 'DRAFT'** — `DRAFT` / `COMMITTED` / `CANCELLED` |
| created_at | TIMESTAMP | |

索引:`idx_inbound_order_created`、`idx_inbound_order_status`

### 2.10 `inbound_order_item` — 入库单明细

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| order_id | INT | FK→inbound_order |
| product_id / sku / product_name | | |
| supplier | VARCHAR(128) | 同一单据可多供应商(行级) |
| quantity | INT | NOT NULL |
| unit_price | DECIMAL(12,2) | |
| before_qty / after_qty | INT | |
| location | VARCHAR(32) | 冗余 |
| row_remark | VARCHAR(255) | |
| created_at | TIMESTAMP | |

### 2.11 `outbound_order` — 出库单

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| order_no | VARCHAR(64) | NOT NULL UNIQUE |
| customer | VARCHAR(128) | 领用方 |
| external_no / remark / operator | | |
| total_quantity / item_count | INT | |
| **status** | VARCHAR(16) | **NOT NULL DEFAULT 'DRAFT'** |
| created_at | TIMESTAMP | |

### 2.12 `outbound_order_item` — 出库单明细

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| order_id / product_id / sku / product_name | | |
| quantity | INT | NOT NULL |
| unit_price / before_qty / after_qty | | |
| location | VARCHAR(32) | 冗余(行级) |
| row_remark | VARCHAR(255) | |
| created_at | TIMESTAMP | |

### 2.13 `stock_check` — 盘点单

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| check_no | VARCHAR(64) | NOT NULL UNIQUE |
| scope | VARCHAR(16) | `ALL` / `SKU` / `LOCATION` / `WAREHOUSE`;`LOCATION`（按单个库位）**创建入口已移除**（与「按仓库 + 多选库位」重叠），仅为读取历史数据保留 |
| scope_keyword | VARCHAR(512) | SKU 模式=多个 SKU;LOCATION=库位编码;WAREHOUSE=**人读的范围描述**(如 `主仓库 / A-01-01 / B-01-01`,未限定库位则只是仓库名,不解析) |
| remark | VARCHAR(255) | |
| created_by | VARCHAR(64) | 创建人 |
| **status** | VARCHAR(16) | **NOT NULL DEFAULT 'DRAFT'** |
| total_count / counted_count / diff_count | INT | 汇总数 |
| committed_at | TIMESTAMP | |
| created_at / updated_at | TIMESTAMP | |

### 2.14 `stock_check_item` — 盘点单明细

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| check_id | INT | FK→stock_check |
| inventory_item_id | INT | FK→inventory_item |
| code / sku / product_name | VARCHAR | 冗余 |
| location_code | VARCHAR(32) | |
| system_qty | INT | NOT NULL | 快照时系统数 |
| counted_qty | INT | 扫码累加;NULL = 未扫 |
| diff | INT | counted_qty - system_qty;commit 时计算 |
| row_remark | VARCHAR(255) | |
| created_at / updated_at | TIMESTAMP | |

UNIQUE(check_id, inventory_item_id)

## 3. 状态机一览

| 表 | 状态值 | 触发迁移 |
|---|---|---|
| `inbound_order` | `DRAFT → COMMITTED` | 提交入库 → 生成 inventory_item 码 |
| | `DRAFT → CANCELLED` | 取消 → 回滚 quantity / 删 codes / 删 log |
| | `COMMITTED` 终态 | 不可改 |
| `outbound_order` | `DRAFT → COMMITTED` | 提交 → FIFO 扣减 inventory_item(consumeForOutbound) |
| | `DRAFT → CANCELLED` | 取消 → 释放 RESERVED 单元回 IN_STOCK(`releaseReservations`)+ 删明细行(单元不删) |
| | `COMMITTED` 终态 | |
| `stock_check` | `DRAFT → COMMITTED` | 提交 → 重算 diff_count + per-row diff |
| | `DRAFT → CANCELLED` | 取消 → 删明细 |
| | `COMMITTED` 终态 | |
| `inventory_item` | 新增 → `PENDING` | 入库**草稿**加明细时生成占位单元(remaining=0,不计库存、不写流水) |
| | `PENDING → IN_STOCK` | 入库单 commit → 生成/激活,`adjustQuantity` + 写流水 |
| | `PENDING` 删除 | 草稿缩减数量 / 删行 / 取消 → 占位单元被**物理删除** |
| | `IN_STOCK → RESERVED` | 出库**草稿**加明细 → FIFO 锁定(`reserveForOutbound`) |
| | `RESERVED → IN_STOCK` | 出库草稿取消 → `releaseReservations` |
| | `IN_STOCK / RESERVED → OUT` | 出库 commit(消费本单 RESERVED 单元) |
| | `IN_STOCK → SCRAPPED` | scrap 端点 |
| | `IN_STOCK → IN_STOCK`(只挪库位) | transfer 端点(**2026-09-20 起**)|
| | OUT / SCRAPPED 终态 | |
| | `TRANSFERRED` | **历史遗留**:旧 transfer 把单元打成这个终态且不改 `location_code`(见 §4 的修复说明) |

> ⚠️ 与**单据状态**是两套维度:出入库单 / 盘点单的状态只有 `DRAFT` / `COMMITTED` / `CANCELLED`。
> `inventory_item` 的 `PENDING` 是「待入库」,与 PDA `DocStatus.PENDING("待执行")` 只是同名。
> 各端界面文案:IN_STOCK=在库 / PENDING=待入库 / RESERVED=已预留 / OUT=已出库 / SCRAPPED=已报废 / TRANSFERRED=已调拨。

## 4. 关键约束

| 约束 | 表 | 列 |
|---|---|---|
| UNIQUE | 几乎每张表 | 见上 |
| **24 位 code** | `inventory_item` | `code` — 不可变,后端生成,见 `CodeGenerator` |
| 一物一码 | `inventory_item` | `quantity=1, remaining=1` |
| FEFO 锁定 | `inventory_item` | 出库建单 `reserveForOutbound` 按 `(production_date IS NULL) ASC, production_date ASC, id ASC`;**不限库位**(商品全部库位的货在一个池子里排) |
| 一个商品可跨库位 | `inventory_item` | `location_code` 是**单元级**属性,同一 `product_id` 的单元可分布在不同库位(入库时逐行指定) |
| 入库行合并 | `inbound_order_item` | 仅 `商品+供应商+生产日期+保质期+库位` **全同**才合并(`InboundService.addItemsToDraftInternal` 的 key) |
| 调拨不动状态 | `inventory_item` | `transfer` 只改 `location_code`,保持 `IN_STOCK`;`transferred_from/to` 是**最后一次调拨**的审计快照 |
| 唯一默认供应商 | `product_supplier` | (product_id, is_default=TRUE) 唯一 |
| 行级 supplier | `inbound_order_item.supplier` | 同一单据可多供应商 |
| 盘点唯一关联 | `stock_check_item` | (check_id, inventory_item_id) UNIQUE |
| 库位码全局唯一 | `location` | `code` UNIQUE,跨仓库不重复(仓库是分组,不是命名空间) |
| 仓库删除保护 | `warehouse` | 名下仍有 `location` 时拒绝删除(409) |
| 不可删商品 | `product` | 名下有未终结库存单元(IN_STOCK/PENDING/RESERVED)时拒绝删除(409) |

## 5. 迁移与初始化

- **首次**:只建表 + 创建首个 admin 账号(`UserService.bootstrapIfEmpty()`,账号/口令取自 `ADMIN_USERNAME` / `ADMIN_PASSWORD`),**不写入任何演示数据**;商品 / 供应商 / 库位由使用方自行建立
- **空库自动建**:`sql/mysql-schema.sql` 全部 `CREATE TABLE IF NOT EXISTS`,由 MySQL 容器首次启动(data dir 为空)时经 `docker-entrypoint-initdb.d` 执行;复用的旧数据卷不会重跑,缺表时需 `docker compose down -v` 重建
- **老库升级**:`DataSeeder.migrate()` 在启动时跑(`tryAddColumn` / `tryAddIndex` 查询 information_schema 后增量 DDL),不会破坏已有数据
- **仓库体系自举**(2026-09-18,`DataSeeder.ensureDefaultWarehouse()`):建 `warehouse` 表 → 加 `location.warehouse_id` → 若无任何仓库则创建一个「默认仓库」(code `WH-DEFAULT`),并**仅在该列刚被加出来的那一次**把存量库位(`warehouse_id IS NULL`)归入它(之后启动不再回填,避免把用户刻意留空的"未分配"塞回去);同时确保兜底库位「默认库位」真实存在并挂在默认仓库下(该 code 一直是商品未填库位时的兜底值,此前并不在 `location` 表里)
- **状态列追加**:B1/B2 后需手工 `ALTER TABLE inbound_order ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'` —— 当前 9090 容器已加,见后端 report
- **MySQL 老版 (<8.0.29)**:`ADD COLUMN IF NOT EXISTS` 不支持,需手工跑 migration

## 6. 索引一览(按查询路径)

| 索引 | 表 | 列 | 加速场景 |
|---|---|---|---|
| `idx_invitem_sku` | inventory_item | sku | 按 SKU 查单元列表 |
| `idx_invitem_location` | inventory_item | location_code | 按库位过滤 |
| `idx_invitem_status` | inventory_item | status | 仪表盘/盘点按状态过滤 |
| `idx_invitem_inbound` | inventory_item | inbound_order_id | 入库单详情 |
| `idx_invitem_outbound` | inventory_item | outbound_order_id | 出库单详情 |
| `idx_inbound_order_created` | inbound_order | created_at | 历史单据日期范围 |
| `idx_inbound_order_status` | inbound_order | status | PDA 选 DRAFT 单 |
| `idx_outbound_order_status` | outbound_order | status | PDA 选 DRAFT 单 |
| `idx_log_created` / `idx_log_order` | inventory_log | created_at / order_id | 流水查询 |
| `idx_log_product` | inventory_log | product_id | 商品维度 |
| `idx_stock_check_created` / `idx_stock_check_status` | stock_check | created_at / status | 盘点单列表 |
| `idx_location_warehouse` | location | warehouse_id | 按仓库筛库位 / 仓库列表带出库位数 |

## 7. 数据量预估

- `warehouse` / `product` / `supplier` / `location`:几十~几百(企业级)
- `inventory_item`:万~百万级(每个实物一行,大仓可能上百万)
- `inbound_order` / `outbound_order` / `stock_check`:千~万/月(随时间累积)
- `inventory_log`:与 inventory_item 同量级,长期保留

## 8. 数据生命周期

| 表 | 保留策略 |
|---|---|
| `inventory_log` | 永久(审计) |
| `inventory_item` | IN_STOCK 实物永久;OUT/SCRAPPED/TRANSFERRED 永久(可追溯历史) |
| `inbound_order` / `outbound_order` | 永久 |
| `stock_check` | 永久 |
| `app_user` 软删除 | 不删,只 `enabled=false` |

## 9. 备份建议

- MySQL:每日全备 + binlog 增量,保留 ≥ 30 天
- 关键表额外 dump:`inventory_item` 因为含 24 位唯一码,不能丢
- 本地开发库即 docker 卷 `simplewms-mysql-data`;`docker compose down -v` 会连数据一起删除,慎用
