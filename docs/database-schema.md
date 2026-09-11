# 数据库结构 — SimpleWMS

> 范围:`src/main/resources/schema.sql`(H2 2.x + MySQL 8.0.29+ 兼容)
> 基线:`main` 分支当前 HEAD(commit 1fc4bc9 / a73ef18 / 7ffcfbd 后)

## 1. 总览 ER

```
                ┌──────────┐         ┌──────────────┐
                │ app_user │         │  supplier    │
                └─────┬────┘         └──────┬───────┘
                      │                     │ 1
                      │                     │ N
                  ┌───┴─────────────────────┴────────────────┐
                  │       product_supplier (M:N)            │
                  └────────────────┬────────────────────────┘
                                   │ N
                ┌──────────┐         │         ┌──────────────┐
                │ location │ 1     N │         │ inventory_log│
                └─────┬────┘ ────────┘         └──────┬───────┘
                      │                              │ N
                      │ N  ┌───────────────────┐    │
                      └───┤      product       ├────┘
                          └─────────┬─────────┘
                                    │ 1
                                    │ N
            ┌───────────────────────┼───────────────────────┐
            │                       │                       │
            │ 1:N                   │ 1:N                   │ 1:N
   ┌────────┴──────┐      ┌─────────┴──────┐      ┌────────┴────────┐
   │ inbound_order │      │ outbound_order │      │   stock_check   │
   │ (status)     │      │ (status)       │      │ (status)        │
   └───────┬──────┘      └────────┬──────┘      └─────────┬───────┘
           │ 1:N                  │ 1:N                  │ 1:N
   ┌───────┴────────┐     ┌────────┴────────┐     ┌────────┴─────────┐
   │inbound_order_ │     │outbound_order_ │     │ stock_check_item │
   │   item        │     │    item         │     │ (counted_qty)    │
   └───────┬───────┘     └────────┬───────┘     └─────────┬───────┘
           │                       │ 1:N                    │ 1:1
           └───────────────────────┴──→ inventory_item (24 位 code, UNIQUE)
                                       status: IN_STOCK / OUT / SCRAPPED / TRANSFERRED
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

### 2.3 `location` — 库位字典

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| code | VARCHAR(32) | NOT NULL UNIQUE |
| name | VARCHAR(64) | NOT NULL |
| remark | VARCHAR(255) | |
| enabled | BOOLEAN | NOT NULL DEFAULT TRUE |
| created_at / updated_at | TIMESTAMP | |

`product.location` 字段从此表选择。

### 2.4 `product` — 商品主数据

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

### 2.5 `product_supplier` — 商品-供应商多对多

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

### 2.6 `inventory_log` — 出入库流水

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| type | VARCHAR(16) | `IN` / `OUT` |
| product_id | INT | FK→product |
| sku / product_name | VARCHAR | 冗余(防止商品改名后历史流水失真) |
| quantity | INT | 正数(出库负数在旧实现有,现统一存正数,type 区分方向) |
| before_qty / after_qty | INT | 事务前后快照 |
| remark | VARCHAR(255) | |
| operator | VARCHAR(64) | |
| order_id | INT | 关联入库单/出库单 id |
| created_at | TIMESTAMP | 索引 |

### 2.7 `inventory_item` — 库存单元(每件实物一行,核心表)

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | INT | PK | |
| **code** | **VARCHAR(24)** | **NOT NULL UNIQUE** | **每件实物一个唯一码,系统生成,贴实物** |
| product_id / sku / product_name | | FK + 冗余 | |
| location_code | VARCHAR(32) | NOT NULL | 入库时按商品 location 冗余记录 |
| quantity | INT | NOT NULL DEFAULT 1 | 一物一码场景永远 = 1 |
| remaining | INT | NOT NULL DEFAULT 1 | 出库部分扣减后剩余;remaining=0 → status=OUT |
| **status** | VARCHAR(16) | NOT NULL DEFAULT 'IN_STOCK' | `IN_STOCK` / `OUT` / `SCRAPPED` / `TRANSFERRED` |
| inbound_order_id | INT | FK→inbound_order | 入库关联 |
| inbound_item_id | INT | FK→inbound_order_item | 行级关联(commit 时回填) |
| outbound_order_id | INT | FK→outbound_order | 出库关联 |
| scrapped_at / scrapped_remark | TIMESTAMP / VARCHAR | 报废信息 | |
| transferred_at / transferred_from / transferred_to | | 调拨信息 | |
| created_by | VARCHAR(64) | 操作员 | |
| created_at / updated_at | TIMESTAMP | |

索引:`idx_invitem_sku`、`idx_invitem_location`、`idx_invitem_status`、`idx_invitem_inbound`、`idx_invitem_outbound`

### 2.8 `inbound_order` — 入库单

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

### 2.9 `inbound_order_item` — 入库单明细

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

### 2.10 `outbound_order` — 出库单

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| order_no | VARCHAR(64) | NOT NULL UNIQUE |
| customer | VARCHAR(128) | 领用方 |
| external_no / remark / operator | | |
| total_quantity / item_count | INT | |
| **status** | VARCHAR(16) | **NOT NULL DEFAULT 'DRAFT'** |
| created_at | TIMESTAMP | |

### 2.11 `outbound_order_item` — 出库单明细

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| order_id / product_id / sku / product_name | | |
| quantity | INT | NOT NULL |
| unit_price / before_qty / after_qty | | |
| location | VARCHAR(32) | 冗余(行级) |
| row_remark | VARCHAR(255) | |
| created_at | TIMESTAMP | |

### 2.12 `stock_check` — 盘点单

| 列 | 类型 | 约束 |
|---|---|---|
| id | INT | PK |
| check_no | VARCHAR(64) | NOT NULL UNIQUE |
| scope | VARCHAR(16) | `ALL` / `SKU` / `LOCATION` |
| scope_keyword | VARCHAR(128) | scope 的过滤值 |
| remark | VARCHAR(255) | |
| operator | VARCHAR(64) | |
| **status** | VARCHAR(16) | **NOT NULL DEFAULT 'DRAFT'** |
| total_count / counted_count / diff_count | INT | 汇总数 |
| committed_at | TIMESTAMP | |
| created_at / updated_at | TIMESTAMP | |

### 2.13 `stock_check_item` — 盘点单明细

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
| | `DRAFT → CANCELLED` | 取消 → 删明细 |
| | `COMMITTED` 终态 | |
| `stock_check` | `DRAFT → COMMITTED` | 提交 → 重算 diff_count + per-row diff |
| | `DRAFT → CANCELLED` | 取消 → 删明细 |
| | `COMMITTED` 终态 | |
| `inventory_item` | `IN_STOCK → OUT` | 提交出库时扣减 remaining=0 |
| | `IN_STOCK → SCRAPPED` | scrap 端点 |
| | `IN_STOCK → TRANSFERRED` | transfer 端点 |
| | OUT / SCRAPPED / TRANSFERRED 终态 | |

## 4. 关键约束

| 约束 | 表 | 列 |
|---|---|---|
| UNIQUE | 几乎每张表 | 见上 |
| **24 位 code** | `inventory_item` | `code` — 不可变,后端生成,见 `CodeGenerator` |
| 一物一码 | `inventory_item` | `quantity=1, remaining=1` |
| FIFO 扣减 | `outbound_order_item` | 后端 `consumeForOutbound` 按 `id ASC` |
| 唯一默认供应商 | `product_supplier` | (product_id, is_default=TRUE) 唯一 |
| 行级 supplier | `inbound_order_item.supplier` | 同一单据可多供应商 |
| 盘点唯一关联 | `stock_check_item` | (check_id, inventory_item_id) UNIQUE |

## 5. 迁移与初始化

- **首次**:只建表 + 创建首个 admin 账号(`UserService.bootstrapIfEmpty()`,账号/口令取自 `ADMIN_USERNAME` / `ADMIN_PASSWORD`),**不写入任何演示数据**;商品 / 供应商 / 库位由使用方自行建立
- **空库自动建**:`schema.sql` 全部 `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`
- **老库升级**:`DataSeeder.migrate()` 在启动时跑(`tryAddColumn` / `tryAddIndex` 查询 information_schema 后增量 DDL),不会破坏已有数据
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

## 7. 数据量预估

- `product` / `supplier` / `location`:几十~几百(企业级)
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

- MySQL:每日全备 + binlog 增量(WAL),保留 ≥ 30 天
- 关键表额外 dump:`inventory_item` 因为含 24 位唯一码,不能丢
- H2 嵌入式(默认):`./data/wmsdb.mv.db` 单文件,生产建议切 MySQL
