# API 接口规范 — SimpleWMS

> 基线:`main` 分支当前 HEAD
> 后端地址:`http://localhost:8080`(直连)/ `http://localhost:9080/api/*`(经 nginx)
> 鉴权:除显式标注外,所有端点需 `Authorization: Bearer <jwt>`,`Content-Type: application/json`

## 1. 通用约定

### 1.1 响应壳

所有响应统一为 `ApiResponse<T>`:
```json
{ "code": 0, "message": "ok", "data": <T> }
```

- `code = 0` 成功
- `code != 0` 失败,语义见各端点
- `message` 人类可读消息(可能含中文)。**注意字段名是 `message`,不是 `msg`** —— 客户端 DTO 必须按 `message` 绑定,否则错误消息会解析成 null。

### 1.2 HTTP 状态码 vs 业务码

| HTTP | 触发 | 业务 code 范围 |
|---|---|---|
| 200 | 成功 | 0 |
| 400 | 参数错误:`IllegalArgumentException` 普通错 / 路径参数类型不匹配 / 缺必填查询参数 | 400 |
| 401 | JWT 缺失 / 无效 / 过期(`JwtAuthFilter` 拦截) | 401 |
| 403 | 缺角色(`@RequireRole`) | 403 |
| 404 | 资源不存在:**路径上的**资源查不到 → 抛 `NotFoundException`;另有少数 service 走 `IllegalArgumentException` 文本前缀兜底(`码不存在` / `出库单不存在, id=` / `入库单不存在, id=` / `用户不存在`)。划分口径见 §1.2.2 | 404 |
| 405 | 路径匹配但 HTTP 方法不支持 | 405 |
| 406 | 客户端 `Accept` 无法满足(Spring 自身处理,**响应体为空**) | — |
| 409 | 业务冲突(库存不足 / 状态不对) | 409 |
| 415 | 请求 `Content-Type` 不支持(应为 `application/json`) | 415 |
| 422 | 不可处理实体(扫码校验失败) | 422 |
| 500 | 服务器异常 | 500 |

`GlobalExceptionHandler.badArg` 根据抛出位置文本前缀在 400 / 404 之间分流(参见 `database-schema.md` 与 `prd-input-backend.md` B6)。前缀是**精确匹配的固定清单**(见上表 404 行),不是"包含'不存在'即为 404" —— 例如 `SKU 不存在` 会落到 400。

#### 1.2.1 404 一定是真 404(2026-09-18 起)

**HTTP 状态码即 404,body 里 `code` 也是 404**,两处一致。

早先有 8 个 GET 端点(products / locations / suppliers / stock-checks / inventory items 的 `{id}` 与 `by-code`、`by-epc` 等)用 `return ApiResponse.fail(404, ...)` 表达"不存在"。Spring 对普通 `@RestController` 返回值一律用 HTTP **200**,于是这些响应的状态码是 200、只有 body 里 `code=404` —— 只看状态码的客户端(web `res.ok` / PDA `isSuccessful`)会当成成功、拿到 `null` 静默吞掉,用户看不到任何提示。

现已统一为抛 `NotFoundException` → `GlobalExceptionHandler` → 真 404。**新增接口请照此办理,不要再直接 `return ApiResponse.fail(404, ...)`。**

客户端仍保留两层防御(不依赖状态码单点判断):web 端 `api.js._handle` 在 `res.ok` 之后还有 `if (data.code) throw ...`;PDA 端 `ApiClient` 走 `ar.code != 0` 分支,且非 2xx 时也会尝试从 body 取 `message`。

#### 1.2.2 404 与 400 的划分口径

判定标准是"**这个不存在的东西,是不是客户端用来定位资源的标识符**",而不是"它出现在 URL 还是 body 里":

| 场景 | 状态码 | 说明 |
|---|---|---|
| 用标识符定位一个资源,而它不存在 | **404** | URL 里的 id(`/products/{id}`、`/stock-checks/{id}/commit`、`DELETE /locations/{id}`);**扫码得到的 24 位 code**(虽在 body 里,但语义就是"按码找单元") |
| 请求内容不满足业务约束 | **400** | 批量明细里的 `productId` 查不到、参数缺失/格式错、状态机不允许(`IllegalStateException` 才是 409) |

同一个资源在所有端点上必须给出一致的状态码。

现状(2026-09-18 已统一):**用标识符定位的那一类全部改为抛 `NotFoundException`** —— products / locations / suppliers / stock-checks / inventory-items 的 `{id}` 与 `by-code`/`by-epc`,盘点单的 `commit`/`cancel`/`scan`/`items`/`export.csv`,出入库单的 `items` 明细行等。批量明细里的 `productId` 类仍是 `IllegalArgumentException` → 400。

