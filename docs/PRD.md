# SimpleWMS 产品需求文档(PRD)

> **项目**:SimpleWMS 简单仓库管理系统 + Android PDA 扫码 App
> **版本**:2026-09-08
> **基线**:`main` 分支当前 HEAD
> **配套文档**:
> - [数据库结构](./database-schema.md) — 13 张表 + ER + 状态机
> - [API 接口规范](./api-reference.md) — 50+ 端点
> - [PDA 客户端设计](./pda-design.md) — Android 扫码 App 模块设计

---

## 1. 产品概述

### 1.1 一句话

**SimpleWMS = 简单仓库管理系统** + Android 工业 PDA 扫码 App,面向小到中型办公 / 制造业 / 仓储场景,**一物一码**(每件实物一个 24 位唯一码)做精确库存管理。

### 1.2 目标用户

- **仓库管理员**:在 PC Web 端管理商品 / 供应商 / 库位 / 单据
- **PDA 操作员**:在 Android 工业 PDA 上扫码出入库 / 盘点
- **系统管理员**:管理用户、配置基础数据

### 1.3 核心价值主张

| 问题 | 方案 |
|---|---|
| 库存数据不准确 | **一物一码** + 扫码出入库,精确到件 |
| 出入库靠手写易错 | PDA 扫实物上的码,后端自动校验 + 记录 |
| 盘点效率低 | 扫到实物 = 实盘确认,未扫的自动算"盘亏" |
| 多供应商 / 多库位 | 商品-供应商多对多,行级 supplier 记录入库来源 |
| 24 位码难管理 | 后端自动生成,入库打印码贴实物(CSV + 系统分享) |

### 1.4 不做什么(YAGNI)

- **多语言 / 多币种**(中文 + 人民币 only)
- **推送通知 / 消息中心**
- **多仓库管理**(单仓库)
- **库存预警自动补货**(只显示低库存数量)
- **多用户并发协作**(无 WebSocket)
- **SaaS 多租户**(单租户)
- **离线模式**(网络不可达时 PDA 不能用)

## 2. 用户角色

| 角色 | 权限 | 典型场景 |
|---|---|---|
| **ADMIN** | 全部:商品 / 供应商 / 库位 / 用户 CRUD + 所有单据 | 系统管理员 |
| **OPERATOR**(预留) | 看板 + 单据查询 + PDA 扫码 | 仓库管理员(预留) |
| _(PDA 当前用 admin)_ | — | PDA 装机默认 admin |

## 3. 业务场景

### 3.1 商品基础数据

仓库管理员在 **PC Web 端** 完成:
- 录入商品(SKU / 名称 / 库位 / 库存阈值 / 单价)
- 关联供应商(多对多,选默认)
- 录入供应商 / 库位字典

### 3.2 入库流程(DRAFT/COMMITTED 状态机)

```
PC Web 或 PDA                            Backend
    │                                        │
    │ 1. 创建空草稿单                          │
    │ POST /api/inbound/orders                │
    │ {externalNo, remark}                    │
    │ ───────────────────────────────────>  │
    │ <────────── InboundOrder (DRAFT) ────  │
    │                                        │
    │ 2. 往单子追加明细(可多次)                  │
    │ POST /api/inbound/orders/{id}/items    │
    │ {productId, quantity, supplier, ...}    │
    │ ───────────────────────────────────>  │
    │ <─── InboundOrder (items 累加) ─────  │
    │                                        │
    │ 3. 提交                                  │
    │ POST /api/inbound/orders/{id}/commit    │
    │ ───────────────────────────────────>  │
    │   后端:                                    │
    │   - 每件库存单元生成 24 位唯一码          │
    │   - product.quantity += N               │
    │   - 写 inventory_log                     │
    │   - 状态 DRAFT → COMMITTED              │
    │ <─ InboundOrder (items[].codes: N×码)  │
    │                                        │
    │ 4. 打印 24 位码,贴到实物                 │
    │ (PDA: 码打印页 + 系统分享 / 导出 CSV)    │
    │ (Web: 打印按钮 / 导出 CSV)                │
```

**关键点**:
- **24 位码由后端生成**,不是 PDA 扫出来
- **一物一码**:每件实物入库后 `inventory_item` 一行,quantity=1
- **行级 supplier**:同一单据可多供应商混录
- **入库前实物上无码**,扫码入库不适用;扫码是出库/盘点的事

### 3.3 出库流程

