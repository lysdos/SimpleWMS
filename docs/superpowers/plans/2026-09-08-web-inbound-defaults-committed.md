# Web 入库/出库列表默认只显示已提交单 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Web 端「最近入库/出库单」卡片 + 「出入库管理」默认只显示已提交单;「入库登记」卡片保持列出全部状态并保留 DRAFT 单的提交/取消入口;「出入库管理」详情页保持只读。

**Architecture:** 纯前端改动。`static/js/app.js` 把 `loadInbound`/`loadOutbound` 的查询字符串 `status=ALL` 改为 `status=COMMITTED`,并在 `state.orderFilter` / `loadOrders` / `searchOrders` / `resetOrderFilter` 链路里加 `status` 字段(默认 `COMMITTED`,重置也回 `'COMMITTED'`,非空串)。`static/js/views.js` 的 `Views.orders` 工具栏插入 4 选 1 状态筛选下拉,绑定到现有 `state.orderFilter.status`。后端 / PDA / 库存页 / 仪表盘 / 入库详情提交/取消按钮 / 详情 modal 全部不动。

**Tech Stack:** Vanilla JS (Spring Boot 3.3.5 静态资源),Docker Compose 构建后端镜像。

## Global Constraints

- 不改后端 Java 代码、不改 DAO、不改 controller、不重启 DB schema。
- 不改 PDA(`pda-android/...`)。
- 不动库存页 / 仪表盘 / 出入库扫码 / 入库详情提交/取消按钮的逻辑 — 仅文件内的字段 / 查询字符串级微调。
- 「出入库管理」详情页保持只读 — 不渲染提交/取消按钮。
- `state.orderFilter.status` 默认 `'COMMITTED'`(不是 `''`);`resetOrderFilter` 把 status 重置回 `'COMMITTED'`(不是 `''`)。
- 「入库登记」卡片(`Views.inbound` / `Views.outbound`)的渲染逻辑不变 — DRAFT/COMMITTED/CANCELLED 都显示状态 pill,`viewOrderDetail` 走 `status=ALL` 反查 id 保持现状。
- 状态文案用 `docStatusLabel()`(`app.js:399`)已定义的映射(DRAFT=草稿 / COMMITTED=已提交 / CANCELLED=已取消);空串 = 全部。

---

### Task 1: 改 `app.js` 5 处

**Files:**
- Modify: `src/main/resources/static/js/app.js`

**Interfaces:**
- 现有 `state.orderFilter`(第 19 行附近,`{ beginDate, endDate, keyword, operator }`)扩展为含 `status: 'COMMITTED'`。
- `loadInbound` / `loadOutbound` 拼 URL 时改查询字符串 `status=ALL` → `status=COMMITTED`。
- `loadOrders` 把 `state.orderFilter.status` 写入 `qs`(`if (f.status) qs.set('status', f.status)`)。
- `searchOrders` 读 `od-status` 下拉写入 `orderFilter.status`(默认 `'COMMITTED'`)。
- `resetOrderFilter` 把 `status` 重置为 `'COMMITTED'`(不是 `''`)。

**Consumes:** 现有 `state` / `api.get` / `Views.inbound` / `Views.outbound` / `Views.orders`。
**Produces:** Web 端默认 `status=COMMITTED` 体现在 API URL;`state.orderFilter.status` 字段就位。

- [ ] **Step 1: 改 `state.orderFilter` 初始值**

定位:`src/main/resources/static/js/app.js` 第 19 行附近。

把:
```js
orderFilter: { beginDate: '', endDate: '', keyword: '', operator: '' },
```

改为:
```js
orderFilter: { beginDate: '', endDate: '', keyword: '', operator: '', status: 'COMMITTED' },
```

- [ ] **Step 2: 改 `loadInbound` 卡片默认查询字符串**

定位:`src/main/resources/static/js/app.js` 第 99-100 行。

把:
```js
        // 默认 status=ALL:PDA 提交的单子是 COMMITTED,不应被默认 DRAFT 过滤掉
        const d = await api.get('/api/inbound/orders?status=ALL&pageSize=10');
```

改为:
```js
        // 最近入库单卡片只显示已提交单(草稿/已取消的不出现在卡片里,详情入口仍可在「入库登记」找到)
        const d = await api.get('/api/inbound/orders?status=COMMITTED&pageSize=10');
```