**已知例外**:`OutboundService.commitDraft` 在 FIFO 扣减时若商品已不存在抛 `商品不存在` → 400。这既不是路径资源也不属于请求体输入,而是**数据不一致**(商品被删但库存单元还在),严格说该按 409/500 处理;目前维持 400 未改。

`GlobalExceptionHandler.badArg` 那个按消息文本前缀分流的兜底仍然存在(供 `用户不存在`、`码不存在`、出入库单不存在等尚未迁移的抛出点使用),但**新代码不要再依赖它** —— 直接用 `NotFoundException`,别再靠消息文案决定状态码。

### 1.3 鉴权

- **登录成功**:后端签发 JWT(默认 24h),返回 `{token, username, role, mustChangePw}`
- **Authorization 头**:`Authorization: Bearer <token>`
- **过期**:`JwtAuthFilter` 抛 `AuthenticationException` → 401
- **PDA 登录后**:token 存 `SessionStore`(SharedPreferences),`ApiClient` 拦截器自动注入

### 1.4 操作员字段

服务端从 JWT 解析 `username` 写入 `request.attribute("username")`,Controller 读 `currentOperator(req)`。缺值时 fallback `"操作员"`(审计可追溯性弱,见 `prd-input-backend.md` M5)。

### 1.5 分页

`page>=1, pageSize∈(0, 200]`,出参固定结构:
```json
{ "list": [...], "total": 123, "page": 1, "pageSize": 20 }
```

### 1.6 时间字段

`created_at` / `updated_at` / `committed_at` / `scrapped_at` / `transferred_at` 均为 TIMESTAMP,ISO 字符串返回。

### 1.7 24 位 inventory_item.code

- 唯一 VARCHAR(24),系统生成(`CodeGenerator`:`yyyyMMddHHmmss` 14 + SKU hash 4 + 随机 6,DB UNIQUE 兜底)
- 入库 commit 时批量生成,贴实物
- 出库 / 盘点扫码读这个码

## 2. 端点列表(按模块)

### 2.1 鉴权 — `/api/auth`

| 方法 | URL | 鉴权 | 请求 | 响应 | 错误码 |
|---|---|---|---|---|---|
| POST | `/login` | 公开 | `{username, password}` | `{token, username, role, mustChangePw}` | 401 用户名密码错 |
| POST | `/logout` | 已登录 | — | `{code:0,message:ok}` | — |
| GET | `/me` | 已登录 | — | `{userId, username, role}` | 401 |
| POST | `/me/change-password` | 已登录 | `{oldPassword, newPassword}` | `{code:0,message:ok}` | 400 旧密码错 |

### 2.2 仪表盘 — `/api/dashboard`

| 方法 | URL | 鉴权 | 参数 | 响应 |
|---|---|---|---|---|
| GET | `/stats` | 已登录 | — | `DashboardStats {productCount, totalQuantity, totalValue, lowStockCount, todayInbound, todayOutbound}` |
| GET | `/inventory-trend?days=30` | 已登录 | days 7-90 | `[{date, cumulative}]` 累计在库单元数 |
| GET | `/outbound-trend?days=30` | 已登录 | days 7-90 | `[{date, quantity}]` 每日出库件数 |

### 2.3 商品 — `/api/products`

| 方法 | URL | 鉴权 | 请求 | 响应 | 错误 |
|---|---|---|---|---|---|
| GET | `/?keyword=` | 已登录 | `keyword` 可选模糊匹配 sku/name/category | `[{id, sku, name, ..., suppliers:[]}]` | — |
| GET | `/{id}` | 已登录 | — | `{...同上 + suppliers:[]}` | 404 |
| POST | `/` | **ADMIN** | `{sku, name, category?, unit?, location?, quantity?, unitPrice?, lowStock?, remark?, suppliers?:[]}` | 创建后 | 400 |
| PUT | `/{id}` | **ADMIN** | 同上 | 更新后 | 404 |
| DELETE | `/{id}` | **ADMIN** | — | `{code:0,message:ok}` | 404 |

`suppliers[]` 元素:`{supplierId, isDefault, unitPrice?, remark?}`。

### 2.4 供应商 — `/api/suppliers`

| 方法 | URL | 鉴权 | 备注 |
|---|---|---|---|
| GET | `/?keyword=` | 已登录 | name 模糊 |
| GET | `/{id}` | 已登录 | |
| POST | `/` | **ADMIN** | |
| PUT | `/{id}` | **ADMIN** | |
| DELETE | `/{id}` | **ADMIN** | |

### 2.5 库位 — `/api/locations`

同上模式,key=code,用于"商品 location 字段"下拉。

### 2.6 用户管理 — `/api/users`(类级 `@RequireRole("ADMIN")`)

