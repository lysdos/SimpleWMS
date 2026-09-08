# Web 端:入库/出库列表默认只显示已提交单

## Context

SimpleWMS 的入库/出库已经实现完整的三态状态机(DRAFT / COMMITTED / CANCELLED)。
DRAFT 阶段写入的 `inventory_item` 是 `STATUS_PENDING + remaining=0`(占 24 位码、不入总库存、不动 productDao.quantity、不写 inventory_log);COMMIT 时升级为 `IN_STOCK + remaining=1 + 加 product.quantity + 写 inventory_log`;CANCEL 时直接删 PENDING 单元、删明细、status=CANCELLED。

业务上的硬约束:
1. **未提交入库单的产品不能出现在库存中** — DRAFT 期间 PENDING 单元不计入 `sumRemaining`,commit 才生效。已实现。
2. **未提交的入库单不能出现在出入库单中** — 这是 web 端的列表/卡片展示规则,需要本次确认 + 落地。PDA 不动。

用户对 "出入库单" 范围的明确界定:
- 「最近入库单」「最近出库单」卡片 → **只显示已提交单**(草稿/取消不出现)。
- 「入库登记」卡片(实际意义是"全部入库单"+ 状态 pill)→ **保持现状,显示所有状态**(DRAFT/COMMITTED/CANCELLED 都在),只有这个页面能点详情、提交、取消。
- 「出库登记」卡片 → **只显示已提交单**(出库没有 DRAFT 提交/取消流程,PDA 扫码即直接 COMMITTED)。
- 「出入库管理」(全量订单页 loadOrders)→ **默认只显示已提交单 + 提供状态筛选下拉**(草稿/已提交/已取消/全部)。
- 「出入库管理」详情页 → **不显示提交/取消按钮**(只读;提交/取消入口只在「入库登记」)。

## 范围

**做**

1. Web 端「最近入库/出库单」卡片:`loadInbound` / `loadOutbound` 的 `status=ALL` 改为 `status=COMMITTED`。
2. Web 端「出入库管理」(`loadOrders`):
   - `state.orderFilter.status` 默认 `'COMMITTED'`。
   - `state.orderFilter.status` 写入 `URLSearchParams` 的 `status` 查询参数。
   - `searchOrders()` 读取 + 写入 status;`resetOrderFilter()` 把 status 重置为 `'COMMITTED'`(不是 `''`)。
3. `Views.orders()` 工具栏增加状态筛选下拉:`全部 / 草稿 / 已提交 / 已取消`(value = `'' / 'DRAFT' / 'COMMITTED' / 'CANCELLED'`),初始化时回填当前 `state.orderFilter.status`。
4. 不动后端 controller / service / dao / PDA / 库存页 / 仪表盘 / 入库登记卡片详情提交/取消按钮。

**不做**

- 不改后端默认 status(`InboundController.listOrders` / `OutboundController.listOrders` 仍 `defaultValue="DRAFT"`,Web 端显式传 `status=COMMITTED` 覆盖即可)。
- 不动 PDA 的 `WmsRepository`。
- 不动库存页 / 仪表盘 / 出入库扫码逻辑(PENDING 已不计入可用库存,本就是设计要求)。
- 不在「出入库管理」详情页加提交/取消按钮。

## 关键文件

### 改

- `src/main/resources/static/js/app.js`
  - `loadInbound`(约第 99-100 行):`status=ALL` → `status=COMMITTED`。
  - `loadOutbound`(约第 112 行):`status=ALL` → `status=COMMITTED`。
  - `state.orderFilter`(第 19 行):新增 `status: 'COMMITTED'` 默认值。
  - `loadOrders`(约第 486-500 行):在 `qs.set(...)` 链路里加 `if (f.status) qs.set('status', f.status);`。
  - `searchOrders()`(约第 502 行):`orderFilter` 读取加 `status: document.getElementById('od-status').value || 'COMMITTED'`。
  - `resetOrderFilter()`(约第 513 行):`status` 字段重置为 `'COMMITTED'`(不是 `''`)。

- `src/main/resources/static/js/views.js`
  - `Views.orders()`(约第 460-540 行)工具栏:在 `od-operator` 旁边加一个 `<select id="od-status">`,4 个 option:`全部(value="") / 草稿("DRAFT") / 已提交("COMMITTED") / 已取消("CANCELLED")`,初始化回填 `state.orderFilter.status`。

### 不改

- `src/main/java/com/wms/controller/InboundController.java` / `OutboundController.java`
- `src/main/java/com/wms/service/InboundService.java` / `OutboundService.java` / `InventoryItemDao.java`
- `src/main/resources/static/js/views.js` 中 `Views.inboundOrderModal` 提交/取消按钮保持现状(只在 DRAFT 显示)。
- `pda-android/...` 任何文件。

## 数据流

