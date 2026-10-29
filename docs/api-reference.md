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
| 404 | 资源不存在:抛 `NotFoundException`(**唯一机制**,状态码与提示语文案无关)。划分口径见 §1.2.2 | 404 |
| 405 | 路径匹配但 HTTP 方法不支持 | 405 |
| 406 | 客户端 `Accept` 无法满足(Spring 自身处理,**响应体为空**) | — |
| 409 | 业务冲突(库存不足 / 状态不对 / 并发下已被改动)。数据库约束冲突(`DataIntegrityViolationException`)也走这里,但**只回通用文案**,驱动原文(表名/列名/约束名)只记服务端日志 | 409 |
| 413 | 上传文件过大(`MaxUploadSizeExceededException`,单文件 10MB;nginx 侧同一文案) | 413 |
| 415 | 请求 `Content-Type` 不支持(应为 `application/json`) | 415 |
| 422 | 不可处理实体(扫码校验失败) | 422 |
| 429 | 请求过于频繁(登录失败次数超限,`TooManyRequestsException`) | 429 |
| 500 | 服务器异常 | 500 |

`GlobalExceptionHandler.badArg` 现在**只负责 400**(`IllegalArgumentException` = 请求参数/内容有问题)。404 一律由 `NotFoundException` 给出,不靠消息文案判断。

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

**已退役(2026-09-18)**:`badArg` 曾经按消息文本前缀(`码不存在` / `出库单不存在, id=` / `入库单不存在, id=` / `用户不存在`)把部分 `IllegalArgumentException` 分流成 404 —— 那等于让**状态码取决于提示语文案**(改一句话就可能悄悄改掉响应码,而不在清单里的"xx不存在"会落到 400)。现全部改为抛 `NotFoundException`。

唯一例外:`UserService.changeOwnPassword` 的存在性校验**保持** `IllegalArgumentException` —— 它被 `AuthController` 专门 catch 成 400("改自己密码"属输入校验)。别去"顺手统一"它。

### 1.3 鉴权

- **登录成功**:后端签发 JWT(默认 24h),返回 `{token, username, role, mustChangePw}`
- **Authorization 头**:`Authorization: Bearer <token>`
- **过期**:`JwtAuthFilter` 抛 `AuthenticationException` → 401
- **PDA 登录后**:token 存 `SessionStore`(加密的 EncryptedSharedPreferences),`ApiClient` 拦截器自动注入
- **登录限流(2026-09-28)**:同一 (**用户名 + IP**) 连错 5 次、或同一 **IP** 累计错 20 次 → 该维度锁 5 分钟并返回 **429**。
  两把尺子是刻意的:只按用户名计数,谁都能拿别人的工号把别人锁在门外;只按 IP 计数,同一个 nginx 出口下的同事会互相连坐。
  成功后计数清零;计数在**进程内存**里,多实例部署需换成共享存储才有意义。
  客户端 IP 取 `X-Real-IP` —— nginx 用 `proxy_set_header X-Real-IP $remote_addr` **覆写**该头,伪造无效。
- **豁免(2026-09-29)**:UHF-Rader 终端**整机**不受登录失败限流。它的登录页与其出库扫码的自动登录都由**同一个容器**代发,后端分不清两者,也无法按来源 IP 区分(docker 网络里容器 IP 重建后会漂)——而该终端被锁 5 分钟 = 现场整条扫码线停摆。
  做法:`.env` 的 `WMS_LOGIN_NO_THROTTLE_TOKEN` 由 backend 与 uhf-rader 两个容器读**同一个值**,UHF 代调 `/api/auth/login` 时带 `X-WMS-No-Throttle: <密钥>`;后端命中则**既不看锁定、也不计失败**(只豁免一半的话,终端页面的误输仍会把桶记满,回头把自动登录顶掉)。
  密钥只在容器进程内,不下发浏览器;**未配置(空白)时豁免整体关闭**(fail-closed,不会"配漏了反而放行")。WMS 网页登录页与 PDA 不带该头,照旧受限流保护。比较走常量时间,不给出逐字节试探的余地。
- **密码下限 8 位(2026-09-28)**:新建用户 / 管理员重置 / 自行改密都按 8 位卡(此前是 6 位,与首个 admin 账号的 8 位口径不一)。
  存量 6~7 位口令**仍可登录**,这次收紧只作用于新建与改密,不会把现有账号锁在外面。

#### 1.3.1 免登录路径:只有条码图一个(2026-09-28 收紧)

除 `POST /api/auth/login` 外,**唯一**免登录的资源是 `GET /api/inventory/items/{id}/barcode.png` ——
前端用 `<img src>` 渲染它,而 `<img>` 带不了 Authorization 头。

**所有 `export.csv`(出入库单 / 盘点单 / 库存)一律要求登录。**

> 此前 `JwtAuthFilter` 用「公开前缀 + `/export.csv` 或 `/barcode.png` 后缀」匹配,而
> `/api/inventory/items/` 也在前缀列表里 —— 于是 `GET /api/inventory/items/export.csv` 被判成公开路径:
> **任何人无需 token 就能下载全量库存台账**(实测 HTTP 200 + 数千行 CSV,含 24 位码 / SKU / 仓库 / 库位 /
> 创建人 / 出入库单 id / 报废原因);单据导出还能靠枚举 id 匿名读取。
> 现在改为一条精确正则 `^/api/inventory/items/\d+/barcode\.png$`,后缀匹配彻底删除 ——
> 将来新增任何 `/export.csv` 路由都不会被静默放行。
> 三个导出入口(出入库单详情、库存管理工具栏)已从裸 `<a href>` 改成 `api.download()`(fetch + Bearer + Blob 下载);
> 盘点单导出本来就是这么做的。

### 1.4 操作员字段