- [ ] **Step 3: 改 `loadOutbound` 卡片默认查询字符串**

定位:`src/main/resources/static/js/app.js` 第 112 行。

把:
```js
        const d = await api.get('/api/outbound/orders?status=ALL&pageSize=10');
```

改为:
```js
        const d = await api.get('/api/outbound/orders?status=COMMITTED&pageSize=10');
```

- [ ] **Step 4: 改 `loadOrders` 把 `status` 写入 `URLSearchParams`**

定位:`src/main/resources/static/js/app.js` 第 486-500 行(`loadOrders` 函数体)。

在 `qs.set('operator', f.operator);` 之后追加:
```js
    if (f.status)    qs.set('status',    f.status);
```

完整函数体改后形如:
```js
async function loadOrders() {
    setActive('orders');
    const t = state.orderType || 'IN';
    const f = state.orderFilter || {};
    const qs = new URLSearchParams();
    if (f.beginDate) qs.set('beginDate', f.beginDate);
    if (f.endDate)   qs.set('endDate',   f.endDate);
    if (f.keyword)   qs.set('keyword',   f.keyword);
    if (f.operator)  qs.set('operator',  f.operator);
    if (f.status)    qs.set('status',    f.status);
    qs.set('page', String(state.orderPage || 1));
    qs.set('pageSize', '20');
    const url = (t === 'IN' ? '/api/inbound/orders' : '/api/outbound/orders') + '?' + qs.toString();
    const data = await api.get(url);
    document.getElementById('view').innerHTML = Views.orders(t, data, f);
}
```

- [ ] **Step 5: 改 `searchOrders` 读下拉 + `resetOrderFilter` 重置为 `'COMMITTED'`**

定位:`src/main/resources/static/js/app.js` 第 502-517 行。

把:
```js
function searchOrders() {
    state.orderType  = document.getElementById('od-type').value || 'IN';
    state.orderFilter = {
        beginDate: document.getElementById('od-begin').value || '',
        endDate:   document.getElementById('od-end').value   || '',
        keyword:   document.getElementById('od-kw').value    || '',
        operator:  document.getElementById('od-op').value    || ''
    };
    state.orderPage = 1;
    loadOrders();
}
function resetOrderFilter() {
    state.orderFilter = { beginDate: '', endDate: '', keyword: '', operator: '' };
    state.orderPage = 1;
    loadOrders();
}
```

改为:
```js
function searchOrders() {
    state.orderType  = document.getElementById('od-type').value || 'IN';
    state.orderFilter = {
        beginDate: document.getElementById('od-begin').value || '',
        endDate:   document.getElementById('od-end').value   || '',
        keyword:   document.getElementById('od-kw').value    || '',
        operator:  document.getElementById('od-op').value    || '',
        status:    document.getElementById('od-status').value || 'COMMITTED'
    };
    state.orderPage = 1;
    loadOrders();
}
function resetOrderFilter() {
    state.orderFilter = { beginDate: '', endDate: '', keyword: '', operator: '', status: 'COMMITTED' };
    state.orderPage = 1;
    loadOrders();
}
```

- [ ] **Step 6: 静态自检**

在 IDE 里 grep 确认:
- `loadInbound` / `loadOutbound` 已不含 `status=ALL`
- `state.orderFilter` 初值有 `status: 'COMMITTED'`
- `searchOrders` / `resetOrderFilter` 都引用了 `status`

预期:`grep -n "status=ALL" src/main/resources/static/js/app.js` 无输出。

- [ ] **Step 7: 提交(等 Task 3 一起 commit,或单独 commit)**

单独提交:
```bash
git add src/main/resources/static/js/app.js
git commit -m "feat(web): 入库/出库列表默认只显示已提交单"
```

---

### Task 2: 改 `views.js` 工具栏加状态筛选下拉

**Files:**
- Modify: `src/main/resources/static/js/views.js`

**Interfaces:**
- 在 `Views.orders` 函数里,把 `filter.status` 读到 `statusVal`(默认 `'COMMITTED'`)。
- 工具栏插入 `<select id="od-status">`,4 个 option:`''`(全部)/ `'DRAFT'`(草稿)/ `'COMMITTED'`(已提交)/ `'CANCELLED'`(已取消),`selected` 属性回填当前 `state.orderFilter.status`。

