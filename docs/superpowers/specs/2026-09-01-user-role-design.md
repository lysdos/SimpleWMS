# SimpleWMS — 用户与角色系统 Spec

- **日期:** 2026-09-01
- **主题:** 增加用户登录、角色权限(管理员 / 操作员)
- **状态:** 已批准,等待实施

## 1. 背景

SimpleWMS 当前无任何鉴权——任何访问者都能对商品 / 出入库 / 流水做增删改查。`inventory_log.operator` 是前端手动填的字符串,不可信。

本特性引入完整登录与角色系统,区分**管理员(全权)**与**操作员(只可出入库)**,保证仓库数据只能由被授权的人员修改。

## 2. 目标

- 用户需登录后才能调用任何业务接口,无 token 一律 401
- 管理员拥有所有功能,包括商品 CRUD 与用户管理
- 操作员只能做入库 / 出库,商品与流水仅可读
- 首位管理员通过环境变量 bootstrap;后续用户由管理员管理
- 登录与权限失效时给前端清晰反馈,引导重登

## 3. 非目标

- 不引入 Spring Security / OAuth / SAML 等第三方鉴权框架
- 不实现 JWT refresh token(单一 24h access token,过期重登)
- 不做 CSRF 防护(纯 JSON API + Bearer token,Cookie 不自动附带)
- 不开放注册 / 找回密码流(由管理员后台创建)
- 不做操作审计日志(`inventory_log.operator` 是唯一的痕迹)
- 不支持多因素认证
- 不细分角色:仅 ADMIN 与 OPERATOR 两种
- 不存用户头像 / 个人资料

## 4. 数据模型

新增一张表 `app_user`(避开 SQL 保留字):

```sql
CREATE TABLE IF NOT EXISTS app_user (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(16)  NOT NULL,           -- 'ADMIN' | 'OPERATOR'
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_pw  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                              ON UPDATE CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_app_user_username ON app_user(username);
```

字段含义:
- `password_hash`:BCrypt 哈希(强度 10)
- `role`:枚举字符串,H2 / MySQL 都不需要枚举类型
- `enabled`:账号是否启用,禁用后保留流水痕迹
- `must_change_pw`:bootstrap 管理员首登后强制改密;之后任何账号"被改密"也置 true

## 5. 架构

```
┌─────────────────────────────────────────────────────────────┐
│ 浏览器                                                       │
│  /login          /change-password         #/xxx(SPA)         │
│     ↓                ↓                       ↓              │
│  fetch POST /api/auth/login → token:localStorage             │
│     ↓                ↓                       ↓              │
│  Authorization: Bearer <token>                                │
└─────────────┬───────────────────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────────────────┐
│ nginx(:80) → backend(:8080 Spring Boot)                      │
│                                                              │
│  JwtAuthFilter(在所有 /api/* 上)                              │
│     ├─ 无 token → 401                                       │
│     ├─ 解析失败 / 过期 → 401                                 │
│     ├─ 解析成功 → 注入 req attrs (userId/username/role)      │
│     └─ RequireRole 拦截 → 不符 → 403                          │
│                                                              │
│  Controllers:                                                │
│    AuthController  /api/auth/login|logout|me                 │
│    UserController  /api/users(ADMIN only)                   │
│    Inbound/OutboundController  → 取 session user 写 operator │
│    Dashboard/Product/Ledger     → 鉴权后放行                 │
└─────────────┬───────────────────────────────────────────────┘
              │
              ▼
       app_user + product + inventory_log(H2 / MySQL)
```

## 6. 后端组件

| 文件 | 职责 |
|------|------|
| `model/AppUser.java` | 实体(id, username, role, enabled, mustChangePw, createdAt, updatedAt) |
| `dao/UserDao.java` | findByUsername / findById / insert / updatePassword / setEnabled / list / count |
| `service/UserService.java` | 业务:bootstrap / 创建 / 改密 / 启用禁用 / 列表;所有密码走 BCrypt |
| `service/JwtService.java` | 生成 / 解析 JWT(HS256,subject=userId,claims={uname,role});常量 TTL 与 secret |
| `service/PasswordService.java` | BCrypt hash / verify |
| `controller/AuthController.java` | `POST /api/auth/login` / `POST /api/auth/logout` / `GET /api/auth/me` |
| `controller/UserController.java` | `GET /api/users` / `POST /api/users` / `POST /api/users/{id}/password` / `POST /api/users/{id}/enabled`(注意 `enabled` 切换) |
| `filter/JwtAuthFilter.java` | 读 `Authorization: Bearer ...`,注入 request attrs,401 处理 |
| `annotation/RequireRole.java` | `@RequireRole("ADMIN")` 方法注解;HandlerMethodArgumentResolver / 拦截器解析 |
| `web/RoleInterceptor.java` | 检查 `@RequireRole` 与当前角色,不匹配 403 |
| `web/WebConfig.java` | 注册拦截器,排除 `/api/auth/login` 与 `/api/auth/me`(可选) |
| `db/DataSeeder.java`(改) | 启动后若 app_user 表为空,插入首账号,设 mustChangePw=true |
| `resources/schema.sql`(改) | 加 app_user 表 + 索引 |

### 依赖新增

`io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` 版本 0.12.x。

## 7. 鉴权流(JWT)

**登录**

```
POST /api/auth/login
  body: {username, password}
  → 200 {token, role, mustChangePw, username}
  → 401 {"code":401,"message":"用户名或密码错误"}
  → 403 {"code":403,"message":"账号已禁用"}
```

**调用 API**

```
GET /api/dashboard/stats
  headers: Authorization: Bearer <token>
  → 200 正常 JSON
  → 401 token 缺失 / 无效 / 过期
  → 403 已鉴权但角色不足
```