服务端从 JWT 解析 `username` 写入 `request.attribute("username")`,Controller 读 `currentOperator(req)`。缺值时 fallback `"操作员"`(审计可追溯性弱)。

### 1.5 分页

`page>=1, pageSize∈(0, 200]`,出参固定结构:
```json
{ "list": [...], "total": 123, "page": 1, "pageSize": 20, "pages": 7 }
```

- 出入库单 / 盘点单的分页接口**没有** `pages`(由前端按 `total/pageSize` 自己算);
  基础管理的 `/page` 接口由 `model/PageResult` 直接返回 `pages`。
- 页码越界会被**钳到最后一页**(`PageResult.clampPage`):别人删了几行后你还停在第 3 页,
  刷新会拿到最后一页而不是空列表。
- 基础管理(商品/供应商/仓库/库位/用户)的分页接口是 `GET /api/{module}/page`,
  与**全量**的 `GET /api/{module}` 并存 —— 后者仍被各下拉框和 **PDA** 使用。
  PDA 用 `List<Dto.X>` 直接解析响应,把全量端点改成分页对象会让它整块解析失败。

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
| POST | `/login` | 公开 | `{username, password}` | `{token, username, role, mustChangePw}` | 401 用户名密码错 / 403 账号已禁用 / **429 失败次数超限** |
| POST | `/logout` | 已登录 | — | `{code:0,message:ok}` | — |
| GET | `/me` | 已登录 | — | `{userId, username, role}` | 401 |
| POST | `/me/change-password` | 已登录 | `{oldPassword, newPassword}` | `{code:0,message:ok}` | 400 旧密码错 |

`POST /login` 另接受一个仅供内部受信调用方的头 `X-WMS-No-Throttle: <WMS_LOGIN_NO_THROTTLE_TOKEN>`(只有 UHF-Rader 容器会带):命中则不查锁定、不计失败,理由与 fail-closed 口径见 §1.3 的「豁免」。

### 2.2 仪表盘 — `/api/dashboard`

| 方法 | URL | 鉴权 | 参数 | 响应 |
|---|---|---|---|---|
| GET | `/stats` | 已登录 | — | `DashboardStats {productCount, totalQuantity, totalValue, lowStockCount, todayInbound, todayOutbound}` |
| GET | `/inventory-trend?days=30` | 已登录 | days 7-90 | `[{date, cumulative}]` 累计在库单元数 |
| GET | `/outbound-trend?days=30` | 已登录 | days 7-90 | `[{date, quantity}]` 每日出库件数 |

### 2.3 商品 — `/api/products`

| 方法 | URL | 鉴权 | 请求 | 响应 | 错误 |
|---|---|---|---|---|---|
| GET | `/?keyword=&supplier=` | 已登录 | `keyword` 可选模糊匹配 sku/name/category;`supplier` 传**供应商名**,匹配该商品**关联过**的供应商(见下) | `[{id, sku, name, ..., suppliers:[]}]` | — |
| GET | `/{id}` | 已登录 | — | `{...同上 + suppliers:[]}` | 404 |
| POST | `/` | **ADMIN** | `{sku, name, category?, unit?, location?, quantity?, unitPrice?, lowStock?, remark?, suppliers?:[]}` | 创建后 | 400 |
| PUT | `/{id}` | **ADMIN** | 同上去掉 `quantity` | 更新后 | 404 |
| DELETE | `/{id}` | **ADMIN** | — | `{code:0,message:ok}` | 404 |
| GET | `/page?keyword&supplier&page&pageSize` | 已登录 | 参数同 `GET /`,另加分页 | `PageResult`,见 §1.5 | — |
| GET | `/export.csv?keyword&supplier` | 已登录 | 与列表同一套筛选,不传即全量 | `text/csv`(UTF-8 + BOM) | — |
| POST | `/export.csv` | 已登录 | body: `[1,2,3]`(选中项 id) | `text/csv` | 400 空数组 |
| GET | `/import/template` | 已登录 | — | 模板 CSV(表头 + 一行示例) | — |
| POST | `/import` | **ADMIN** | `multipart/form-data`:`file`、`dryRun` | `ImportReport`,见下 | 400 / 413 |

> **导入(`POST /import`)走"预检 → 确认"两趟**:`dryRun=true` 只解析校验、**不写库**,
> 返回逐行错误(带**文件物理行号**,字段内含换行时也准)与有效行预览;`dryRun=false`
> 才真正建数据,且整体一个事务 —— 中途任何一行失败都不会留下"导了一半"的商品。
>
> `ImportReport` 字段:`{ok, dryRun, encoding, fileRows, totalErrors, errors:[{lineNo,message}],
> warnings:[], created, lines:[{lineNo,sku,name,supplierCount}]}`。`errors` 明细最多 50 条
> (`totalErrors` 是真实总数);`lines` 最多 200 条(`fileRows` 是真实行数)。
>
> **不接受「数量」列**:`product.quantity` 只由出入库/报废维护。文件里出现该列时给一条
> **不阻断**的警告并忽略;新建的商品数量恒为 0。
>
> **供应商列**:`默认供应商`(名称)+ `全部供应商`(分号分隔)。按名称匹配档案,
> 匹配不上只警告并跳过该关联;两列都空时不改动商品原有的供应商关联。
>
> 单个文件上限 **5000 行**(`wms.import.max-rows`);支持 UTF-8(含 BOM)与 GBK 自动识别。

> **`PUT` 不接受 `quantity`(2026-09-28 起)**:商品数量不是资料的一部分,只由出入库 / 报废的原子增减维护
> (`ProductDao.adjustQuantity`)。此前 create/update 共用一个解析方法、把缺失的 `quantity` 补成 0,而 DAO 的
> UPDATE 整行写 `quantity=?` —— 一个不带 `quantity` 的 PUT 就能把库存清零,而 `inventory_item` 一件没动,
> 仪表盘总量/总值/低库存跟着一起错(编辑弹窗用的是打开时的旧值,并发下同样会覆盖)。
> 现在编辑路径根本不解析这个字段(传了也忽略),前端编辑弹窗把它渲染成只读;**新建**时仍可填初始数量。