```
PDA 端 (OutboundScanActivity)              Backend
    │                                        │
    │ 1. 选/建草稿单                          │
    │ POST /api/outbound/orders               │
    │ ───────────────────────────────────>  │
    │ <──────── OutboundOrder (DRAFT) ─────  │
    │                                        │
    │ 2. 全屏摄像头实时扫码                   │
    │ 扫到 24 位码 → 调 /scan                │
    │ POST /api/outbound/orders/{id}/scan     │
    │ {code: "..."}                           │
    │ ───────────────────────────────────>  │
    │   后端:                                    │
    │   - 查 inventory_item by code            │
    │   - 状态必须 IN_STOCK                    │
    │   - 找到 productId,行 +1                │
    │   - 返回最新 order (含 items)            │
    │ <──────── OutboundOrder ───────────  │
    │                                        │
    │ 3. UI 列表从响应 items 重建              │
    │                                        │
    │ 4. 提交                                  │
    │ POST /api/outbound/orders/{id}/commit   │
    │ ───────────────────────────────────>  │
    │   后端:                                    │
    │   - FIFO 扣减 inventory_item(remaining) │
    │   - product.quantity -= N               │
    │   - 写 inventory_log                     │
    │   - 状态 DRAFT → COMMITTED              │
```

**关键点**:
- **扫码必走 DRAFT 单**:每扫一码 → 后端校验 + 累加(回包含最新 items)
- **FIFO 扣减**:同一商品有多个 inventory_item 时,按 id ASC 优先消耗最早入库的
- **失败码 立即提示**:404 码不存在 / 409 码已出库,用户当场知道

### 3.4 盘点流程

```
PDA 端 (StockCheckActivity)               Backend
    │                                        │
    │ 1. 选/建盘点单                          │
    │ POST /api/stock-checks                  │
    │ {scope:ALL/SKU/LOCATION, keyword}      │
    │ ───────────────────────────────────>  │
    │   后端:                                    │
    │   - 快照当前所有 IN_STOCK 单元到        │
    │     stock_check_item                     │
    │   - system_qty = 当时的 remaining        │
    │ <──────── StockCheck (DRAFT) ────────  │
    │                                        │
    │ 2. 全屏摄像头扫实物                      │
    │ 扫到 24 位码 → 调 /scan                │
    │ POST /api/stock-checks/{id}/scan        │
    │ {code}                                  │
    │ ───────────────────────────────────>  │
    │   后端:                                    │
    │   - 查 inventory_item                    │
    │   - 必须在盘点单明细中(否则 422)         │
    │   - counted_qty += 1                    │
    │ <──────── StockCheck (counted 累加) ──  │
    │                                        │
    │ 3. 提交                                  │
    │ POST /api/stock-checks/{id}/commit      │
    │ ───────────────────────────────────>  │
    │   后端:                                    │
    │   - per-row diff = counted - system      │
    │   - diff_count = sum(|diff|)             │
    │   - 状态 DRAFT → COMMITTED              │
    │ <─ StockCheck (status=COMMITTED) ───  │
    │                                        │
    │ 4. 导出差异报告 CSV                      │
    │ GET /api/stock-checks/{id}/export.csv   │
```

**关键点**:
- **快照机制**:创建盘点单时所有 in_stock 单元被锁到明细,之后这些单元被出库/报废不影响盘点结果
- **未扫 = 盘亏**:盘点单 commit 时,所有明细行的 counted_qty vs system_qty 自动算 diff
- **扫码校验 422**:扫错单子的码被拒(避免混盘)

### 3.5 查询与基础数据

- **PC Web 端**:库存管理 / 出入库单历史 / 仪表盘(库存趋势 + 出库趋势)
- **PDA 端**:扫码查询(LookupActivity)— 24 位码查 inventory_item 详情

## 4. 数据模型概览

详见 [database-schema.md](./database-schema.md)。

13 张表分 5 类:
- **基础数据**(4):`app_user` / `supplier` / `location` / `product`(+ `product_supplier` 多对多)
- **流水**(1):`inventory_log`
- **实物单元**(1):`inventory_item`(核心,每件实物一行,带 24 位 code)
- **单据**(6):`inbound_order` / `inbound_order_item` / `outbound_order` / `outbound_order_item` / `stock_check` / `stock_check_item`
- (索引在数据库 schema 文档)

**核心关系**:`inventory_item.code`(24 位 VARCHAR UNIQUE)是 PDA 扫码的"主键"。

## 5. API 概览

详见 [api-reference.md](./api-reference.md)。

50+ 端点分 10 组:

| 模块 | 端点数 | 关键路径 |
|---|---|---|
| 鉴权 | 4 | `/api/auth/*` |
| 仪表盘 | 3 | `/api/dashboard/{stats, inventory-trend, outbound-trend}` |
| 商品 / 供应商 / 库位 / 用户 | 16 | `/api/{products, suppliers, locations, users}/*` |
| 入库 | 7 | `/api/inbound/{, /orders, /orders/{id}/{items, commit, cancel}, /orders/{id}, /orders, /orders/{id}/export.csv}` |
| 出库 | 7 | `/api/outbound/*`(含 `/scan`) |
| 库存单元 | 11 | `/api/inventory/items/*` |
| 盘点 | 8 | `/api/stock-checks/*`(含 `/scan`) |

**鉴权矩阵**:ADMIN 才能写基础数据 / 创建盘点单;查询 / 扫码 / 出库 / 库存 / 提交 PDA 操作单 任意已登录用户可做。

**扫码端点**(PDA 主路径):
- `POST /api/outbound/orders/{id}/scan` — 出库扫码
- `POST /api/stock-checks/{id}/scan` — 盘点扫码
- `GET /api/inventory/items/by-code/{code}` — 查单元
- `GET /api/inventory/items/by-code/{code}/product` — 码反查商品

**响应壳**:`ApiResponse<T> { code, msg, data }`,`code=0` 成功,`code=404/409/422` 区分业务错误。

## 6. PDA 客户端概览

详见 [pda-design.md](./pda-design.md)。

6 个 Activity + 扫码抽象层 + OkHttp 网络层 + Gson DTO,总 ~3000 行 Java。

**核心架构**:
```
UI → ScanManager (单例)
      ├─ UhfScannerImpl   (UROVO RFID)
      └─ BarcodeScannerImpl (CameraX + ML Kit)
```
UI 层永远只调 `ScanManager.get(ctx).startScan(ScanType, callback)`,不知道底层是哪个厂。

**6 个 Activity**:
1. `LoginActivity` — 服务器地址 + 登录
2. `HomeActivity` — 4 入口 + 硬件状态
3. `InboundScanActivity` — 选/建单 + 选商品+数量 + 提交 + 码打印
4. `OutboundScanActivity` — 选/建单 + 摄像头扫码
5. `StockCheckActivity` — 选/建盘点单 + 摄像头扫码
6. `LookupActivity` — 24 位码查单元(P2 范围)

**关键依赖**:AndroidX CameraX 1.3.4 + ML Kit barcode 17.2.0 + UROVO URFIDLibrary + OkHttp 3.12 + Gson 2.10.1

**manifest 关键点**:`CAMERA` 权限 + `usesCleartextTraffic=true`(调试期允许 http)。

## 7. 用户流程(端到端)

### 7.1 完整入库示例(PC 端 + PDA 混合)

1. **PC Web 端**:管理员新建商品 SP-001 "A4 复印纸",数量 0
2. **PC Web 端**:管理员新建草稿入库单 IN-20260908-001
3. **PC Web 端**:往单子追加明细 SP-001 × 50
4. **PC Web 端**:提交单子 → 后端生成 50 个 24 位码 + 数量增加 + 写流水
5. **打印**:导出 CSV → 标签机打印 50 个码 → 贴 50 包复印纸
6. **PDA 端**:仓库管理员拿着贴好码的 50 包,按其它流程上架

### 7.2 完整出库示例(PDA 端)

1. **PDA 端**:仓库管理员在 OutboundScanActivity 选/建出库单
2. **PDA 端**:扫客户要领的 10 个 24 位码,每扫一个实时校验 + 加入
3. **PDA 端**:提交 → 后端 FIFO 扣减 inventory_item(10 个最早的码)
4. **PDA 端**:Toast 反馈,回 Home

### 7.3 完整盘点示例

1. **PC Web 端**:管理员创建盘点单(sku 范围)
2. **PDA 端**:领 PDA 走仓库,扫看到的实物
3. **PDA 端**:扫到没在本单子里的码 → 422 错误(这个码不在本次盘点范围)
4. **PDA 端**:扫完已扫过的码自动累加 counted_qty
5. **PDA 端**:提交 → 后端算 diff
6. **PC Web 端**:导出差异报告 CSV,看哪些实物少了

## 8. 非功能需求

### 8.1 性能

| 指标 | 目标 |
|---|---|
| 主页加载 | < 1s |
| 扫码到响应(PDA 屏幕) | < 200ms |
| 单据提交(50 个码) | < 3s |
| 库存列表 1000 行 | < 1s |
| 盘点 10000 行快照 | < 5s |
| 仪表盘趋势图 | < 1s |

### 8.2 可用性

- **PDA 上线时间**:从冷启动到扫码可用 < 5s
- **网络异常容错**:扫码 → 网络失败 → Toast 提示,**数据本地不暂存**(本期不支持离线)
- **并发**:管理员+1 个 PDA 操作员同时操作(无锁竞争,基于 JWT + 业务校验)