**Consumes:** 现有 `Views.orders` 工具栏模板(`<select id="od-type">` / `<input id="od-begin">` / `<input id="od-end">` / `<input id="od-kw">` / `<input id="od-op">`)。
**Produces:** 状态筛选下拉的 DOM 节点;`searchOrders` 通过 `document.getElementById('od-status').value` 读到状态。

- [ ] **Step 1: 读 filter.status 到 statusVal**

定位:`src/main/resources/static/js/views.js` 第 460-470 行(`orders` 函数顶部)。

把:
```js
        const beginVal = (filter && filter.beginDate) || '';
        const endVal   = (filter && filter.endDate)   || '';
        const kwVal    = (filter && filter.keyword)   || '';
        const opVal    = (filter && filter.operator)  || '';
```

改为:
```js
        const beginVal  = (filter && filter.beginDate) || '';
        const endVal    = (filter && filter.endDate)   || '';
        const kwVal     = (filter && filter.keyword)   || '';
        const opVal     = (filter && filter.operator)  || '';
        const statusVal = (filter && filter.status)    || 'COMMITTED';
```

- [ ] **Step 2: 工具栏插入状态筛选下拉**

定位:`src/main/resources/static/js/views.js` 第 517 行(`<input id="od-op">` 那行)之后,`查询` 按钮之前。

在 `<input id="od-op">` 之后追加(注意:`<label>` 加 "状态:" 文案以提高可读性):
```html
                <label style="font-size:13px;color:#5d6d7e;">状态</label>
                <select id="od-status" class="select" style="width:110px;">
                    <option value=""           ${statusVal===''           ?'selected':''}>全部</option>
                    <option value="DRAFT"      ${statusVal==='DRAFT'      ?'selected':''}>草稿</option>
                    <option value="COMMITTED"  ${statusVal==='COMMITTED'  ?'selected':''}>已提交</option>
                    <option value="CANCELLED"  ${statusVal==='CANCELLED'  ?'selected':''}>已取消</option>
                </select>
```

完整工具栏插入位置示意:
```html
                <input id="od-op" class="input input-sm" placeholder="操作员(精确)" value="${escapeHtml(opVal)}" style="width:140px;">
                <label style="font-size:13px;color:#5d6d7e;">状态</label>
                <select id="od-status" class="select" style="width:110px;">
                    <option value=""           ${statusVal===''           ?'selected':''}>全部</option>
                    <option value="DRAFT"      ${statusVal==='DRAFT'      ?'selected':''}>草稿</option>
                    <option value="COMMITTED"  ${statusVal==='COMMITTED'  ?'selected':''}>已提交</option>
                    <option value="CANCELLED"  ${statusVal==='CANCELLED'  ?'selected':''}>已取消</option>
                </select>
                <button class="btn btn-sm btn-primary" onclick="searchOrders()">查询</button>
                <button class="btn btn-sm" onclick="resetOrderFilter()">重置</button>
```

- [ ] **Step 3: 静态自检**

IDE 里 grep `od-status`,确认:
- `views.js` 内有 1 处 `<select id="od-status">`
- `app.js` 内有 1 处 `document.getElementById('od-status')`

预期:两个文件各 1 处命中。

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/js/views.js
git commit -m "feat(web): 出入库管理工具栏加状态筛选下拉"
```

---

### Task 3: docker compose 重建后端 + 启动

**Files:** 不改代码,仅操作部署。

- [ ] **Step 1: 重建后端镜像**

```bash
cd E:/Code/SimpleWMS
docker compose build --no-cache backend
```

预期:`Successfully built` 或 `exporting layers done`。耗时通常 30-60s。

- [ ] **Step 2: 重启 backend 容器**

```bash
docker compose up -d backend
```

预期:`Container simplewms-backend  ...  Started` 或 `Up X seconds`。

- [ ] **Step 3: 健康检查**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/dashboard/stats
```

预期:`401`(未登录)或 `200`(已登录)都说明服务起来了。**关键是不能 404 / 502 / connection refused**。

- [ ] **Step 4: 静态资源已更新**

```bash
curl -s http://localhost:8080/static/js/app.js | grep -n "status=COMMITTED" | head -5
```

预期:至少 2 行命中(`loadInbound` + `loadOutbound`)。

```bash
curl -s http://localhost:8080/static/js/views.js | grep -n 'id="od-status"' | head -5
```

预期:1 行命中。

---

### Task 4: 浏览器冒烟验证