`suppliers[]` 写入元素:`{supplierId, isDefault, unitPrice?, remark?}`;读取元素(`GET` 返回的):`{id, name, isDefault}`,顺序为**默认供应商在前**(`is_default DESC, name ASC`)。写入时全量替换。

> **`supplier` 筛选按"关联过"匹配(2026-09-24 修正)**:条件是 `EXISTS (SELECT 1 FROM product_supplier …)` —— 一个商品关联多个供应商时,非默认的那几个也搜得到,与列表「供应商」列显示的内容一致。
> 此前这里的 SQL 写的是 `product.default_supplier = ?`,而 `product` 表**没有**这一列(该列从未在 `sql/mysql-schema.sql` 出现过),一用就 `Unknown column` → 500,生下来就是坏的。默认供应商只是关联集合里的一个标记,不是商品表的列。

### 2.4 供应商 — `/api/suppliers`

| 方法 | URL | 鉴权 | 备注 |
|---|---|---|---|
| GET | `/?keyword=` | 已登录 | name 模糊 |
| GET | `/{id}` | 已登录 | |
| POST | `/` | **ADMIN** | |
| PUT | `/{id}` | **ADMIN** | |
| DELETE | `/{id}` | **ADMIN** | |
| GET | `/page?keyword&status&page&pageSize` | 已登录 | 分页,见 §1.5 |
| GET / POST | `/export.csv` | 已登录 | GET 跟筛选全量;POST body `[id,...]` 导出选中 |
| GET | `/import/template` | 已登录 | 模板 CSV |
| POST | `/import` | **ADMIN** | 预检 → 确认,规则同商品(不含"数量"列的概念) |

> **`name` 唯一(2026-10-28 起)**:`supplier` 加了 `UNIQUE KEY uk_supplier_name`。
> 此前只有主键,同名能建出来,而 `SupplierService.create` 里的 `DuplicateKeyException`
> 分支因此是**死代码**。重名不是洁癖问题:供应商名称在新建单据弹窗里是下拉框的 `value`,
> 重名会让两个选项长得一模一样、选谁都写同一个名字。
> 新建、改名、CSV 导入三条路径都按同一口径拦(400「供应商名称已存在」)。
>
> 老库若有历史重名,`DataSeeder.ensureSupplierNameUnique()` 会先体检并跳过、不阻断启动。

### 2.5 仓库 — `/api/warehouses`

| 方法 | URL | 鉴权 | 参数 / 请求 | 响应 | 错误 |
|---|---|---|---|---|---|
| GET | `/?keyword&status=` | 已登录 | `keyword` 模糊匹配 code/name/remark;`status` = `enabled` / `disabled` | `[{id, code, name, remark, enabled, locationCount, ...}]` | — |
| GET | `/{id}` | 已登录 | — | `Warehouse` 单个(含 `locationCount`) | 404 |
| POST | `/` | **ADMIN** | `{code, name, remark?, enabled?}` | 创建后 | 400 code 空/重复、name 空 |
| PUT | `/{id}` | **ADMIN** | 同上 | 更新后 | 404 / 400 code 重复 |
| DELETE | `/{id}` | **ADMIN** | — | `{code:0,message:ok}` | 404;**409 名下还有库位** |
| GET | `/page?keyword&status&page&pageSize` | 已登录 | 参数同 `GET /`,另加分页 | `PageResult`,见 §1.5 | — |
| GET / POST | `/export.csv` | 已登录 | GET 跟筛选全量;POST body `[id,...]` 导出选中(含 `locationCount`) | `text/csv` | — |
| GET | `/import/template` | 已登录 | — | 模板 CSV | — |
| POST | `/import` | **ADMIN** | `file`、`dryRun` | `ImportReport` | 400 / 413 |

- `code` 唯一(`VARCHAR(32)`),`name` 必填(`VARCHAR(64)`)。
- `locationCount` 是列表/详情带出的**展示字段**(名下库位数),不是表列。
- **删除保护**:名下仍有 `location` 时抛 `IllegalStateException` → 409,提示"该仓库下还有 N 个库位";

### 2.6 库位 — `/api/locations`

同上模式,key=code,用于"商品 location 字段"下拉。

| 方法 | URL | 鉴权 | 参数 / 请求 | 备注 |
|---|---|---|---|---|
| GET | `/?keyword&status&warehouseId=` | 已登录 | `warehouseId` 可选,按所属仓库过滤 | 响应每个库位带 `warehouseId` + `warehouseName` |
| GET | `/{id}` | 已登录 | — | 同上 |
| POST | `/` / PUT `/{id}` / DELETE `/{id}` | **ADMIN** | 请求体含 `warehouseId`(可空 = 未分配) | `warehouseId` 给了但仓库不存在 → 400 |
| GET | `/page?keyword&status&warehouseId&page&pageSize` | 已登录 | 分页,参数同 `GET /` | `PageResult` |
| GET / POST | `/export.csv` | 已登录 | GET 跟筛选全量;POST body `[id,...]` 导出选中 | `text/csv` |
| GET | `/import/template` | 已登录 | — | 模板 CSV |
| POST | `/import` | **ADMIN** | `file`、`dryRun` | `ImportReport` |

