# SimpleWMS — Docker 部署 Spec

- **日期:** 2026-09-01
- **主题:** 本地项目部署到容器内
- **状态:** 已批准,等待实施

## 1. 背景

SimpleWMS 是 Spring Boot 3.3.5 + Java 21 + H2/MySQL 的仓库管理系统,代码与 Docker 配置均已就位(`docker-compose.yml`、`docker/Dockerfile.backend`、`docker/Dockerfile.frontend`、`docker/nginx.conf`、`sql/mysql-schema.sql`、`docker-start.bat`/`docker-stop.bat`)。当前主机已安装 Docker 29.7.2 + Compose v5.4.0,但 SimpleWMS 尚未以容器方式运行。

**唯一阻碍:** 宿主机 80 端口已被占用(`0.0.0.0:80 LISTENING PID 6484`),与前端容器默认的 `80:80` 端口映射冲突。

## 2. 目标

通过 `docker-start.bat` 启停脚本,以 docker compose 方式在本地一键拉起 SimpleWMS 全栈(mysql + backend + frontend),浏览器可访问,API 可直连。

## 3. 非目标

- 不改动源码、Dockerfile、nginx.conf、mysql-schema.sql
- 不升级镜像版本(backend 保持 `1.0.0`,frontend 保持 `1.0.0`)
- 不推送镜像到远端 registry
- 不改 README(用户可自行调整)
- 不加 HTTPS / 反向代理层

## 4. 架构(沿用现有,不修改)

```
[浏览器]
   ↓
[simplewms-frontend :8081(nginx 1.27-alpine)]
   ├─ 静态文件 / /css / /js(/usr/share/nginx/html)
   └─ /api/* → http://backend:8080(Spring Boot)
                  ↓
              [simplewms-mysql :3306(mysql:8.0)]
                  ↓
              卷 simplewms-mysql-data
```

所有容器在同一自定义桥接网络 `simplewms-net`,通过服务名互访。

## 5. 端口映射

| 服务 | 容器端口 | 宿主机端口 | 用途 |
|------|----------|------------|------|
| frontend | 80 | **8081** | 用户访问入口 `http://localhost:8081/` |
| backend | 8080 | 8080 | API 直连 `http://localhost:8080/api/...` |
| mysql | 3306 | 3306 | 数据库客户端调试(Navicat/DBeaver) |

**改动点:** frontend 端口从 `80:80` 改为 `8081:80`,仅修改 `docker-compose.yml` 一行。

## 6. 执行步骤

1. 编辑 `docker-compose.yml`,把 frontend 服务的 ports 改为 `8081:80`。
2. 执行 `docker-start.bat`(其内部调用 `docker compose up -d --build`)。
3. 等待 MySQL healthcheck 通过(默认 30s start_period + 15 retries)。
4. backend 容器启动,SPRING_PROFILES_ACTIVE=mysql 自动生效,SPRING_DATASOURCE_URL 指向 `mysql:3306`。
5. frontend 容器启动,nginx 监听 80(容器内),反代到 `backend:8080`。

## 7. 验证

| 检查 | 命令 | 期望 |
|------|------|------|
| 容器全 Up | `docker compose ps` | 3 个服务都 `Up`(mysql healthy) |
| 经 nginx 调 API | `curl http://localhost:8081/api/dashboard/stats` | 返回 JSON(默认演示数据) |
| 浏览器入口 | `curl -I http://localhost:8081/` | HTTP 200 |
| 直连 backend | `curl http://localhost:8080/api/dashboard/stats` | 与 nginx 路径返回一致 JSON |
| MySQL 可达 | `docker compose exec mysql mysql -uwms -pwmspass -e "USE simple_wms; SHOW TABLES;"` | 列出 product、inventory_log |

## 8. 风险与回退

| 风险 | 触发条件 | 回退动作 |
|------|----------|----------|
| MySQL healthcheck 超时 | 首次构建慢或卷损坏 | `docker compose down -v && docker-start.bat` |
| 8081 也被占用 | `netstat -ano | grep 8081` 命中 LISTENING | 改用 8082/8083,重跑 |
| 构建镜像失败 | Dockerfile / 网络问题 | `docker compose logs backend` 定位,改动 Dockerfile 后 `docker compose up -d --build backend` |
| Spring Boot 启动失败 | 配置 / 依赖问题 | `docker compose logs backend` 查看,常见为 datasource 连不上 |

## 9. 成功标准

- 用户在浏览器打开 `http://localhost:8081/` 看到 SimpleWMS 仪表盘
- 创建/查询商品、登记出入库等 REST 接口经 nginx 反代正常返回
- 数据写入 MySQL,容器重启后数据不丢(卷持久化生效)
- `docker-stop.bat` 一键停服,`docker-start.bat` 一键重启

## 10. 后续(不在本次范围)

- 配置 host 文件或域名访问
- 加 HTTPS / Let's Encrypt
- 改用 docker swarm / k8s 编排
- 把镜像推到内网 registry