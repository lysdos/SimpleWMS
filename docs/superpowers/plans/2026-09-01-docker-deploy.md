# SimpleWMS Docker Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将本地 SimpleWMS 项目以 docker compose 方式部署到容器内,用户可通过 `http://localhost:8081/` 访问完整系统。

**Architecture:** 沿用现有 `docker-compose.yml` 的三服务编排(mysql + backend + frontend),仅修改 frontend 端口映射 `80:80 → 8081:80` 以避让宿主机 80 端口冲突,然后通过 `docker-start.bat` 一键构建并启动。

**Tech Stack:** Docker 29.x、docker compose v2/v5、Spring Boot 3.3.5(运行镜像)、MySQL 8.0、Alpine nginx 1.27。

## Global Constraints

- 项目根目录:`E:\Code\SimpleWMS`(Windows 11,Git Bash 环境)
- 项目不是 git 仓库,本计划**不包含 commit 步骤**(所有"提交"步骤已省略)
- 不修改源码、Dockerfile、nginx.conf、mysql-schema.sql
- 不升级镜像版本:backend 保持 `1.0.0`,frontend 保持 `1.0.0`
- 不推送镜像到远端 registry
- 所有脚本使用项目根目录的 `.bat` 或 `docker compose` 命令,均在 `E:\Code\SimpleWMS` 下执行
- 时间戳:今天 = 2026-09-01

## File Structure

本计划仅涉及一个文件修改:

- **Modify:** `E:\Code\SimpleWMS\docker-compose.yml`
  - 改一行:frontend 服务的 ports 从 `"80:80"` → `"8081:80"`

其余文件(`docker/Dockerfile.backend`、`docker/Dockerfile.frontend`、`docker/nginx.conf`、`sql/mysql-schema.sql`、`docker-start.bat`、`docker-stop.bat`、`target/simple-wms.jar`、`pom.xml`、源码)全部**只读**,不修改。

---

### Task 1: 修改 docker-compose.yml 的 frontend 端口

**Files:**
- Modify: `E:\Code\SimpleWMS\docker-compose.yml`(只改 frontend 服务的 ports 一行)

**Interfaces:**
- Consumes: 无
- Produces: 修改后的 `docker-compose.yml`,frontend 服务 ports 段为 `- "8081:80"`

- [ ] **Step 1: 用 Read 工具读取 `docker-compose.yml` 的 frontend 段(行 76-91)**

确认要修改的位置精确是 frontend 服务块的 `ports:` 段下的 `- "80:80"`。

- [ ] **Step 2: 用 Edit 工具把 `- "80:80"` 替换为 `- "8081:80"`**

```yaml
    ports:
      # 用户访问入口(原 80:80,因宿主机 80 占用改 8081)
      - "8081:80"
```

old_string 必须唯一指向原文件行 `- "80:80"`(grep 确认 compose 里只出现这一次,而不是 MySQL 或 backend 的某行)。注意 MySQL 端口是 `3306:3306`,backend 端口是 `8080:8080`,不要误改。

- [ ] **Step 3: 用 Read 工具复核修改后的 frontend 段**

打开文件,确认行 88 附近现在显示 `- "8081:80"`,其余两服务的 ports 未动。

---

### Task 2: 启动 docker-compose 全栈

**Files:**
- 无文件修改,只运行脚本

**Interfaces:**
- Consumes: Task 1 修改后的 `docker-compose.yml`
- Produces: 3 个正在运行的容器(simplewms-mysql、simplewms-backend、simplewms-frontend)

- [ ] **Step 1: 切换工作目录**

```bash
cd "E:/Code/SimpleWMS"
```

- [ ] **Step 2: 执行 `docker-start.bat`**

```bash
./docker-start.bat
```

脚本内部:
1. 检查 docker / docker compose 是否安装(预期已安装,直接通过)
2. 打印启动横幅
3. 执行 `docker compose up -d --build`(全量构建+后台启动)
4. 等 8 秒
5. 执行 `docker compose ps` 显示状态

预期输出:
- `[INFO] 构建并启动所有服务 ...` 之后是一堆 build/pull 日志
- 最后 `[INFO] 服务状态:` 下方表格中 3 个服务均为 `Up`(mysql 显示 `Up (healthy)`,其他可能 `Up`)

**构建时间预期:** 首次构建约 1-3 分钟(取决于本地镜像缓存)。`maven:3.9.6-eclipse-temurin-21`、`eclipse-temurin:21-jre`、`nginx:1.27-alpine`、`mysql:8.0` 基础镜像大部分已在本地(检查时确认存在)。

- [ ] **Step 3: 如脚本中 healthcheck 显示 mysql 未 healthy,等 30 秒再 `docker compose ps`**

```bash
docker compose ps
```

预期:simplewms-mysql 状态显示 `(healthy)`,simplewms-backend 与 simplewms-frontend 显示 `Up`。

如 mysql 一直 `starting`,查看:
```bash
docker compose logs mysql --tail 50
```

---

### Task 3: 验证部署

**Files:**
- 无文件修改

**Interfaces:**
- Consumes: Task 2 启动的 3 容器
- Produces: 验证报告(全部通过 = 部署成功)

- [ ] **Step 1: 容器状态全 Up 检查**

```bash
cd "E:/Code/SimpleWMS" && docker compose ps
```

预期:3 个容器都 `Up`,mysql 列 `(healthy)`。

- [ ] **Step 2: 经 nginx 反代调 API(用户视角)**

```bash
curl -sS http://localhost:8081/api/dashboard/stats
```

预期:返回 JSON,形如 `{"code":0,"message":"success","data":{"productCount":N,"totalQuantity":N,...}}`(演示数据 6 件商品)。

- [ ] **Step 3: 浏览器入口返回 200**

```bash
curl -sS -I http://localhost:8081/
```

预期:第一行 `HTTP/1.1 200 OK`,`Server: nginx/1.27.x`。

- [ ] **Step 4: 直连 backend API(开发者视角)**

```bash
curl -sS http://localhost:8080/api/dashboard/stats
```

预期:与 Step 2 返回的 JSON 完全一致。

- [ ] **Step 5: MySQL 表可见**

```bash
docker compose exec mysql mysql -uwms -pwmspass -e "USE simple_wms; SHOW TABLES;"
```

预期:列出 `product`、`inventory_log` 两张表。

- [ ] **Step 6: 商品列表接口返回演示数据**

```bash
curl -sS "http://localhost:8081/api/products"
```

预期:JSON `data` 数组中至少含 6 件商品的字段(SKU、名称、分类、数量等),说明 Spring Boot 通过 mysql profile 成功连上容器内 MySQL,演示数据由 DataSeeder 写入。

---

### Task 4: 完成与交接

**Files:**
- 无文件修改

- [ ] **Step 1: 打印汇总**

向用户报告:
- 部署成功,3 容器已 Up
- 访问入口:`http://localhost:8081/`
- API 直连:`http://localhost:8080/`
- MySQL 调试:3306,wms/wmspass,库 simple_wms
- 停止:`docker-stop.bat`
- 重启:`docker-start.bat`

- [ ] **Step 2: 提示 spec 文件位置**

告诉用户 spec 在 `E:\Code\SimpleWMS\docs\superpowers\specs\2026-09-01-docker-deploy-design.md`,plan 在 `E:\Code\SimpleWMS\docs\superpowers\plans\2026-09-01-docker-deploy.md`,供将来参考或回退。

- [ ] **Step 3: 不主动 commit / push**

项目不是 git 仓库,本计划不产生 commit。如需把 spec/plan 纳入 git,用户自行决定是否手动初始化仓库并提交。