**登出**

```
POST /api/auth/logout
  → 200 OK
  (无状态;前端清 localStorage;若需即时失效可加黑名单,本项目不做)
```

**Me**

```
GET /api/auth/me
  → 200 {username, role, mustChangePw}
  → 401 未登录
```

## 8. 权限矩阵

| 接口 | ADMIN | OPERATOR | 未登录 |
|------|:---:|:---:|:---:|
| GET `/api/dashboard/stats` | ✅ | ✅ | 401 |
| GET `/api/products` 系列 | ✅ | ✅ | 401 |
| POST / PUT / DELETE `/api/products/**` | ✅ | 403 | 401 |
| POST `/api/inbound` | ✅ | ✅ | 401 |
| POST `/api/outbound` | ✅ | ✅ | 401 |
| GET `/api/ledger` | ✅ | ✅ | 401 |
| GET / POST `/api/users/**` | ✅ | 403 | 401 |
| POST `/api/auth/**` | ✅(任意已登录) | ✅ | 公开(除 `me` 需登录) |

> 操作员菜单看不到"商品管理 / 库存流水 / 用户管理 / 仪表盘",但 API 层对读接口仍开放(200)。这样后端是单一鉴权边界,不会因前端禁用 JS 而绕过。

## 9. 前端组件

| 文件 | 职责 |
|------|------|
| `static/login.html` | 独立登录页(用户名 + 密码 + 登录按钮 + 错误提示) |
| `static/change-password.html` | 强制改密页(老密码 + 新密码 + 确认 + 保存) |
| `static/js/auth.js` | `getToken()` / `setToken()` / `clearToken()` / `getCurrentUser()` |
| `static/js/api.js`(改) | 在每个 fetch 自动塞 `Authorization`;遇 401 清 token + 跳 `/login` |
| `static/js/app.js`(改) | 启动时 `GET /api/auth/me`,未授权跳 `/login`;按 role 过滤导航 |
| `static/js/views.js`(改) | 顶部菜单按 role 隐藏;`views.userAdmin()` 仅 ADMIN 可见 |
| `static/js/views/userAdmin.js`(新) | 用户管理视图(列表 + 模态表单) |
| `static/index.html`(改) | 头部"操作员"区改为 `<span id="me-name">…</span> <button id="logout-btn">登出</button>` |
| `static/css/app.css`(改) | `.login-page` / `.change-password-page` 居中卡片样式 |
| 删除 | 各视图里的 operator 输入框 |

### 路由策略

- `/login` → `login.html`(独立,不在 SPA)
- `/change-password` → `change-password.html`(独立)
- 其他 hash `#/xxx` → SPA

### 用户管理 UI(ADMIN 专属)

```
[+ 新建用户] 按钮 → 弹窗表单(username, role=ADMIN|OPERATOR, 初始密码)
表格列:ID | username | role | enabled | 创建时间 | 操作
操作: 重置密码(弹窗) | 启用/禁用切换
无"删除"按钮(满足"禁用而非删除")
```

## 10. 配置 / 环境变量

`docker-compose.yml` backend 段加:

```yaml
environment:
  ADMIN_USERNAME: admin
  ADMIN_PASSWORD: change-me-now     # 仅 bootstrap 时使用,mustChangePw=true
  JWT_SECRET: <≥32 chars>            # HS256 密钥
  JWT_TTL_HOURS: 24
```

`DataSeeder` 检测 `app_user` 为空 → 用上述 env 插入首账号,`mustChangePw=true`,`enabled=true`。

`application.yml` / `application-mysql.yml` 不必硬编码——`UserService` 走 `@Value("${...}")` 注入。

## 11. 验收

| 场景 | 期望 |
|------|------|
| 未登录访问 `/` | 重定向 `/login` |
| 错误密码登录 | 401 + 前端提示 |
| 禁用账号登录 | 403 + 前端提示 |
| 正确密码 + `mustChangePw=true` | 跳 `/change-password`,改后才进 `#/dashboard` |
| ADMIN 登录 | 仪表盘 / 商品 / 入库 / 出库 / 流水 / **用户管理** 菜单全显示 |
| OPERATOR 登录 | 仅入库 / 出库菜单 |
| OPERATOR 用 API 改商品 | 403 |
| OPERATOR 出入库 | 成功,流水 `operator` = 当前登录 username |
| OPERATOR 伪造 operator 字段 | 后端忽略,实际写入的是 session username |
| 重建容器 | app_user 表与账号保留(因 MySQL 卷持久化) |

## 12. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| 首位管理员密码忘了 | 改 `docker-compose.yml` + `docker compose restart backend`(会重新跑 bootstrap) | 先 `mysql -e "DELETE FROM app_user WHERE username='admin'"` 再重启 |
| JWT secret 改了 | 旧 token 全部失效 | 用户重新登录 |
| BCrypt 不兼容 H2 | H2 模式首次启动无用户 | 同上回退 |
| 拦截器误伤 | Controller 漏标 `@RequireRole` | 单独补注解;默认所有 `/api/*` 走 JwtAuthFilter,仅 `AuthController` 公开 |

## 13. 范围

本特性聚焦:**登录 + 角色 + 用户管理 + 鉴权拦截**。一个 plan 可独立完成。

## 14. 后续(不在本次范围)

- JWT refresh token + 滑动续期
- 操作审计日志(谁在何时改了什么)
- 用户自助改密(非强制场景)
- 角色细分(WAREHOUSE_KEEPER / VIEWER 等)
- H2 兼容 BCrypt 验证(若 H2 默认 BCrypt 算法与 jjwt BCrypt 实现有版本不兼容需额外处理)