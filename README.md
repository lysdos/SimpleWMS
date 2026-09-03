# SimpleWMS

> 面向办公场景的极简仓库管理系统。**双击 `run.bat` 即可启动**,浏览器即开即用。

## ✨ 功能模块

| 模块 | 能力 |
| ---- | ---- |
| 📊 仪表盘 | 商品种类 / 库存总量 / 库存总值 / 低库存预警,实时统计 |
| 📦 商品管理 | 新增 / 修改 / 删除,字段:SKU、名称、分类、单位、库位、数量、单价、低库存阈值 |
| ⬆️ 入库登记 | 选择商品 → 填数量与备注 → 库存自动增加 + 写流水 |
| ⬇️ 出库登记 | 库存不足时拒绝并明确提示,事务保证数据一致 |
| 📜 库存流水 | 记录每笔出入库(类型/商品/数量/备注/操作员/时间),可按类型 + 关键字检索 |

## 🚀 快速开始

```bat
build.bat       :: 首次构建(需要 Maven 与 JDK 17+)
run.bat         :: 启动应用 → 浏览器访问 http://localhost:8080/
```

启动后:
- 主界面:<http://localhost:8080/>
- H2 控制台(调试用):<http://localhost:8080/h2-console>
  - JDBC URL: `jdbc:h2:file:./data/wmsdb`
  - 用户名 `sa` / 密码空

> 默认使用 **H2 嵌入式数据库**,数据文件位于 `./data/wmsdb.mv.db`。
> 删除整个 `data/` 文件夹即恢复出厂状态(下次启动会重新生成演示数据)。

### 端口被占用?

```bat
run.bat --server.port=9090
```

或在 `src/main/resources/application.yml` 中改 `server.port` 后重新构建。

## 🐬 切换到 MySQL

1. MySQL 中执行 `sql/mysql-schema.sql` 建库
2. 修改 `src/main/resources/application-mysql.yml` 中的账号/密码
3. 用 MySQL 模式启动:

```bat
java -jar target\simple-wms.jar --spring.profiles.active=mysql
```

或在 PowerShell 里:

```powershell
$env:SPRING_PROFILES_ACTIVE="mysql"
java -jar target\simple-wms.jar
```

> 切到 MySQL 后,**演示数据仍会由 DataSeeder 在空表时自动写入**。

## 🛠️ 技术栈

- Java 17+(本仓库用 JDK 21 构建)
- **Spring Boot 3.3.x** + Spring MVC + Spring JDBC (JdbcTemplate)
- **H2 2.x** (MODE=MySQL) 默认 / **MySQL 8** 可切换
- 前端:原生 HTML + 自写 CSS + Vanilla JS,**无任何外部 CDN 依赖**

## 📁 目录结构

```
SimpleWMS/
├── pom.xml
├── README.md
├── build.bat            # 一键构建(支持离线 + 内置 .m2-repo 镜像)
├── run.bat              # 一键启动
├── sql/
│   └── mysql-schema.sql        # MySQL 建库脚本
├── .m2-repo/            # 离线 Maven 镜像(首次构建后生成)
├── data/                # H2 数据文件(运行时生成)
├── target/
│   └── simple-wms.jar   # Spring Boot fat-jar
└── src/main/
    ├── java/com/wms/
    │   ├── WmsApplication.java
    │   ├── model/      Product / InventoryLog / DashboardStats / ApiResponse
    │   ├── dao/        ProductDao / InventoryLogDao
    │   ├── service/    Dashboard / Product / Inbound / Outbound / Ledger
    │   ├── controller/ Dashboard / Product / Inbound / Outbound / Ledger + 异常处理
    │   └── db/         DataSeeder
    └── resources/
        ├── application.yml        # 默认 (H2)
        ├── application-mysql.yml  # MySQL profile
        ├── schema.sql             # H2/MySQL 兼容 DDL
        └── static/                # 前端 SPA
            ├── index.html
            ├── css/app.css
            └── js/  api.js / views.js / app.js
```

## 📡 REST 接口一览

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET  | `/api/dashboard/stats` | 仪表盘统计 |
| GET  | `/api/products?keyword=` | 商品列表(可搜索 SKU/名称/分类) |
| POST | `/api/products` | 新增商品 |
| GET  | `/api/products/{id}` | 商品详情 |
| PUT  | `/api/products/{id}` | 修改商品 |
| DELETE | `/api/products/{id}` | 删除商品 |
| POST | `/api/inbound` | 入库 `{productId,quantity,remark,operator}` |
| POST | `/api/outbound` | 出库,库存不足时返回 409 |
| GET  | `/api/ledger?type=&keyword=&limit=` | 流水(全部/IN/OUT) |