| 方法 | URL | 请求 | 备注 |
|---|---|---|---|
| GET | `/?keyword&role&status&beginDate&endDate` | — | 用户列表(参数均可省略) |
| GET | `/{id}` | — | 单个用户;不存在 → `用户不存在` |
| POST | `/` | `{username, password, role}` | 创建 |
| PUT | `/{id}` | `{role}` | 改角色 |
| POST | `/{id}/password` | `{password}` | 管理员重置(用户不存在 → 404) |
| POST | `/{id}/enabled` | `{enabled: bool}` | 启停 |
| DELETE | `/{id}` | — | 不能删自己 / 最后一名 ADMIN |

### 2.7 入库 — `/api/inbound`(DRAFT/COMMITTED/CANCELLED)

**老接口**(兼容保留,直接 COMMITTED):
| 方法 | URL | 请求 | 响应 |
|---|---|---|---|
| POST | `/` | `{productId, quantity, remark, operator?}` | `InventoryLog` |
| POST | `/batch` | `{externalNo?, remark?, items:[{productId, quantity, supplier?, rowRemark?}]}` | `InboundOrder` + items |

**草稿单流(PDA 主流)**:
| 方法 | URL | 请求 | 响应 | 错误 |
|---|---|---|---|---|
| POST | `/orders` | `{externalNo?, remark?}` | `InboundOrder{status:DRAFT, totalQuantity:0, itemCount:0}` | 400 |
| POST | `/orders/{id}/items` | `{productId, quantity, supplier?, rowRemark?}` | `InboundOrder` 刷新(含 items + 每行 codes 已回填) | 400 商品不存在 / 409 单子非 DRAFT |
| POST | `/orders/{id}/commit` | — | `InboundOrder`(status=COMMITTED,items[].codes 是生成的 N 个 24 位码) | 409 单子非 DRAFT / 空单 |
| POST | `/orders/{id}/cancel` | — | `{code:0}` | 409 单子非 DRAFT |

**查询**:
| 方法 | URL | 参数 |
|---|---|---|
| GET | `/orders?beginDate&endDate&keyword&operator&status&page&pageSize` | status 默认 `DRAFT`(PDA 用);status=ALL 看全部 |
| GET | `/orders/{id}` | 含 items + 每行 codes(commit 后有) |
| GET | `/orders/{id}/export.csv` | UTF-8 BOM + 双引号转义,Excel/Numbers 兼容 |

### 2.8 出库 — `/api/outbound`

**老接口**(兼容):
| 方法 | URL | 备注 |
|---|---|---|
| POST | `/` | 单货出库 |
| POST | `/batch` | 批量,直接 COMMITTED |

**草稿单流(PDA 主流)**:
| 方法 | URL | 请求 | 响应 | 错误 |
|---|---|---|---|---|
| POST | `/orders` | `{customer?, externalNo?, remark?}` | `OutboundOrder{status:DRAFT}` | 400 |
| **POST** | **`/orders/{id}/scan`** | **`{code}`** | **`OutboundOrder`** 刷新(items 重建) | **404 码不存在 / 409 码已 OUT/SCRAPPED** |
| POST | `/orders/{id}/commit` | — | `OutboundOrder`(status=COMMITTED,FIFO 扣减) | 409 非 DRAFT / 空单 |
| POST | `/orders/{id}/cancel` | — | `{code:0}` | 409 非 DRAFT |

**查询**:同入库,`status` 默认 DRAFT。

### 2.9 库存单元 — `/api/inventory/items`

| 方法 | URL | 鉴权 | 备注 |
|---|---|---|---|
| GET | `/?sku&location&status&page&pageSize` | 已登录 | 三过滤 + 分页 |
| GET | `/{id}` | 已登录 | |
| GET | `/by-inbound/{orderId}` | 已登录 | 入库单下所有单元 |
| GET | `/by-outbound/{orderId}` | 已登录 | 出库单消耗的所有单元 |
| GET | `/by-code/{code}` | 已登录 | PDA 扫码查询;code→InventoryItem |
| GET | `/by-epc/{epc}` | 已登录 | EPC 别名(同 by-code) |
| GET | `/by-code/{code}/product` | 已登录 | code→InventoryItem→Product(PDA 入库扫码解析) |
| GET | `/{id}/barcode.png` | 已登录 | CODE_128 PNG |
| POST | `/{id}/scrap` | 已登录 | `{remark?}`,只能 IN_STOCK→SCRAPPED |
| POST | `/{id}/transfer` | 已登录 | `{toLocation}`,IN_STOCK→TRANSFERRED,改 location_code |
| GET | `/export.csv?sku&location&status` | 已登录 | 单次硬编码 5000 行,大库需分页 |

### 2.10 盘点 — `/api/stock-checks`