**Files:** 不改代码,走 UI 验证。

**前置:** docker backend 已启动并健康。

- [ ] **Step 1: 打开 Web,登录 admin**

浏览器访问 `http://localhost:8080`(假设)→ 登录页 → 输入 `admin` / `admin@123` → 登录成功 → 仪表盘。

- [ ] **Step 2: 验证「入库登记」卡片只显示已提交单**

导航到「入库登记」。观察「最近入库单(最近 10 条)」表格:
- 全部行的「状态」列应均为 **已提交**(绿底 pill)。
- **不应出现**「草稿」或「已取消」pill。

- [ ] **Step 3: 验证 DRAFT 单详情仍可打开**

打开浏览器 DevTools Console,执行:
```js
(async () => {
    const d = await fetch('/api/inbound/orders?status=DRAFT&pageSize=5', { credentials:'include' }).then(r=>r.json());
    console.table(d.list);
})();
```

取 list 中第一行的 `orderNo`,在 UI 里手动模拟点详情(测试环境若没提供入口,可临时把 `loadInbound` 的 `status=COMMITTED` 改成 `status=ALL` 验证 1 次,然后改回):
- 进入详情 modal 后,底部 **必须** 看到「提交入库」+「取消」按钮(因为 status==='DRAFT')。

验证完后:**别忘了把临时改回 `status=COMMITTED`**。

- [ ] **Step 4: 验证「出库登记」卡片只显示已提交单**

导航到「出库登记」。观察「最近出库单(最近 10 条)」表格:
- 全部行的「状态」应为 已提交(出库本身没有 DRAFT 流程,实际基本都是 COMMITTED)。

- [ ] **Step 5: 验证「出入库管理」默认 + 状态筛选下拉**

导航到「出入库管理」。
- 工具栏应有新的「状态」下拉,默认显示「已提交」。
- 表格行应只列已提交单。
- 把下拉切到「草稿」→ 点「查询」→ 表格刷新,可能为空(若没有草稿单)或列出 DRAFT 单。
- 把下拉切到「已取消」→ 点「查询」→ 同上。
- 把下拉切到「全部」→ 点「查询」→ 列出所有状态的单子(应能看到「草稿」「已提交」pill)。
- 点「重置」→ 下拉回到「已提交」(不是「全部」),表格刷新。

- [ ] **Step 6: 验证详情页只读**

在「出入库管理」任意点一行「查看」→ 详情 modal 打开:
- 底部 **不应出现**「提交入库」/「取消」按钮(只显示「关闭」)。

---

### Task 5: 提交 + 推送

- [ ] **Step 1: 暂存所有改动**

```bash
cd E:/Code/SimpleWMS
git status
```

预期:`modified: src/main/resources/static/js/app.js` + `modified: src/main/resources/static/js/views.js`。

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/static/js/app.js src/main/resources/static/js/views.js
git commit -m "feat(web): 入库/出库列表默认只显示已提交单 + 出入库管理状态筛选下拉"
```

- [ ] **Step 3: 推送到远程**

```bash
git push origin main
```

预期:`To http://192.168.11.70:8418/fjlysdos/SimpleWMS`,无报错。

---

## Self-Review

**1. Spec 覆盖:**
- 「最近入库/出库单」卡片只显示已提交单 → Task 1 Step 2/3
- 「入库登记」卡片保留全部状态 + 提交/取消入口 → Task 1 Step 2 不动 + Task 4 Step 3 验证(已存在代码不需改)
- 「出入库管理」默认 COMMITTED → Task 1 Step 1 + Task 1 Step 4
- 「出入库管理」状态筛选下拉 → Task 2
- 「出入库管理」详情页只读 → 不需改(本来就不渲染按钮),Task 4 Step 6 验证
- 不改后端 → Task 3 不触碰 Java 文件
- 不改 PDA → 整计划不碰 `pda-android/`

**2. 占位符扫描:** 无 TBD / TODO / "实现细节"。每个 Step 都给出确切代码/命令。

**3. 类型一致性:**
- `state.orderFilter.status` 在 4 处定义统一为字符串字面量(`'COMMITTED'` / `''` / `'DRAFT'` / `'CANCELLED'`)。
- `od-status` DOM ID 在 `views.js` 创建 + `app.js` 读取,两边一致。
- 查询字符串 `status=COMMITTED` 拼写一致。