> **导入的「所属仓库」列,编码和名称都认**:库位管理页的筛选下拉显示的是仓库**名称**,
> 而导出/接口给的是编码 —— 用户照着界面填多半写名称、照着导出文件改会写编码。
> 只认一种就会莫名其妙报"所属仓库不存在"。填了必须存在且**启用**(停用仓库不允许再挂库位)。
> 库位名称为空时退回编码(与 `LocationService.validate` 同口径,DDL 是 `NOT NULL`)。

**库位编码全局唯一**,跨仓库不重复(仓库只是分组标签)。这样 `inventory_item.location_code`、
出入库单明细的 `location` 等"存裸库位码"的地方无需改动即可唯一定位。

### 2.7 用户管理 — `/api/users`(类级 `@RequireRole("ADMIN")`)

| 方法 | URL | 请求 | 备注 |
|---|---|---|---|
| GET | `/?keyword&role&status&beginDate&endDate` | — | 用户列表(参数均可省略) |
| GET | `/{id}` | — | 单个用户;不存在 → `用户不存在` |
| POST | `/` | `{username, password, role}` | 创建 |
| PUT | `/{id}` | `{role}` | 改角色 |
| POST | `/{id}/password` | `{password}` | 管理员重置(用户不存在 → 404) |
| POST | `/{id}/enabled` | `{enabled: bool}` | 启停 |
| DELETE | `/{id}` | — | 不能删自己 / 最后一名 ADMIN |
| GET | `/page?keyword&role&status&beginDate&endDate&page&pageSize` | 分页,参数同 `GET /` | `PageResult` |
| GET / POST | `/export.csv` | GET 跟筛选全量;POST body `[id,...]` 导出选中 | `text/csv` |

> **用户模块不做导入**。`UserService.create` 要求**明文密码**,导入等于把明文密码写进
> CSV —— 文件会留在下载目录、被转发、进邮件;而用户数量本就少,手工建不痛。
>
> **导出按列白名单**:`{id, username, role, enabled, mustChangePw, createdBy, createdAt}`,
> **不含 `password_hash`**。模型上的 `@JsonIgnore` 只挡 JSON,CSV 走的是 getter,
> 不显式排除就会把 BCrypt 哈希写进明文件。

### 2.8 入库 — `/api/inbound`(DRAFT/COMMITTED/CANCELLED)

| 方法 | URL | 请求 | 响应 |
|---|---|---|---|
| POST | `/batch` | `{externalNo?, remark?, items:[{productId, quantity, supplier?, rowRemark?, productionDate?, shelfLifeDays?, location?}]}` | `InboundOrder` + items |

> **`POST /api/inbound`(单条入库)已删除(2026-09-24)**:全仓库没有调用方(Web 建单走上面的 `/batch`、PDA 走 `/orders`,UHF 桥接不涉及),nginx 日志也没有它的请求。而它内部直接调 `doInboundBatch`,**自调用绕过 Spring 代理 → `@Transactional` 失效**,一次写「单头 + 明细 + N 个库存单元 + 汇总」却没有事务包着,中途失败会留下半截单。既然没人用,就删入口而不是补事务(只服务它的 `InboundService.doInbound` 一并删除)。

**明细行的 `location`(2026-09-20 新增,可选)**:指定这批货落在哪个库位;不传则用**商品的默认库位**,商品也没设则落到兜底库位「默认库位」。传了就必须存在且启用,否则 400。
> 为什么需要它:一个商品的货可能因为主仓库位放不下而分到副仓库位。所以"一个商品只有一个库位"不再成立,同一批次拆到两处时**拆成两行分别指定**。
> 合并规则:只有 `商品 + 供应商 + 生产日期 + 保质期 + 库位` 全同的两行才会被合并 —— 库位不同的两行**不会**被合并掉。

**草稿单流(PDA 主流)**:
| 方法 | URL | 请求 | 响应 | 错误 |
|---|---|---|---|---|
| POST | `/orders` | `{externalNo?, remark?}` | `InboundOrder{status:DRAFT, totalQuantity:0, itemCount:0}` | 400 |
| POST | `/orders/{id}/items` | `{productId, quantity, supplier?, rowRemark?, productionDate?, shelfLifeDays?, location?}` | `InboundOrder` 刷新(含 items + 每行 codes 已回填) | 400 商品不存在/库位不存在 / 409 单子非 DRAFT |
| POST | `/orders/{id}/commit` | — | `InboundOrder`(status=COMMITTED,items[].codes 是生成的 N 个 24 位码) | 409 单子非 DRAFT / 空单 |
| POST | `/orders/{id}/cancel` | — | `{code:0}` | 409 单子非 DRAFT |

> **同一张草稿单的并发写现在是串行的(2026-09-28)**:`append/update/delete 明细`、`commit`、`cancel`、
> 扫码确认都会在事务开头对单头取**行锁**(`SELECT ... FOR UPDATE`,与盘点单同一套做法)。
> 后到的请求拿到锁时状态已经不是 DRAFT,直接 409(如「只有 DRAFT 单可以追加明细,当前 status=COMMITTED」)。
> 为什么必须这样:改明细的 DAO 写入本身**不带** `AND status='DRAFT'` 守卫,守卫只在最后一步 commit/cancel ——
> 于是"PDA 正在追加明细"与"另一个人提交同一张单"并发时,提交完的单会被继续追加:那些行永远不会被激活,
> 对应占位单元变成孤儿 PENDING(不计库存、又挡住商品删除)。出库侧后果对称:刚锁的货再没人释放
> (状态 RESERVED、所属单却是 COMMITTED),`product.quantity` 一并少算。

