# SimpleWMS 部署手册

> 面向运维/开发同学的 **Docker 部署** 全流程。从 clone 代码到能登录系统,一步步走通。

---

## 目录

- [1. 环境要求](#1-环境要求)
- [2. 快速开始](#2-快速开始)
- [3. 配置 `.env` 详解](#3-配置-env-详解)
- [4. 日常操作](#4-日常操作)
- [5. 架构与端口](#5-架构与端口)
- [6. 常见问题](#6-常见问题)
- [7. 升级与回滚](#7-升级与回滚)
- [8. 生产环境加固](#8-生产环境加固)

---

## 1. 环境要求

| 组件 | 最低版本 | 说明 |
|------|----------|------|
| Docker Engine | 24.0+ | 桌面版 / Server 版均可 |
| Docker Compose | v2.20+ | 跟随 Docker Desktop 自带 |
| 可用内存 | 2 GB | MySQL 512M + Backend 512M + 系统 |
| 可用磁盘 | 5 GB | 镜像 + MySQL 数据卷 |

验证环境:

```bash
docker --version
docker compose version
```

---

## 2. 快速开始

### 2.1 拉取代码

```bash
git clone <你的仓库地址> SimpleWMS
cd SimpleWMS
```

### 2.2 准备 `.env`

```bash
cp .env.example .env
```

打开 `.env`,**至少**改两处:

| 变量 | 怎么改 | 示例 |
|------|--------|------|
| `JWT_SECRET` | `openssl rand -base64 48` 生成 | `uBzHZQ3...` 一长串随机串 |
| `MYSQL_ROOT_PASSWORD` | 改成一个**强密码** | `Root@2026!secure` |
| `MYSQL_PASSWORD` | 改成一个**强密码**(不能与 root 相同) | `Wms@2026!app` |
| `ADMIN_PASSWORD` | 首次登录后台的密码 | `Admin@2026!` |

> ⚠️ `JWT_SECRET` **留空时容器会拒绝启动**(`${JWT_SECRET:?...}` 强制),这是设计如此,不是 bug。

### 2.3 启动服务

```bash
docker compose --env-file .env up -d --build
```

首次启动会做四件事:

1. **构建 backend 镜像**(Maven 离线编译,~2 分钟,后续增量秒级)
2. **构建 frontend 镜像**(Nginx 静态文件,秒级)
3. **拉取 MySQL 8.0 镜像**(~150 MB,首次会下载)
4. **初始化 MySQL 数据卷** + 自动跑 `sql/mysql-schema.sql`(建库、建表、GRANT 业务用户权限)

等几秒,查看状态:

```bash
docker compose ps
```

正常输出(全部 `healthy`):

```
NAME                STATUS
simplewms-mysql     Up X minutes (healthy)
simplewms-backend    Up X minutes (healthy)
simplewms-frontend   Up X minutes (healthy)
```

### 2.4 访问系统

打开浏览器:

> **http://localhost:9080/**

用 `.env` 里设置的 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 登录。

---

## 3. 配置 `.env` 详解

完整模板见仓库根目录的 [`.env.example`](../.env.example)。

### 3.1 变量速查

| 变量 | 必填 | 默认值 | 作用 |
|------|------|--------|------|
| `MYSQL_ROOT_PASSWORD` | 是 | `rootpass` | MySQL root 密码(运维用,业务不用) |
| `MYSQL_USER` | 是 | `wms` | 业务数据库用户名 |
| `MYSQL_PASSWORD` | 是 | `wmspass` | 业务数据库密码 |
| `ADMIN_USERNAME` | 否 | `admin` | 后台管理员账号 |
| `ADMIN_PASSWORD` | 是 | `change-me-now` | 后台管理员初始密码 |
| `JWT_SECRET` | **是** | (空,强制) | JWT 签名密钥,至少 32 字节 |
| `JWT_TTL_HOURS` | 否 | `24` | JWT 过期时间(小时) |

### 3.2 改密码的注意事项

> ⚠️ **改 `MYSQL_USER` / `MYSQL_PASSWORD` 后必须重置数据卷才能生效。**

```bash
docker compose down -v      # 删容器 + 删 mysql-data volume(数据全丢!)
docker compose --env-file .env up -d --build
```

**原因**:MySQL 镜像只在 `data` 目录为空(首次启动)时,用 env 里的 `MYSQL_PASSWORD` 创建/重置用户。已存在的 volume 里用户密码不会被 env 改动。

如需保留数据,只能:
1. 进入 mysql 容器手动改密码:`docker exec -it simplewms-mysql mysql -uroot -p`
2. 或者用 `ALTER USER 'wms'@'%' IDENTIFIED BY '新密码';` SQL 语句

### 3.3 改 `JWT_SECRET` 的影响

**改完会让所有已签发的 token 失效,所有用户必须重新登录。** 适合"怀疑密钥泄露"时紧急轮换。无需重启 MySQL,只重启 backend 即可:

```bash
docker compose --env-file .env up -d --force-recreate backend
```

---

## 4. 日常操作

### 4.1 查看状态

```bash
docker compose --env-file .env ps
```

### 4.2 查看日志

```bash
# 实时跟踪所有服务
docker compose --env-file .env logs -f

# 跟踪某个服务
docker compose --env-file .env logs -f backend

# 看最近 100 行
docker compose --env-file .env logs --tail=100 backend
```

### 4.3 进入容器调试

```bash
# 进 backend 容器
docker exec -it simplewms-backend sh

# 进 mysql 容器
docker exec -it simplewms-mysql mysql -uroot -p
```

### 4.4 停止 / 启动

```bash
# 停止(数据保留)
docker compose --env-file .env down

# 启动
docker compose --env-file .env up -d

# 停止 + 删除所有数据(慎用!)
docker compose --env-file .env down -v
```

### 4.5 重新构建(改了代码后)

```bash
# 改了什么 → 重打哪个
docker compose --env-file .env build backend    # 只重打后端
docker compose --env-file .env build frontend   # 只重打前端
docker compose --env-file .env build            # 全部重打

# 重打并替换容器
docker compose --env-file .env up -d --build
```

### 4.6 备份与恢复

#### 备份 MySQL

```bash
docker exec simplewms-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" simple_wms' > backup_$(date +%Y%m%d).sql
```

#### 恢复 MySQL

```bash
cat backup_20260101.sql | docker exec -i simplewms-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" simple_wms'
```

#### 备份整个数据卷(更彻底)

```bash
docker run --rm -v simplewms-mysql-data:/data -v $(pwd):/backup alpine tar czf /backup/mysql-data_$(date +%Y%m%d).tar.gz /data
```

---

## 5. 架构与端口

### 5.1 容器拓扑

```
       ┌─────────────────┐
       │  Browser 用户   │
       └────────┬────────┘
                │ http://localhost:9080
                ▼
       ┌─────────────────┐
       │  simplewms-     │  nginx 1.27-alpine
       │  frontend       │  ├─ 静态文件 (index.html / js / css)
       │  9080 → 80      │  └─ /api/* 反代到 backend
       └────────┬────────┘
                │ http://backend:8080 (docker 网络)
                ▼
       ┌─────────────────┐
       │  simplewms-     │  Spring Boot 3.3 + JRE 21
       │  backend        │  ├─ REST API (/api/*)
       │  127.0.0.1:9090 │  ├─ 静态文件 (内置,绕过 nginx 也能用)
       │   → 8080        │  └─ (数据库为外部 MySQL,无内置控制台)
       └────────┬────────┘
                │ jdbc:mysql://mysql:3306/simple_wms
                ▼
       ┌─────────────────┐
       │  simplewms-     │  MySQL 8.0
       │  mysql          │  数据卷:mysql-data
       │  (无 host 映射) │  首次启动自动跑 sql/mysql-schema.sql
       └─────────────────┘
```

### 5.2 端口分配

| 端口 | 绑定 | 服务 | 说明 |
|------|------|------|------|
| `9080` | `0.0.0.0` | frontend (nginx) | **用户唯一推荐入口** |
| `127.0.0.1:9090` | loopback | backend (Spring) | 本机调试 API 用,局域网/外网**不可达** |
| `3306` | (无 host 映射) | mysql | 容器间通信用,host 上**不暴露** |

> 💡 **为什么 8080 → 9090、80 → 9080?**
> Windows 上 Hyper-V 默认占用 `80`、`8033-8132` 等端口,直接绑会被拒。换到 9090/9080 避坑。其他系统无此限制时可改回。

### 5.3 关键路径

| 路径 | 内容 |
|------|------|
| `docker-compose.yml` | 编排三容器 |
| `docker/Dockerfile.backend` | 后端多阶段构建 |
| `docker/Dockerfile.frontend` | 前端 nginx 镜像 |
| `docker/nginx.conf` | nginx 反代配置 |
| `sql/mysql-schema.sql` | MySQL 建库建表 + GRANT |
| `src/main/resources/static/` | 前端源码(被两个镜像分别 COPY) |
| `.env` | 运行时配置(**不入 git**) |
| `.env.example` | `.env` 模板(入 git) |

---

## 6. 常见问题

### 6.1 启动报 `port 8080 already in use`

本机 8080 已被占用。改 `.env` 加一个 `BACKEND_PORT` 变量,然后同步改 `docker-compose.yml`(参考 `127.0.0.1:9090:8080` 改法)。**当前 compose 不支持直接 env 改端口,需要手改 compose 文件。**

### 6.2 MySQL 容器一直 restarting

```bash
docker logs simplewms-mysql
```

常见原因:
- `MYSQL_ROOT_PASSWORD` 含有 `$` 等特殊字符但没用引号 → 改用单引号包裹
- 数据卷冲突(老的 wms 数据和新的不兼容) → `docker compose down -v` 重置

### 6.3 Backend 报 `Access denied for user 'wms'`

`MYSQL_PASSWORD` 在 .env 改了,但 mysql-data volume 还在用旧密码。两种解决:
- **丢数据**:`docker compose down -v && docker compose --env-file .env up -d --build`
- **保数据**:进 mysql 容器手动同步密码

### 6.4 改了前端代码但浏览器看到旧的

nginx 容器内嵌一份 static,改前端后必须重打:

```bash
docker compose --env-file .env build frontend
docker compose --env-file .env up -d --force-recreate frontend
```

nginx.conf 里已经给所有静态资源设了 `Cache-Control: no-store`,正常发版不会缓存;但**容器镜像层**是另一回事,文件层没变浏览器就还是旧。

### 6.5 backend healthy 但接口 401/500

`healthy` 只表示 HTTP 层能响应(返回 401 = 鉴权拦截,**应用层正常**)。要确认业务是否正常:

```bash
# 登录测试
curl -X POST http://localhost:9080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"你的ADMIN_PASSWORD"}'
```

### 6.6 8088/9090 都能打开 web,正常吗?

正常。Spring Boot 内嵌 Tomcat 会自动 serve `static/` 下的文件:
- `http://localhost:9080/` ← 走 nginx,推荐
- `http://127.0.0.1:9090/` ← 直连 backend 容器,调试用(本机专属,局域网不可达)

两者看到完全一样的页面,因为前端代码就是同一份。

### 6.7 `docker compose` 报 `JWT_SECRET must be set in .env (32+ bytes)`

`.env` 里 `JWT_SECRET=` 留空了。生成一个:

```bash
openssl rand -base64 48
```

把输出贴到 `.env` 的 `JWT_SECRET=...`。

---

## 7. 升级与回滚

### 7.1 升级步骤

```bash
# 1. 拉最新代码
git pull

# 2. 看 compose / Dockerfile 有没有 breaking 改动
git log --oneline -10
diff <(git show HEAD~1:docker-compose.yml) <(git show HEAD:docker-compose.yml)

# 3. 重打 + 重启
docker compose --env-file .env up -d --build

# 4. 验证
docker compose --env-file .env ps
curl http://localhost:9080/api/auth/me    # 应返 401(无 token)
```

### 7.2 回滚

**回滚代码 + 镜像**:

```bash
git checkout <上一个稳定版本>
docker compose --env-file .env up -d --build
```

**只回滚代码,保留当前数据**(推荐,数据无破坏风险):

```bash
git checkout <上一个稳定版本>
docker compose --env-file .env build      # 重打镜像
docker compose --env-file .env up -d      # 容器用新镜像,数据卷不动
```

**数据库不兼容时才需要回滚数据**(极少见):

```bash
# 备份当前数据
docker exec simplewms-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases' > before_rollback.sql
# 用备份恢复(假设你有老版本 .sql)
```

---

## 8. 生产环境加固

> 本节是 checklist,不是强制。建议至少做 1-4 项。

### 8.1 密码策略

- [ ] 把 `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` / `ADMIN_PASSWORD` 全部改成 16+ 位强密码
- [ ] `JWT_SECRET` 用 `openssl rand -base64 64` 生成 64 字节
- [ ] `.env` 文件设权限 `chmod 600`,并备份到密码管理器

### 8.2 网络隔离

- [ ] 改 backend 端口为非默认,如 `127.0.0.1:9443:8080`
- [ ] 用 reverse proxy(Caddy / Traefik / Nginx)接管 443 + TLS
- [ ] MySQL 不暴露 host,只允许 backend 容器访问(已经是这样)

### 8.3 数据备份

- [ ] 每日自动 `mysqldump` 到远程存储(OSS / S3)
- [ ] 定期演练恢复(`mysql < backup.sql` 验证数据完整性)
- [ ] 备份保留 30 天滚动

### 8.4 监控

- [ ] 加 `cAdvisor` + Prometheus 监控容器资源
- [ ] backend healthcheck 已配(/api/auth/me 返 401 = 应用存活)
- [ ] MySQL healthcheck 已配(mysqladmin ping)
- [ ] frontend healthcheck 已配(`/` 返 200)

### 8.5 日志

- [ ] 接 ELK / Loki 把容器日志集中
- [ ] backend 日志用 JSON 格式方便解析(目前是 Spring 默认,需要改造)
- [ ] mysql 慢查询日志开启

### 8.6 TLS / HTTPS

本 compose 不直接配 TLS(避免重复造轮子),推荐做法:

```
Internet
   ↓ 443
[Caddy / Traefik / Nginx]   ← 负责 TLS 终止 + 自动续签证书
   ↓ 80 (内网)
simplewms-frontend
```

示例 Caddyfile:

```caddy
wms.your-domain.com {
    reverse_proxy localhost:9080
}
```

### 8.7 资源限制(防 OOM)

可以在 compose 里给每个 service 加 `deploy.resources.limits`:

```yaml
services:
  backend:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 1G
  mysql:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 1G
```

> ⚠️ `deploy.resources` 在 `docker compose` 单机模式下需要 `compose.deploy.resources` 而不是 `services.X.deploy`,见 [Docker 官方文档](https://docs.docker.com/compose/compose-file/deploy/)。如果用 Swarm 模式则直接生效。

---

## 附:30 秒 sanity check

部署完成后,跑一遍这个检查清单确保全绿:

```bash
# 1. 三个容器都 healthy
docker compose --env-file .env ps

# 2. 前端页面可达
curl -fsS -o /dev/null -w "frontend: %{http_code}\n" http://localhost:9080/

# 3. nginx 反代能通
curl -fsS -o /dev/null -w "api via nginx: %{http_code}\n" http://localhost:9080/api/auth/me

# 4. backend 直连也通
curl -fsS -o /dev/null -w "api direct: %{http_code}\n" http://127.0.0.1:9090/api/auth/me

# 5. mysql 业务用户能登录
docker exec simplewms-mysql mysql -uwms -p"$MYSQL_PASSWORD" simple_wms -e "SELECT 1" 2>&1 | tail -1
```

期望输出:

```
frontend: 200
api via nginx: 401
api direct: 401
1
```

**全部对应 = 部署成功。**

---

## 相关文档

- [PRD.md](PRD.md) - 产品需求
- [api-reference.md](api-reference.md) - REST API 接口
- [database-schema.md](database-schema.md) - 数据库表结构
- [pda-design.md](pda-design.md) - PDA 扫码端设计
- [`.env.example`](../.env.example) - 环境变量模板

---

**最后更新**:基于 `simplewms-backend:1.0.0` + `simplewms-frontend:1.0.0` + `mysql:8.0` 镜像。改 compose / Dockerfile 后请同步更新本文档。
