# SimpleWMS

> **一物一码的轻量级仓库管理系统**：PC Web、Android 工业 PDA、UHF RFID 终端协同作业。

`Java 21` · `Spring Boot 3.3` · `MySQL 8` · `Android 工业手持机` · `Docker Compose`

SimpleWMS 为每件实物生成一个 **24 位唯一码**：入库时生成并打印贴标，出库、盘点时扫码核销。
PC 端负责主数据与单据管理，工业 PDA 负责现场扫码作业，UHF RFID 终端负责批量读取，
三端共用同一套 REST API 和库存台账。

---

## 目录

- [核心特性](#核心特性)
- [功能一览](#功能一览)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [端口与入口](#端口与入口)
- [快速开始](#快速开始)
- [Android 工业 PDA](#android-工业-pda)
- [UHF RFID 终端](#uhf-rfid-终端)
- [核心业务模型](#核心业务模型)
- [目录结构](#目录结构)
- [配置速查](#配置速查)
- [API 约定](#api-约定)
- [文档索引](#文档索引)
- [已知边界](#已知边界)

---

## 核心特性

- **一物一码**：每件实物对应一条 `inventory_item`（24 位码，数据库唯一），库存精确到件。
- **三端协同**：Web 管理端、Android 工业 PDA、UHF RFID 终端调用同一套后端接口，数据实时一致。
- **单据状态机**：出入库单、盘点单统一 `DRAFT → COMMITTED / CANCELLED`；草稿单并发写用行锁串行化。
- **扫码作业**：出库扫码校验码状态，盘点扫码自动记已盘、快照外的码自动记盘盈。
- **FEFO 与效期**：支持生产日期 / 保质期，临期与过期看板；出库按先到期先出跨库位自动锁货。
- **可追溯**：流水记录操作员、单号、库位；每件货都能查到入库单、出库单和当前位置。
- **导入导出**：基础数据 CSV 导入先预检后落库；出入库单、库存、盘点差异支持 CSV / ZIP 导出。
- **权限与安全**：JWT + BCrypt，`ADMIN` / `OPERATOR` 两级角色，登录失败限流，首个管理员首登强制改密。
- **中英双语**：Web 与 PDA 均内置中文 / English 切换。

---

## 功能一览

### Web 端（PC）

| 模块 | 能力 |
| ---- | ---- |
| 仪表盘 | 商品种类 / 库存总量 / 库存总值 / 低库存 / 今日出入库；库存与出库趋势、临期与过期批次、最近操作 |
| 入库管理 | 草稿单新建、追加 / 修改 / 删除明细、提交生成 24 位码；批量入库、CSV 导入、条码打印与单据导出 |
| 出库管理 | 草稿单新建、追加明细、锁定库存、提交出库；拣货明细（按库位 / 条码）、单据导出 |
| 出入库单 | 入库单与出库单统一列表，按单号 / 供应商 / 客户 / 外部单号 / 备注 / 状态 / 操作员 / 日期筛选 |
| 库存管理 | 库存单元台账，按 SKU / 24 位码 / 库位 / 仓库 / 状态 / 效期筛选；条码图、调拨、报废、CSV 导出 |
| 库存盘点 | 按全部 / SKU / 仓库建单快照，扫码或手工填实盘数，盘盈行、差异统计、差异报告导出 |
| 基础管理 | 商品（多供应商关联、默认供应商、供货价）、供应商、仓库、库位 |
| 用户管理 | 用户增删改、角色与启停、重置密码、导出（不落明文密码） |
| 系统能力 | 登录 / 登出、修改密码、首次登录强制改密、中英文切换 |

基础数据（商品 / 供应商 / 仓库 / 库位）均支持分页、关键字筛选、CSV 模板下载、
“预检 → 确认”两步导入、全量或选中项导出。

**角色权限**：`ADMIN` 可维护商品 / 供应商 / 仓库 / 库位 / 用户并执行报废；
`OPERATOR` 可使用仪表盘、出入库、库存查询 / 调拨、盘点等作业功能，
但不能维护基础数据，也不能报废（报废是不可逆减库存）。

### Android 工业 PDA

| 页面 | 能力 |
| ---- | ---- |
| 登录 / 设置 | WMS 账号登录、服务器地址配置与连通性测试、恢复默认、语言切换 |
| 仪表盘 | 商品种类、库存总量、库存总值、低库存 SKU、临期 / 过期批次 |
| 入库 | 选建草稿单、选商品与数量、追加 / 修改明细、提交、查看生成的 24 位码 |
| 出库 | 选建草稿单、硬件扫码 / 摄像头扫码 / UHF EPC 扫码加货、提交出库 |
| 库存 | 库存单元查询与筛选、调拨、报废 |
| 盘点 | 盘点单列表、新建盘点、扫码盘点、手工计数、提交 |

### UHF RFID 终端

`UHF-Rader/` 是一个独立的 Flask 桥接服务：通过 TCP 连接固定式 RFID 读写器，
提供本机 Web 终端，并复用 WMS 出库单完成 **派单 → 自动领单 → EPC 扫码确认 → 清空重扫**。
它以固定账号调用 WMS API（默认与管理员同账号），操作员姓名随扫码记录写入审计字段。

---

## 系统架构

```
浏览器 / Android PDA
          │  HTTP :9080（局域网唯一入口）
          ▼
  simplewms-frontend（nginx）
    ├── 静态页面 frontend/
    └── /api/*  ─────────────────┐
                                 ▼
                    simplewms-backend（Spring Boot :8080）
                       ├── JWT 鉴权 / 角色拦截
                       ├── 业务 Service + 事务
                       └── JdbcTemplate
                                 │
                                 ▼
                    simplewms-mysql（MySQL 8 :3306）

UHF 终端浏览器 ──▶ simplewms-uhf-rader（Flask，宿主 :5001）
                       ├── /api/* ──▶ backend:8080（容器网络内直连）
                       └── TCP ──▶ RFID 读写器（默认 192.168.1.209:5000）
```

- 后端是**纯 API**，不提供界面；前端静态文件只由 nginx 镜像打包一份。
- Web 与 PDA 只需访问 **9080**；UHF 终端访问自己的 `5001`，由桥接服务在容器内调用后端。
- 后端 8080 只映射到宿主机 `127.0.0.1:9090`，仅供本机调试。
- 所有表结构集中在 `sql/mysql-schema.sql`，由 MySQL 容器首次初始化时自动执行。

---

## 技术栈

| 层级 | 技术 |
| ---- | ---- |
| 后端 | Java 21、Spring Boot 3.3.5、Spring MVC、Spring JDBC（JdbcTemplate）、Maven |
| 鉴权 | JJWT 0.12、BCrypt（`spring-security-crypto`） |
| 数据库 | MySQL 8.0（InnoDB / utf8mb4），表结构由 `sql/mysql-schema.sql` 统一管理 |
| Web 前端 | 原生 HTML + CSS + Vanilla JS，无 CDN、无构建步骤 |
| Android PDA | Java（source/target 11）、minSdk 23 / targetSdk 34、CameraX + ML Kit、OkHttp + Gson、CHAINWAY DeviceAPI |
| UHF 终端 | Python 3 + Flask，读写器 TCP 协议 |
| 部署 | Docker Compose、nginx 1.27、多阶段镜像构建 |

---

## 端口与入口

| 端口（宿主） | 服务 | 访问范围 | 说明 |
| ---- | ---- | ---- | ---- |
| `9080` | frontend（nginx） | 局域网 | **客户端唯一入口**：Web 界面 + `/api/*` 反向代理 |
| `9090` | backend（Spring Boot） | 仅本机 | 纯 API，调试用；局域网访问不到 |
| `23306` | MySQL 8 | 局域网 | 映射容器 3306，供 Navicat / DBeaver 调试 |
| `5001` | UHF-Rader | 局域网 | RFID 终端自己的 Web UI（独立应用） |

> PDA 必须使用 `9080`（或它对应的局域网 IP + 端口），不要直连 `9090`；
> UHF 终端访问自己的 `5001`，由桥接服务内部调用后端。

---

## 快速开始

### 方式一：Docker 一键部署（推荐）

前置：Docker Engine 24+、Docker Compose v2.20+。

```bash
cp .env.example .env
```

编辑 `.env`，至少设置以下变量：

| 变量 | 说明 |
| ---- | ---- |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 |
| `MYSQL_PASSWORD` | 业务库 `wms` 用户密码 |
| `ADMIN_PASSWORD` | 初始管理员密码，至少 8 位，且不能是 `admin123` / `change-me-now` / `password` |
| `JWT_SECRET` | JWT 签名密钥，至少 32 字节：`openssl rand -base64 48` |
| `WMS_USERNAME` / `WMS_PASSWORD` | UHF-Rader 调用 WMS 的账号密码，默认与管理员账号一致 |
| `WMS_LOGIN_NO_THROTTLE_TOKEN` | UHF 容器登录限流豁免密钥：`openssl rand -base64 32` |

然后启动：

```bash
docker compose --env-file .env up -d --build
```

Windows 下也可直接双击 `docker-start.bat`（等价于 `docker compose up -d --build`）。

首次启动会构建后端与前端镜像、拉取 MySQL 镜像并执行 `sql/mysql-schema.sql` 建库建表。
就绪后打开：

> **http://localhost:9080/**

使用 `.env` 中的 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 登录；首个管理员首次登录会强制修改密码。

```bash
docker compose ps                    # 查看容器状态
docker compose logs -f backend       # 跟随日志
docker compose down                  # 停止,保留数据卷
docker compose down -v               # 停止并清空数据(慎用)
```

### 方式二：本机裸跑后端

本机方式只启动 Spring Boot，**没有 Web 界面**（界面由 Docker 的 nginx 提供）。
数据库仍建议用容器：

```bash
docker compose --env-file .env up -d mysql
```

然后设置启动所需的环境变量：

```bash
# Windows CMD
set JWT_SECRET=<至少 32 字节的随机串>
set ADMIN_PASSWORD=<初始管理员密码>
```

```powershell
# PowerShell
$env:JWT_SECRET = "<至少 32 字节的随机串>"
$env:ADMIN_PASSWORD = "<初始管理员密码>"
```

构建并启动：

```bash
build.bat        # 构建 target/simple-wms.jar（支持 .m2-repo 离线构建）
run.bat          # 启动后端 → http://localhost:8080/api/...
```

默认连接 `localhost:23306` 的 MySQL。若 `.env` 改过数据库账号，还需设置
`SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`。
端口冲突可用 `run.bat --server.port=8190`。

> 全新数据库只创建首个 `ADMIN` 账号，不写入演示数据；商品、供应商、仓库、库位需自行建立，
> 或手动执行 `tools/seed.sql`。

---

## Android 工业 PDA

`pda-android/` 是原生 Java Android App，面向 **工业手持机（CHAINWAY 系列）** 的仓库现场作业，
也兼容普通 Android 设备（摄像头扫码）。

### 支持的设备与扫描方式

| 能力 | 说明 |
| ---- | ---- |
| UHF RFID | CHAINWAY DeviceAPI（`RFIDWithUHFUART`）读取 EPC，用于出库 / 盘点扫码 |
| 硬件扫码头 | 通过广播或 `BarcodeDecoder` 读取条码，速度快于摄像头 |
| 摄像头兜底 | 无扫码头时使用 CameraX + ML Kit 识别条码 |
| 系统版本 | `minSdk 23`（Android 6.0+），现场设备实测 Android 11 |
| 通用 Android | 可使用摄像头扫码；没有 UHF / 扫码头硬件时对应功能不可用 |

扫码能力封装在 `ScanManager`：UI 只调用 `startScan(ScanType, callback)`，
UHF、硬件扫码头、摄像头三种实现按设备能力自动选择，业务代码不感知厂商差异。

### 构建与安装

```bash
cd pda-android

./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 签名发布包

adb install -r app/build/outputs/apk/release/app-release.apk
```

Windows 用 `gradlew.bat`。安装前需在 PDA 上开启「开发者选项 → USB 调试」。

> release 签名使用仓库内的 `pda-release.jks`（演示用）。正式分发前请替换为自己的
> keystore，并修改 `app/build.gradle` 中的 `signingConfigs`。

### 配置后端地址

- 打包默认值：`pda-android/app/build.gradle` 中的 `API_BASE_URL`，
  默认 `http://192.168.11.160:9080`，真机改为运行 Docker 那台电脑的**局域网 IP**。
- App 内「设置」可直接修改、测试连接、恢复默认；**已保存的地址优先于打包默认值**。
- 端口必须是 `9080`（nginx），不要填 `9090`（只绑本机回环）。
- 模拟器访问宿主机用 `http://10.0.2.2:9080`。

### 使用注意

- PDA 与后端需在同一局域网；现场 APK 允许 HTTP 明文（`network_security_config.xml`）。
- 登录 token 使用 `EncryptedSharedPreferences` 加密存储。
- 库存**调拨**仅移动位置、状态保持 `IN_STOCK`；**报废**为不可逆终态，仅 `ADMIN` 可操作。
- `pda-android/INSTALL.md` 是早期安装说明，其中“暂无设置入口”等描述已过时，以本节为准。

---

## UHF RFID 终端

RFID 读写器通过 `UHF-Rader` 桥接接入 WMS：

- 读写器以 TCP Server 方式提供 EPC 数据，桥接服务作为 TCP Client 轮询读取。
- 终端 Web UI（`:5001`）提供出库草稿单列表、选单、自动认领派单、扫码结果与清空重扫。
- EPC 为 24 位十六进制码，与 WMS `inventory_item.code` 对应。
- 桥接服务使用 `.env` 中 `WMS_USERNAME` / `WMS_PASSWORD` 登录 WMS，
  操作员姓名由终端会话转发，写入扫码审计。

相关配置：`UHF_READER_HOST`、`UHF_READER_PORT`、`UHF_DEVICE_CODE`。
[UHF-Rader/README.md](UHF-Rader/README.md) 保留了读写器 TCP 数据格式与早期演示版本的说明。

---

## 核心业务模型

### 一物一码

- 入库单**提交**时，后端为每一件货生成一个 24 位 `inventory_item.code`
  （`yyyyMMddHHmmss` + SKU hash + 随机串，数据库唯一索引兜底）。
- 码打印后贴到实物；出库、盘点、查询都以该码为准。
- 入库时实物上还没有码，因此**入库不扫码**，扫码发生在出库 / 盘点 / 查询环节。

### 单据状态

出入库单与盘点单共用三态：

| 状态 | 含义 |
| ---- | ---- |
| `DRAFT` | 草稿：可增删改明细、可取消；入库不增库存，出库只锁定库存 |
| `COMMITTED` | 已提交：入库生成单元并入账，出库扣减，盘点固化差异 |
| `CANCELLED` | 已取消：释放占位 / 预留单元，不再变更库存 |

### 库存单元状态

| 状态 | 含义 |
| ---- | ---- |
| `IN_STOCK` | 在库，可出库 / 调拨 / 报废 / 盘点 |
| `PENDING` | 待入库：入库草稿单生成的占位单元，提交后才生效 |
| `RESERVED` | 已预留：被出库草稿单锁定，提交后变 `OUT`，取消则回 `IN_STOCK` |
| `OUT` | 已出库 |
| `SCRAPPED` | 已报废（不可逆终态） |
| `TRANSFERRED` | 已调拨（历史状态；当前调拨只改库位，状态保持 `IN_STOCK`） |

### 数据表

共 14 张表：

- **基础数据**：`app_user`、`supplier`、`warehouse`、`location`、`product`、`product_supplier`
- **库存单元**：`inventory_item`（核心）
- **流水**：`inventory_log`
- **单据**：`inbound_order` / `inbound_order_item`、`outbound_order` / `outbound_order_item`、
  `stock_check` / `stock_check_item`

仓库是库位的上层分组（`location.warehouse_id`），库位编码全局唯一；库存与单据目前按**全局单仓**
核算，不按仓库隔离。详见 [docs/database-schema.md](docs/database-schema.md)。

---

## 目录结构

```
SimpleWMS/
├── src/main/java/com/wms/
│   ├── WmsApplication.java
│   ├── controller/        REST 接口（鉴权 / 单据 / 基础数据 / 盘点 / 异常处理）
│   ├── service/           业务逻辑与事务边界（含导入、条码、JWT、密码、限流）
│   ├── dao/               JdbcTemplate 数据访问
│   ├── model/             实体、DTO、分页与统一响应
│   ├── filter/            JWT 鉴权、用户状态校验
│   ├── web/               角色拦截器、Web 配置
│   ├── util/              CSV / JSON / 日期工具
│   ├── config/            配置类
│   └── db/                初始化数据
├── src/main/resources/    application.yml / application-mysql.yml
├── src/test/java/         JUnit 5 + Mockito 单元测试
├── frontend/              Web 前端源码（原生 HTML / CSS / JS，无 CDN、无构建）
├── pda-android/           Android 工业 PDA App（Java）
├── UHF-Rader/             RFID 读写器桥接 + 终端页面（Python / Flask）
├── sql/mysql-schema.sql   MySQL 建库建表脚本
├── docker/                Dockerfile.backend / Dockerfile.frontend / nginx.conf
├── docs/                  PRD、API、数据库、部署、PDA 设计文档
├── tools/                 种子数据与辅助脚本
├── docker-compose.yml     四服务编排
├── build.bat / run.bat    本机构建 / 启动后端
├── docker-start.bat       一键 Docker 启动
└── .m2-repo/              离线 Maven 镜像（可选）
```

---

## 配置速查

### `.env`（Docker 部署）

模板见 [.env.example](.env.example)。核心变量：

| 变量 | 必填 | 默认 / 说明 |
| ---- | ---- | ---- |
| `MYSQL_ROOT_PASSWORD` | 是 | MySQL root 密码 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 是 | 业务库账号，默认 `wms` / `wmspass` |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | 是 | 首个管理员账号；弱口令会拒绝启动 |
| `JWT_SECRET` | 是 | ≥ 32 字节，留空拒绝启动 |
| `JWT_TTL_HOURS` | 否 | 默认 `24` |
| `WMS_USERNAME` / `WMS_PASSWORD` | 是 | UHF-Rader 调 WMS 的账号，与管理员一致 |
| `WMS_LOGIN_NO_THROTTLE_TOKEN` | 是 | UHF 容器登录限流豁免密钥 |
| `UHF_READER_HOST` / `UHF_READER_PORT` | 否 | RFID 读写器 TCP 地址 |
| `UHF_DEVICE_CODE` | 否 | WMS 中的设备号，默认 `MOCK-UHF-001` |

> 修改 `MYSQL_PASSWORD` 后必须执行 `docker compose down -v` 重建数据卷才会生效，数据会清空。

### `application.yml`（应用参数）

| 配置 | 默认 | 说明 |
| ---- | ---- | ---- |
| `server.port` | `8080` | 容器内端口 |
| `wms.low-stock-threshold` | `10` | 新建商品的默认低库存阈值 |
| `wms.import.max-rows` | `5000` | 基础数据单次导入行数上限 |
| `wms.inbound.import.max-units` | `10000` | 入库导入单次件数上限 |
| `spring.servlet.multipart.max-file-size` | `10MB` | 上传文件大小；nginx 侧同步限制 |

---

## API 约定

- 入口：`http://localhost:9080/api/*`（经 nginx）或 `http://127.0.0.1:9090/api/*`（本机调试）。
- 鉴权：除登录与库存条码图外，均需 `Authorization: Bearer <jwt>`。
- 统一响应：`{ "code": 0, "message": "ok", "data": ... }`，成功 `code=0`。
- HTTP 状态码与业务 `code` 保持一致：`400` 参数错误、`401` 未登录、`403` 权限不足、
  `404` 资源不存在、`409` 业务冲突、`422` 扫码校验失败、`429` 登录限流。
- 分页响应：`{ list, total, page, pageSize, pages }`，`pageSize` 上限 200。

主要接口分组：

| 模块 | 路径 |
| ---- | ---- |
| 鉴权 | `/api/auth/*` |
| 仪表盘 | `/api/dashboard/*` |
| 商品 / 供应商 / 仓库 / 库位 / 用户 | `/api/{products,suppliers,warehouses,locations,users}/*` |
| 入库 | `/api/inbound/*` |
| 出库 | `/api/outbound/*` |
| 库存单元 | `/api/inventory/items/*` |
| 盘点 | `/api/stock-checks/*` |
| 统一单据列表 | `/api/orders` |

完整端点、请求响应示例与错误码见 [docs/api-reference.md](docs/api-reference.md)。

---

## 文档索引

| 文档 | 内容 |
| ---- | ---- |
| [docs/PRD.md](docs/PRD.md) | 产品范围、角色、业务流程、非功能需求 |
| [docs/api-reference.md](docs/api-reference.md) | REST 接口规范、鉴权矩阵、错误码 |
| [docs/database-schema.md](docs/database-schema.md) | 14 张表结构、索引、状态机、数据生命周期 |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Docker 部署、`.env` 详解、备份恢复、生产加固 |
| [docs/pda-design.md](docs/pda-design.md) | PDA 客户端模块设计 |
| [UHF-Rader/README.md](UHF-Rader/README.md) | RFID 读写器 TCP 数据格式与早期演示说明 |

> 部分设计文档（PRD / pda-design / pda-android/INSTALL.md）保留了历史基线，
> 与当前实现不一致处以代码和本 README 为准。

---

## 已知边界

- **单仓核算**：仓库用于库位分组与筛选；库存数量、单据、盘点不按仓库隔离，库位编码全局唯一。
- **不支持离线**：PDA 与 UHF 终端均需网络可达才能作业。
- **入库不扫码**：入库前实物没有码，24 位码由后端在提交时生成。
- **无独立批次 / 序列号体系**：效期落在库存单元上（生产日期 + 保质期），不维护批次主数据。
- **PDA / UHF 依赖 nginx**：两者都走 `9080`，nginx 不可用时现场无法作业。
- **登录限流为进程内计数**：多实例部署时需改为共享存储。
- **导出上限**：库存 CSV 单次 5000 行；基础数据导入单次 5000 行、入库导入 10000 件。
- **AI 助手开发中**：设计文档与模型 provider 客户端已在 `design/ai-assistant` 分支推进，
  尚未对外提供接口与界面；当前版本请使用现有查询与筛选功能。

---

## 开发命令

```bash
mvn -q compile          # 编译后端
mvn test                # 后端单元测试
mvn -DskipTests package # 打包 target/simple-wms.jar
build.bat               # 离线构建（优先 .m2-repo）
run.bat                 # 启动后端
```

前端无需构建：修改 `frontend/` 后重建 frontend 镜像即可（`docker compose build frontend`）。
PDA 测试：`cd pda-android && ./gradlew test`。