**查询**:
| 方法 | URL | 参数 |
|---|---|---|
| GET | `/orders?beginDate&endDate&keyword&createdBy&status&page&pageSize` | 不传 `status`(或传 `ALL`) = 不过滤、返回全部;PDA 显式传 `DRAFT`,Web 显式传 `ALL`。`keyword` 模糊匹配 **单号 / 外部单号 / 备注 / 行级供应商**(供应商在 `inbound_order_item.supplier` 上,用 EXISTS 打明细,2026-09-21 起支持) |
| GET | `/orders/{id}` | 含 items + 每行 codes(commit 后有) |
| GET | `/orders/{id}/export.csv` | UTF-8 BOM + 双引号转义,Excel/Numbers 兼容 |

### 2.9 出库 — `/api/outbound`

| 方法 | URL | 备注 |
|---|---|---|
| POST | `/batch` | 批量建单(DRAFT;提交后才动库存) |

> **`POST /api/outbound`(单货出库)已删除(2026-09-24)**:与入库侧对称 —— 全仓库没有调用方(Web 建单走 `/batch`,PDA 与 UHF 桥接走 `/orders`、`/dispatches`,测试零引用,nginx 日志也没有它的请求)。要出单件,传单元素数组给 `/batch`。它**没有**入库那条的事务隐患(`doOutbound` 自己标了 `@Transactional`),所以这次纯粹是清死入口。

**草稿单流(PDA 主流)**:同一张草稿单的并发写已串行化(行锁,见 §2.8 的说明,出库侧同理)。
| 方法 | URL | 请求 | 响应 | 错误 |
|---|---|---|---|---|
| POST | `/orders` | `{customer?, externalNo?, remark?}` | `OutboundOrder{status:DRAFT}` | 400 |
| POST | `/orders/{id}/items` | `{productId, quantity, rowRemark?}` | `OutboundOrder` 刷新(明细 + 已锁单元) | 400 商品不存在 / 409 非 DRAFT |
| **POST** | **`/orders/{id}/scan`** | **`{code}`** | **`OutboundOrder`** 刷新(items 重建) | **404 码不存在 / 409 码已 OUT/SCRAPPED** |
| POST | `/orders/{id}/commit` | — | `OutboundOrder`(status=COMMITTED,RESERVED→OUT 扣减) | 409 非 DRAFT / 空单 |
| POST | `/orders/{id}/cancel` | — | `{code:0}` | 409 非 DRAFT |

**取货库位 = 自动(2026-09-20 起)**:建单锁货时**不再按商品的默认库位做等值匹配**,而是把该商品**全部库位**的货放进一个池子按 FEFO(先到期先出)锁。所以 `outbound_order_item.location` 对新建的单**为空**,表示"自动"。

> 为什么改:同一个商品的主仓库位放不下时,剩下的会进副仓库位。以前按商品默认库位等值锁货,会报「库存不足」—— 而总量其实够,现场完全看不出原因。
> **去哪儿取货看哪里**:明细行上不再有单一库位,真实取货位置要按**锁定到的库存单元**汇总(Web 出库单详情的「🛒 拣货」行、`export.csv` 的「库位」列会写成 `自动(A-01-01×30 / B-02-02×30)`)。历史单若行上带库位(手工指定过),仍按那个库位锁。

**出库单明细的 `picks` 字段(2026-09-20 新增)**:`GET /orders/{id}` 的每个明细行带 `picks`,是「库位码 → 件数」的映射(如 `{"A-01-01":6,"B-01-01":2}`),按库位码升序。它由**该行真实锁定的库存单元**统计而来,是 `location` 为空时现场唯一能知道"去哪儿拿货"的依据(PDA 出库详情据此渲染「拣货: 主仓库/A-01-01 × 6 · 副仓库/B-01-01 × 2」)。注意它是**响应字段、不存表**。

**出库单明细的 `codesByLocation` 字段(2026-09-21 新增)**:同一响应里的每个明细行还带 `codesByLocation`,是「库位码 → 该库位取到的 24 位码列表」的映射(如 `{"A-01-01":["...3301","...3302"],"B-01-01":["...3401"]}`)。
> **已提交的单同样有值(2026-09-28 修复)**:`commitReservations` 置 OUT 时不再清空 `inventory_item.outbound_order_id` —— 那一列正是"这条明细出了哪几件"的唯一线索。此前一提交就清掉,于是订单一 COMMITTED,详情页的条码行变「无条码」、库位列退化成「自动」,`GET /api/inventory/items/by-outbound/{id}` 永远返回空,打印/导出再也看不出实际取货分布。
> 现在只有"退还在库"的单元(单据被改小、老数据余货)才会清这一列。**历史数据无法追回**:库里已有的 OUT 单元记录里该列已被置空。
> 补它是因为 `codes` 是**纯字符串列表、不带库位** —— 一个商品同时躺在主仓和副仓时,现场拿着几个码看不出哪个该放哪个仓(跨库位取货之后这是常态)。
> 它与 `picks` **同源同序**(同一个循环产出、都用 `TreeMap`),所以 `codesByLocation.keySet()` 与 `picks.keySet()` **恒等**:同一个库位,`picks` 给件数、`codesByLocation` 给码。
> 两者**不能靠推算对应**:`picks` 记的是件数、`codes` 是一单元一码,老数据(`remaining>1`)一个码算多件,切不出块。
> 同样**不存表**;`codes` 保持原样没动(入库明细也在用它,且 PDA 靠它做扫码 ✓ 标记的身份比对)。
> 空库位码(`""`)的抬头统一显示「未指定库位」(Web / CSV / PDA 一个说法)。
> 已知限制:同一张单若存在两行**同商品**的明细,按商品查单元会让两行拿到同一批单元(`codes` 与 `picks` 都会重复)。出库批量建单本身按商品合并,正常不会出现;手工往草稿追加同商品行才会。