1. **入库登记卡片加载**:
   `loadInbound` → `api.get('/api/inbound/orders?status=COMMITTED&pageSize=10')` → 后端按 status 过滤 → 卡片只渲染 COMMITTED 单子。
2. **出库登记卡片加载**:
   `loadOutbound` → `api.get('/api/outbound/orders?status=COMMITTED&pageSize=10')` → 同上。
3. **入库登记点 DRAFT 单详情**(用户需求里允许):
   `viewOrderDetail(orderNo)` → `api.get('/api/inbound/orders?keyword=...&status=ALL&pageSize=1')` 反查 id(保持现状)→ `api.get('/api/inbound/orders/{id}')` 拉详情 → `Views.inboundOrderModal` 渲染,`o.status==='DRAFT'` 时显「提交/取消」按钮(保持现状)。
4. **出入库管理加载**:
   `loadOrders` → `state.orderFilter.status === 'COMMITTED'` → `api.get('/api/inbound/orders?...&status=COMMITTED&...')` → 默认显示已提交单。
5. **用户切换状态筛选下拉**:
   `searchOrders` 读下拉值 → 写 `state.orderFilter.status` → `loadOrders` → API 带新 status 重查。
6. **用户点「重置」**:
   `resetOrderFilter` → status 重置为 `'COMMITTED'`(不是 `''`)→ `loadOrders`。

## 实现步骤

1. `app.js`:`state.orderFilter` 加 `status: 'COMMITTED'`。
2. `app.js`:`loadInbound` / `loadOutbound` 的 `status=ALL` 改 `status=COMMITTED`。
3. `app.js`:`loadOrders` 在 URLSearchParams 段补一行 `if (f.status) qs.set('status', f.status);`。
4. `app.js`:`searchOrders` 读 `od-status` 下拉,`resetOrderFilter` 重置 status 为 `'COMMITTED'`。
5. `views.js`:`Views.orders` 工具栏插入 `<select id="od-status">` 4 选项,value 绑定当前 `state.orderFilter.status`。
6. 浏览器单点验证(冒烟见下)。

## 复用现有工具

- `Views.orders` 现有工具栏布局(begin/end/keyword/operator)→ 加 status 下拉沿用同样 `.input` / `.select` class。
- `docStatusLabel()`(已在 `app.js:399`)用于下拉文案(DRAFT → 草稿 / COMMITTED → 已提交 / CANCELLED → 已取消)。
- `state.orderFilter` 已有的 4 字段模式(beginDate / endDate / keyword / operator)→ 加第 5 字段 `status` 完全一致。
- `resetOrderFilter()` 已有的"全部置空字符串"模式 → 唯独 status 例外(重置为 `'COMMITTED'`,而非 `''`)。

## 验证

**后端冒烟**

浏览器打开 `http://<host>:8080`(假设运行端口)→ 登录 admin → 走 4 个流程。

1. **入库登记卡片**(默认):
   - 看到「最近入库单(最近 10 条)」表格 **只** 含 COMMITTED 单子(草稿/取消单子不出现)。
   - 之前的 DRAFT 单(假如 id=27,29 之类)在「入库登记」卡片看不到 — 这是预期。
2. **入库登记点 DRAFT 单详情**(任意方式:把 ID 拼到 URL、或在某个未过滤的入口打开):
   - 打开后 modal 显示 DRAFT 状态 pill,底部有「提交入库」+「取消」按钮(此能力保留)。
3. **出库登记卡片**(默认):
   - 同入库,只显示 COMMITTED 单子。
4. **出入库管理**(默认):
   - 「状态」下拉默认显示「已提交」。
   - 表格只列出 COMMITTED 单子。
   - 切换到「草稿」/「已取消」/「全部」→ 列表重查,行为正确。
   - 切换到「已提交」后又点「重置」→ 下拉回到「已提交」(不是「全部」)。

**回归**

- PDA 入库:`createDraft` → `addItem` → `commit` 完整流程保持工作。
- 库存页:已 commit 单子的 PENDING → IN_STOCK 转换仍正确(不变,本设计不动后端)。
- 仪表盘 KPI:PENDING 不进 sumRemaining 仍正确(不变)。
- 入库详情页提交/取消按钮:仅 DRAFT 显示(不变)。

## 不在本次范围 / 留作后续

- 后端 controller 默认 status 改 COMMITTED(若 Web 不显式传 status,PDA 走 /api/inbound/orders 时会拿到空 — PDA 当前不依赖默认 status,显式传 ALL / 走具体 id)。
- 入库登记卡片加"全部/草稿/已提交/已取消"状态过滤(本次需求里"入库登记可以显示全部状态",默认行为即符合;不加额外下拉避免范围蔓延)。
- 出库登记卡片(已只显示 COMMITTED,出库没有 DRAFT 流程)— 也不加过滤。
- 「出入库管理」详情页的提交/取消按钮(用户明确不要)。