### 8.3 安全

- **JWT** 24h 过期,操作员需重登
- **BCrypt** 密码哈希
- **HTTPS** 生产(开发用 HTTP)
- **角色控制** ADMIN vs OPERATOR(OPERATOR 预留,当前都按 ADMIN 处理)
- **操作员字段**:每条单据/流水记录操作员 username,审计可追溯

### 8.4 部署

- **后端**:Docker Compose 三服务(mysql 8.0 / backend spring boot:3.3.5 / frontend nginx 1.27)
- **端口**:后端 9090 → 容器 8080,前端 9080 → 容器 80
- **数据**:MySQL 8.0,卷 `simplewms-mysql-data`
- **PDA**:APK 11.6 MB(包含 CameraX + ML Kit),安装到 UROVO PDA 即可

## 9. 已知限制与风险

### 9.1 业务限制

- **入库不扫码**(实物上还没码)
- **没有图片附件**(商品图 / 实物照片)
- **没有批次 / 效期管理**
- **没有序列号管理**(24 位码既是 ID 又是"序列号")
- **没有多仓库**(单仓)
- **OPERATOR 角色预留但未启用**(所有 PDA 当前用 admin)

### 9.2 技术债务(详细见 review 报告)

**Blocker 级**(未修):
- B1:LoginActivity 实际**不调后端**登录
- B2:摄像头 attachCamera 失败不重试
- B3:401 自动 clear 但**不广播回登录页**
- B4:入库行级删除无后端接口
- B5:出库选单 dialog 不可取消(网络失败卡死)
- B6:盘点选单 dialog 布局 ID 可能错

**Medium 级**(不阻断):
- 扫码去重(同码 500ms 内不重复)+ 运行时 CAMERA 权限申请
- ApiClient data 字段是 array 时会抛 IllegalStateException
- ScanManager 单例 Activity 泄漏
- 摄像头重新绑定竞态
- 盘点 commit 要求所有明细 counted_qty 非 null(对扫码模式不友好)
- 盘点端点权限不对称(创建要 ADMIN,扫描不需)

**Spec 过期**:`pda-android-scanner-design.md` 第 31 行说"摄像头扫码 — 本期不做",但 P4-P6 已经实现,**需更新 spec**。

### 9.3 兼容与迁移

- **MySQL 老版(<8.0.29)**:`ADD COLUMN IF NOT EXISTS` 不支持,需手工跑 migration
- **PDA 厂家**:当前只 UROVO,加 iData 需重写 UhfScannerImpl
- **浏览器**:PC 端支持 Chrome / Edge / Firefox 现代版本(ES6+)
- **JDK**:后端 17 / 21,PDA 编译 11

## 10. 后续路线图

### 10.1 P0(立刻)

- 修 review 报告里的 6 个 Blocker(见 §9.2)
- 更新 `pda-android-scanner-design.md` spec,反映 P4-P6 实际实现
- PDA 操作员角色(OPERATOR)权限实施

### 10.2 P1(下迭代)

- 扫码去重 + 运行时 CAMERA 权限
- 离线缓存(扫码暂存 + 网络恢复后批量提交)
- 多 PDA 端并发同步(乐观锁 / 事件溯源)
- 库存预警自动通知(邮件 / 短信)

### 10.3 P2(下季度)

- 多仓库管理
- 批次 / 效期
- 多语言(英文 / 繁体)
- 报表中心(出入库汇总 / 库存周转率 / 呆滞料)
- 与 ERP / WMS 上游系统对接(SAP / 用友)

## 11. 变更记录

| 日期 | 版本 | 变更 | 作者 |
|---|---|---|---|
| 2026-09-08 | 1.0 | 初版,涵盖 PDA 端扫码 / 草稿单流 / 盘点扫码 | Mavis |

## 12. 附录

- A. [数据库结构详细](./database-schema.md) — 13 张表 + 字段 + 索引 + 状态机
- B. [API 接口规范详细](./api-reference.md) — 50+ 端点 + 请求/响应示例
- C. [PDA 客户端设计详细](./pda-design.md) — 6 个 Activity + 扫码抽象 + 网络层
- D. [项目 design 文档](./superpowers/specs/2026-09-07-pda-android-scanner-design.md) — 原始 spec
- E. [实施计划](./superpowers/plans/2026-09-07-pda-android-scanner.md) — 10 任务
- F. [drawio UI 流程图](./superpowers/diagrams/pda-ui-flow.drawio) — 6 个 Activity 导航