**UHF 相关端点**(不在上表,实现在 `OutboundController`):`GET /orders/{id}/expect`(期望扫码清单,取本单 RESERVED 单元)、
`POST /orders/{id}/scan-epc`(扫码确认,不扣库存)、`POST /orders/{id}/reset-scans`(清本单扫码确认)、
`POST /orders/{id}/dispatch` 与 `GET /dispatches` / `POST /dispatches/{id}/claim`(派单到设备)。
> `scan-epc` 的 `createdBy` **可以由请求体给出**,这是有意设计:UHF-Rader 持的是它自己的服务账号 token,
> 但扫码是**人工**动作,桥接把浏览器会话里那名操作员的名字转发过来(`wms_bridge.py`)。所以这条审计的可信前提是
> **桥接服务本身可信**;代价是任何已登录用户都能在这个字段上冒名。

**追加明细会并进同商品的行(2026-09-20 起)**:`POST /orders/{id}/items` 若该单已有同商品的行,就**数量相加**到那一行上,不再新插一行(`addItemToDraft`)。这样上面那条"同商品两行"的限制从**入口**上就不可达了 —— 批量建单(`/batch`)本来就按商品合并,这次把 PDA 的追加路径对齐了它。
> 代价:第二次传的 `rowRemark` 会被丢掉(第一行的备注生效),与批量建单行为一致。
> 锁货量:每次调用只按**本次传入的 quantity** 追加锁定,不会把整行重新锁一遍;
> 库存不足时抛异常、事务回滚,不会留下"数量加了但没锁到货"的半截状态。

**查询**:同入库,不传 `status`(或传 `ALL`) = 不过滤。

### 2.10 库存单元 — `/api/inventory/items`

| 方法 | URL | 鉴权 | 备注 |
|---|---|---|---|
| GET | `/?sku&location&warehouseId&status&expiryStatus&expiryFrom&expiryTo&page&pageSize` | 已登录 | 过滤 + 分页;`sku` 是**模糊匹配,同时命中 `sku` 与 24 位 `code`**(2026-09-21 起 —— 同一个搜索框既能搜 SKU 也能搜实物码);`warehouseId` 按仓库筛(经 `location.warehouse_id`),一个商品分散多仓时用它分别看数 |
| GET | `/{id}` | 已登录 | |
| GET | `/by-inbound/{orderId}` | 已登录 | 入库单下所有单元 |
| GET | `/by-outbound/{orderId}` | 已登录 | 出库单消耗的所有单元(RESERVED + OUT;**2026-09-28 起已提交的单同样有值**,见 §2.9) |
| GET | `/by-code/{code}` | 已登录 | PDA 扫码查询;code→InventoryItem |
| GET | `/by-epc/{epc}` | 已登录 | EPC 别名(同 by-code) |
| GET | `/by-code/{code}/product` | 已登录 | code→InventoryItem→Product(PDA 入库扫码解析) |
| GET | `/{id}/barcode.png` | 已登录 | CODE_128 PNG |
| POST | `/{id}/scrap` | **ADMIN** | `{remark?}`,只能 IN_STOCK→SCRAPPED。SCRAPPED 是**不可逆终态**(没有接口能改回去),且是唯一能"静默减少可用库存"的日常操作,所以只给 ADMIN |
| POST | `/{id}/transfer` | 已登录 | `{toLocation}`,仅 IN_STOCK 可调;改 `location_code`、**状态保持 IN_STOCK**,并写一条 `inventory_log(type=TRANSFER)`。目标库位必须存在且启用,否则 400。只挪位置、总量不变、挪错可再挪一次,所以不设角色门槛(2026-09-24 起,此前误标 ADMIN) |
| GET | `/export.csv?sku&location&warehouseId&status&...` | 已登录 | 单次硬编码 5000 行,大库需分页;列含「仓库」 |

**调拨语义(2026-09-20 修正)**:此前 `transfer` 把单元打成终态 `TRANSFERRED` 且**不改 `location_code`** —— 货从默认库存列表(只看 `IN_STOCK`)里消失、再也不能出库/盘点,而 `product.quantity` 仍统计它。现在调拨=移动位置,状态不变。

**库存单元状态(`inventory_item.status`)** —— DB 列默认 `IN_STOCK`:

| 值 | 含义 | 界面显示 |
|---|---|---|
| `IN_STOCK` | 在库,可出库 / 可调拨 / 可报废 | 在库 |
| `PENDING` | **待入库**:入库**草稿**单生成的占位单元 —— 尚未生效(`remaining=0`,不计入库存、不写流水),入库单提交时才升级为 `IN_STOCK`;草稿单取消则被删除 | 待入库 |
| `RESERVED` | **已预留**:被某张出库**草稿**单锁定(`outbound_order_id` 指向它)—— 出库单提交后变 `OUT`,取消则回到 `IN_STOCK` | 已预留 |
| `OUT` | 已出库 | 已出库 |
| `SCRAPPED` | 已报废 | 已报废 |
| `TRANSFERRED` | 已调拨(改过库位) | 已调拨 |

⚠️ 与**单据状态**是两套不同维度的枚举,别混:出入库单/盘点单的状态只有 `DRAFT`/`COMMITTED`/`CANCELLED`(界面上显示草稿/已提交/已取消);PDA 内部还有个 `DocStatus.PENDING("待执行")`,那是**单据**状态的兜底值,与上面的 `PENDING("待入库")` 只是同名。

### 2.11 盘点 — `/api/stock-checks`