| 方法 | URL | 鉴权 | 请求 | 响应 | 错误 |
|---|---|---|---|---|---|
| GET | `/?beginDate&endDate&status&page&pageSize` | 已登录 | — | `[{id, checkNo, scope, ...}]` | — |
| GET | `/{id}` | 已登录 | — | `StockCheck + items[]` | 404 |
| POST | `/` | **ADMIN** | `{scope:ALL/SKU/LOCATION, keyword?, remark?}` | `StockCheck`(快照所有 in_stock 单元到明细) | 400 scope 错 |
| POST | `/{id}/items/{itemId}/count` | **ADMIN** | `{countedQty, remark?}` | `{code:0}` | 400 / 404 |
| **POST** | **`/{id}/scan`** | **已登录** | **`{code}`** | **`StockCheck`** 刷新(items[].countedQty+=1) | **404 码不在 / 409 状态非 IN_STOCK / 422 码不在本单** |
| POST | `/{id}/commit` | **ADMIN** | — | `StockCheck`(status=COMMITTED,生成 diff 报告) | 409 |
| POST | `/{id}/cancel` | **ADMIN** | — | `{code:0}` | 409 |
| GET | `/{id}/export.csv` | 已登录 | 差异报告(每行 system/counted/diff) | — |

> **权限不对称**(已知):`/scan` 不需 ADMIN,`create/commit/cancel` 需要 ADMIN。PDA 操作员角色(OPERATOR 未来支持)可扫码但不能建/提交单。

## 3. 错误响应示例

```bash
# 码不存在
$ curl -X POST http://localhost:9090/api/outbound/orders/1/scan -H "Authorization: Bearer ..." -d '{"code":"INVALID"}'
HTTP/1.1 404
{"code":404,"message":"码不存在: INVALID","data":null}

# 码已出库
$ curl -X POST ... -d '{"code":"ALREADY_OUT"}'
HTTP/1.1 409
{"code":409,"message":"该码已出库","data":null}

# 盘点单码不在本单
$ curl -X POST http://localhost:9090/api/stock-checks/5/scan -H "Authorization: Bearer ..." -d '{"code":"OTHER_CHECK_CODE"}'
HTTP/1.1 422
{"code":422,"message":"该码不在本盘点单中","data":null}
```

## 4. 24 位 inventory_item.code 校验端点

PDA 扫码链路涉及的端点(共 4 个):

| 端点 | 入参 | 作用 | 返回 |
|---|---|---|---|
| `GET /api/inventory/items/by-code/{code}` | code | 实物码查单元 | `InventoryItem` 或 404 |
| `GET /api/inventory/items/by-epc/{epc}` | epc | EPC 别名 | 同上 |
| `GET /api/inventory/items/by-code/{code}/product` | code | 实物码反查商品(PDA 入库扫码辅助) | `Product` 或 404 |
| `POST /api/outbound/orders/{id}/scan` | code | 出库单扫码加入 | 200 / 404 / 409 |
| `POST /api/stock-checks/{id}/scan` | code | 盘点单扫码累加 countedQty | 200 / 404 / 409 / 422 |

## 5. 鉴权矩阵

| 端点 | 公开 | 已登录 | ADMIN |
|---|---|---|---|
| 登录 / 注册 | ✓ | | |
| 查询类(stats / products / orders) | | ✓ | |
| 写入单据(批量) | | ✓ | |
| DRAFT 单据的 scan / commit | | ✓ | |
| 创建/删除商品/供应商/库位 | | | ✓ |
| 创建/提交/取消盘点单 | | | ✓ |
| 用户管理 | | | ✓ |
| scrap / transfer(实物) | | ✓ | |
| inventory_item.read 类 | | ✓ | |

## 6. 性能与限流

- 当前无显式限流(测试用)
- 库存 CSV 导出硬编码 5000 行(见 `prd-input-backend.md` M3)
- FIFO 出库:`consumeForOutbound` 多次 SELECT inventory_item(按 `id ASC` ORDER BY limit),N=100 时约几毫秒
- 盘点扫描:N=1000 时 sub-100ms;N=10000 可能秒级(见 `prd-input-backend.md` B3)

## 7. 接口约定补充

- 列表查询都支持 `page` + `pageSize`,默认 pageSize=20
- 时间查询:`beginDate` `endDate` 是 `yyyy-MM-dd` 字符串,服务端补 00:00:00 / 23:59:59
- 关键词查询:`keyword` 默认 LIKE 模糊匹配多个字段
- 大写/小写:SKU 精确匹配,name/category 部分模糊

## 8. 后续待办

- 出库扫码加 SKU 校验(避免错码入错单)— 当前仅校验码 + IN_STOCK
- 盘点端点权限统一(OPERATOR 角色下应允许 create + commit)
- 限流中间件(防 PDA 端 bug 触发批量请求)
- 库存 item 批量报废 / 批量调拨
- 盘点 commit 支持"扫码模式"自动算 diff(避免要求所有明细填完)