## 🔒 出库事务保护

`OutboundService.doOutbound` 在 `@Transactional` 中完成:
1. 读当前库存 → 校验 `>=` 出库数量
2. `UPDATE product SET quantity = quantity - ? WHERE id = ?`
3. 写入流水

**任何一步失败,整体回滚 — 库存与流水永远一致。**

## ⚙️ 低库存阈值

- 单商品字段 `low_stock`,默认 `10`
- 仪表盘 `lowStockCount` = `quantity <= low_stock` 的商品数
- 商品列表与出库页中低库存商品会用红色高亮

新建商品时的默认阈值在 `application.yml` 中通过 `wms.low-stock-threshold` 配置。

## 🧱 离线构建

构建脚本会按以下顺序寻找 Maven 仓库:

1. `./.m2-repo/` — 项目自带离线镜像(首次构建时从 `~/.m2/repository` 同步)
2. `%USERPROFILE%\.m2\repository` — 本机 Maven 缓存

如需重新同步离线仓库(例如添加了新的依赖),删除 `.m2-repo` 重新 `build.bat` 即可。
## 🐳 Docker 部署

> 一键拉起 MySQL + Backend + Frontend 三个容器,服务名为 `simplewms-xxx`。

### 启动

```bat
docker-start.bat
```

或手动:

```bat
docker compose up -d --build
```

启动后:

- 🌐 前端 (用户访问入口):<http://localhost/>
- 🔌 后端 API (直连):<http://localhost:8080/api/dashboard/stats>
- 🗄️ MySQL (供 Navicat/DBeaver 调试):`localhost:3306` 账号 `wms` / 密码 `wmspass`,库 `simple_wms`

### 停止

```bat
docker-stop.bat
```

或:

```bat
docker compose down          :: 停服务,保留数据卷
docker compose down -v       :: 停服务 + 删除数据卷(数据清空)
```

### 架构

```
[浏览器] -> [simplewms-frontend :80 nginx]
                  |  静态文件 (/css /js /index.html)
                  └─ /api/* -> [simplewms-backend :8080 Spring Boot]
                                          |
                                          └─> [simplewms-mysql :3306 MySQL 8]
```

| 容器 | 镜像 | 端口映射 | 数据持久化 |
| ---- | ---- | -------- | ---------- |
| `simplewms-mysql` | `mysql:8.0` | `3306:3306` (可选) | 卷 `simplewms-mysql-data` |
| `simplewms-backend` | `simplewms-backend:1.0.0` (本地构建) | `8080:8080` (可选) | 无 |
| `simplewms-frontend` | `simplewms-frontend:1.0.0` (本地构建) | `80:80` | 无 |

所有服务在同一个自定义桥接网络 `simplewms-net`,容器间通过服务名互访 (`mysql`、`backend`)。

### 配置文件

- `docker-compose.yml` — 三服务编排
- `docker/Dockerfile.backend` — Spring Boot 镜像构建(多阶段,体积小)
- `docker/Dockerfile.frontend` — nginx 镜像,自带静态文件 + 反向代理
- `docker/nginx.conf` — 反代 `/api/*` 到 `backend:8080`,gzip 已开启

### 调试

```bat
docker compose ps                          :: 查看所有容器状态
docker compose logs -f backend            :: 跟随后端日志
docker compose logs -f mysql              :: 跟随 MySQL 日志
docker compose exec mysql mysql -uwms -pwmspass  :: 进入 MySQL 控制台
docker compose exec backend sh            :: 进入后端容器
docker compose restart backend            :: 只重启后端(代码改了重新构建后用)
```

修改代码后重新构建并启动:

```bat
docker compose up -d --build backend       :: 只重建后端
docker compose up -d --build frontend      :: 只重建前端(静态文件改动)
docker compose up -d --build               :: 全部重建
```

### 离线构建

`Dockerfile.backend` 直接复用项目根目录的 `.m2-repo/`,**无需联网**即可构建后端镜像。

如需清理:

```bat
docker system prune -a                     :: 清理无用镜像
docker volume rm simplewms-mysql-data      :: 删除 MySQL 数据卷(慎用!)
```

### 端口冲突?

如 80 / 8080 / 3306 已被占用,编辑 `docker-compose.yml` 把映射改成其他端口:

```yaml
ports:
  - "8081:80"          # 前端改到 8081
  - "8080:8080"        # 后端保持
  - "3307:3306"        # MySQL 改到 3307
```