| 方法 | URL | 鉴权 | 请求 | 响应 | 错误 |
|---|---|---|---|---|---|
| GET | `/?beginDate&endDate&status&page&pageSize` | 已登录 | — | `[{id, checkNo, scope, ...}]` | — |
| GET | `/{id}` | 已登录 | — | `StockCheck + items[]` | 404 |
| POST | `/` | 已登录 | `{scope:ALL/SKU/WAREHOUSE, keyword?, warehouseId?, remark?}` | `StockCheck`(快照所有 in_stock 单元到明细) | 400 scope 错 |
| POST | `/{id}/items/{itemId}/count` | 已登录 | `{countedQty, remark?}` | `{code:0}` | 400 / 404 |
| **POST** | **`/{id}/scan`** | **已登录** | **`{code}`** | **`StockCheck`** 刷新:命中快照 → 记已盘;快照外 → 追加盘盈行(应盘 0 / 实盘 1) | **404 码不存在 / 409 盘点单非 DRAFT / 422 该码已盘过(含并发重复)、或按仓库盘点扫到非在库的跨仓库码** |
| POST | `/{id}/commit` | 已登录 | — | `StockCheck`(status=COMMITTED,生成 diff 报告) | 409 |
| POST | `/{id}/cancel` | 已登录 | — | `{code:0}` | 409 |
| GET | `/{id}/export.csv` | 已登录 | 差异报告(每行 system/counted/diff) | — |

> **盘盈行(2026-09-29)**:扫到的码不在本单快照里,就自动追加一行 `应盘 0 / 实盘 1`(非在库的码也一样)。
> **按仓库盘点**时,来自其他仓库(或库位未挂仓库)的码分两种:单元**在库** → 照记盘盈,并把来源写进行备注
> (`来自其他仓库,记录库位 X` / `记录库位 X 未挂到任何仓库`);**非在库** → 422。
> 分界理由:非在库单元不在**任何**盘点的快照里,放行会让同一实物被两张单各记一次盘盈;而在库单元在它**自己仓库**的快照里,那边扫到算「已盘」,不会双记。
> 盘盈行**不计入** `totalCount / countedCount / diffCount` 三个汇总(口径见 `StockCheckDao.SURPLUS_COND`),只在详情页单独成区、导出 CSV 里按「盘盈数 / 盘亏数」两行体现。

> **整条线不设角色门槛(2026-09-24 起)**:此前只有 `/scan` 不限角色,`create/count/commit/cancel` 要 ADMIN —— 而两端界面把「新建盘点」「提交」都摆给了操作员,点了必然 403。现在统一放开:盘点在本系统**不动库存**(提交只固化差异记录,库存校正另行处理),作业员自己发起、盘完、提交即可。
> 作为对比,`/api/inventory/items/{id}/scrap`(报废,不可逆减库存)**仍限 ADMIN**,`/transfer`(调拨,可逆)不限。

> **`scope=LOCATION` 已不能创建(2026-09-21)**:它与「按仓库 + 多选库位」重叠(按仓库只勾一个库位即等价),创建入口已从 Web 与 PDA 移除。**枚举值与读取标签保留** —— 历史单(若生产库有)仍能正确显示「按库位」,不会退化成裸文本。库内实测 `scope='LOCATION'` 为 0 行。

### 2.12 统一出入库单列表 — `/api/orders`(仅 Web 用)

入库单和出库单 UNION ALL 成一张列表,供 Web「全部」类型下拉使用。

| 方法 | URL | 鉴权 | 请求 | 响应 | 错误 |
|---|---|---|---|---|---|
| GET | `/?beginDate&endDate&keyword&createdBy&status&page&pageSize` | 已登录 | — | `[{id, orderNo, type(IN/OUT), externalNo, partner, remark, createdBy, createdAt, itemCount, totalQuantity, status}]` | — |

> **`keyword` 匹配哪些字段(2026-09-21 起)**:两个分支各自匹配 —— 入库 `单号 / 外部单号 / 备注 / 行级供应商`(供应商用 `EXISTS` 打 `inbound_order_item`,因为单头的 `inbound_order.supplier` 是死列),出库 `单号 / 外部单号 / 备注 / customer`。所以搜索框那句「单号 / 供应商 / 客户 / 外部单号 / 备注」现在与实现一致了(此前供应商与客户都搜不到)。

**`partner` 与 `externalNo` 是两个独立字段(2026-09-21 修正)**:列表上「供应商 / 领用方」那一列取 `partner`,「外部单号」那一列取 `externalNo`。

> 之前出库分支把 `customer` 别名成了 `external_no`,前端又拿同一个值去填两列 —— 于是「外部单号」和「领用方」永远同值(入库行同理:两列都显示 `external_no`)。
> 现在:出库行 `partner = customer`;入库行 `partner` = 该单**行级** `inbound_order_item.supplier` 去重拼接(空格加斜杠分隔),没有则 `null`。
> 注意 `inbound_order.supplier`(单头)是一列**从未被写入过**的死列,不要拿它当数据源 —— 真相在行级。

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

# 盘点扫码被拒(422 的两种来源)
$ curl -X POST http://localhost:9090/api/stock-checks/5/scan -H "Authorization: Bearer ..." -d '{"code":"000000003500302029039358"}'
HTTP/1.1 422
{"code":422,"message":"该码已盘过,无需重复扫描","data":null}

# 按仓库盘点,且该码是**别的仓库**的「非在库」单元(在库的会记成盘盈,不拒)
{"code":422,"message":"该码不在本次盘点的仓库范围内(库位 B-02-02,属于其他仓库),且该单元已不在库,不能记盘盈","data":null}
```

## 4. 24 位 inventory_item.code 校验端点

PDA 扫码链路涉及的端点(共 4 个):

| 端点 | 入参 | 作用 | 返回 |
|---|---|---|---|
| `GET /api/inventory/items/by-code/{code}` | code | 实物码查单元 | `InventoryItem` 或 404 |
| `GET /api/inventory/items/by-epc/{epc}` | epc | EPC 别名 | 同上 |
| `GET /api/inventory/items/by-code/{code}/product` | code | 实物码反查商品(PDA 入库扫码辅助) | `Product` 或 404 |
| `POST /api/outbound/orders/{id}/scan` | code | 出库单扫码加入 | 200 / 404 / 409 |
| `POST /api/stock-checks/{id}/scan` | code | 盘点单扫码(命中快照记已盘;快照外追加盘盈) | 200 / 404 / 409 / 422 |

## 5. 鉴权矩阵

| 端点 | 公开 | 已登录 | ADMIN |
|---|---|---|---|
| 登录 / 注册 | ✓ | | |
| 查询类(stats / products / orders) | | ✓ | |
| 写入单据(批量) | | ✓ | |
| DRAFT 单据的 scan / commit | | ✓ | |
| 创建/删除商品/供应商/库位/仓库 | | | ✓ |
| 基础数据导出(`/export.csv`,含选中导出) | | ✓ | |
| 基础数据导入(`POST /import`,预检与落库**都是 ADMIN**;模板下载只需登录) | 模板 ✓ | 模板 ✓ | 导入 ✓ |
| 盘点单(创建/计数/删行/提交/取消/扫码) | | ✓ | |
| 用户管理 | | | ✓ |
| transfer(调拨,实物) | | ✓ | |
| scrap(报废,不可逆) | | | ✓ |
| inventory_item.read 类 | | ✓ | |

## 6. 性能与限流

- 登录入口有失败限流(§1.3:同一 **用户名+IP** 错 5 次、或同一 **IP** 错 20 次 → 锁 5 分钟;UHF-Rader 容器整机豁免);**其余接口无显式限流**
- 库存 CSV 导出硬编码 5000 行
- FIFO 出库:`consumeForOutbound` 多次 SELECT inventory_item(按 `id ASC` ORDER BY limit),N=100 时约几毫秒

## 7. 接口约定补充

- 列表查询都支持 `page` + `pageSize`,默认 pageSize=20
- 时间查询:`beginDate` `endDate` 是 `yyyy-MM-dd` 字符串,服务端补 00:00:00 / 23:59:59
- 关键词查询:`keyword` 默认 LIKE 模糊匹配多个字段
- 大写/小写:SKU 精确匹配,name/category 部分模糊

## 8. 后续待办

- 出库扫码加 SKU 校验(避免错码入错单)— 当前仅校验码 + IN_STOCK
- 接口限流(防 PDA 端 bug 触发批量请求)。登录入口已有失败限流(§1.3),其余接口仍无
- 库存上量后给「全库盘点」加提示/限制(建单会把该范围期望行逐行写进 `stock_check_item`,现在 19 件无所谓)
- 库存 item 批量报废 / 批量调拨
- 盘点 commit 支持"扫码模式"自动算 diff(避免要求所有明细填完)

### 8.1 已评审但**本轮明确不做**(2026-09-28,避免后人重复排查)

- **`RIGHT(code, ?)` 尾匹配用不上索引**:扫码兜底(条码前导零丢失兼容,见 `InventoryItemDao.findByCode`)在**增长最快的表**上不可走 `uk_inventory_item_code`。当前表内只有几十行,且精确匹配是快路径、只有落空才走兜底,所以收益要到千行级才显现。届时的做法是加一个生成列 `RIGHT(code, 24)` + 索引,或干脆存一列规范化码。
- **`GET /api/inventory/items/export.csv` 单次 5000 行、无分页**:行数由 controller 传的 `pageSize` 决定,DAO 不额外封顶;库存上量后要改成分页或流式。
- **导出的分页语义**:DAO 里 `pageSize > 200` 即视为"导出/批量"并强制 `OFFSET 0`,任何调用方传大 `pageSize` 都会忽略 `page` 参数 —— 当前唯一调用方就是导出,行为正确,但别拿它当分页接口。
- **未分页渲染**:Web 的商品 / 供应商 / 库位 / 仓库列表与盘点详情一次性渲染全部行(库存与单据列表已分页)。数据量小,先不动。
- **`GeneratedKeys.id` 失败时返回 0**:MySQL 驱动稳定返回 `GENERATED_KEY`,只有 `UserDao.insert` 与库存单元插入做了 0 值防护;其余调用方会把 0 当真实 id。属防御性加固。
- **`RoleInterceptor` 默认放行**(没写 `@RequireRole` 就对所有已登录用户开放):这是既定姿态(见 §5 矩阵),代价是新增 controller 忘写注解即默认开放。
- **`barcode.png` 的 404 是空响应体**(`ResponseEntity.status(404).build()`),与其余 404 的 JSON 壳不一致;二进制端点,暂不动。
- **i18n `t(key, {default})` 不支持 `default` 参数**、`confirmModal` 硬编码中文「确认」:`views.js` 的 `statusLabel`/`docStatusLabel` 是死代码,一旦被调用会吐裸 key。低危。

### 8.2 已评审但决定**接受**的风险(2026-09-29)

- **UHF-Rader 登录页 = 局域网内不限次数的口令试探入口**。§1.3 的豁免让整个 UHF 容器绕过登录失败限流,而该容器的 Web UI 端口是 `5001:5000`(绑所有网卡,局域网可达),`GET /api/wms/session` 又是免鉴权的 —— 局域网内任何一台机器都能先领 cookie、再反复打 `/api/wms/login` 试探 WMS 口令,上限从「5 次/(用户名,IP)」变成没有。
  选它是因为反面的代价更具体:UHF 终端被锁 5 分钟 = 现场整条出库扫码线停摆,而该终端只服务于车间局域网。WMS 网页登录页与 PDA 不带豁免头,照旧限流(§1.3)。
  真要收紧,先确认现场是**怎么打开 UHF 界面**的:同一台机器可把端口改成 `"127.0.0.1:5001:5000"`;若是局域网另一台设备访问,则只能在 Flask 侧给 `/api/wms/login` 加一层轻量节流 —— 那会与「终端登录页不做失败限流」的要求相抵触,需重新拍板。
