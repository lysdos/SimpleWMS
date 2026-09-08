/* ============================================
 *  app.js  -  Hash 路由 + 视图装配 + 事件
 * ============================================ */

const state = {
    products: [],
    productsKw: '',
    suppliers: [],
    supplierKw: '',
    locations: [],
    locationKw: '',
    inventoryKw: '',
    inventoryLoc: '',
    inventoryStatus: 'IN_STOCK',
    inventoryPage: 1,
    stockCheckFilter: { beginDate: '', endDate: '', status: '' },
    stockCheckPage: 1,
    orderType: 'IN',
    orderFilter: { beginDate: '', endDate: '', keyword: '', operator: '' },
    orderPage: 1,
    currentRole: null,
    currentUser: null
};

/* ---------- 路由 ---------- */
const routes = {
    dashboard: loadDashboard,
    products : loadProducts,
    inbound  : loadInbound,
    outbound : loadOutbound,
    orders   : loadOrders,
    users    : loadUsers,
    suppliers: loadSuppliers,
    locations: loadLocations,
    inventory: loadInventory,
    'stock-checks': loadStockChecks
};

function go(route) {
    if (location.hash !== '#/' + route) {
        location.hash = '#/' + route;
        return;  // hashchange 会再触发一次
    }
    (routes[route] || loadDashboard)();
}

window.addEventListener('hashchange', () => {
    const r = (location.hash || '#/dashboard').replace('#/', '');
    setActive(r);
    (routes[r] || loadDashboard)();
});

function setActive(route) {
    document.querySelectorAll('.nav-item').forEach(el => {
        el.classList.toggle('active', el.dataset.route === route);
    });
    // 基础管理父节点在子项命中时也带 active
    const parent = document.querySelector('.nav-group');
    if (parent) {
        const childRoutes = Array.from(parent.querySelectorAll('.nav-item[data-route]'))
                                 .map(a => a.dataset.route);
        parent.classList.toggle('open', childRoutes.includes(route));
    }
}

/* ---------- 各视图加载 ---------- */
async function loadDashboard() {
    setActive('dashboard');
    const stats = await api.get('/api/dashboard/stats');
    document.getElementById('view').innerHTML = Views.dashboard(stats);
    // 加载趋势图(并行,不阻塞主流程;失败静默)
    Promise.all([
        api.get('/api/dashboard/inventory-trend?days=30').catch(() => []),
        api.get('/api/dashboard/outbound-trend?days=30').catch(() => [])
    ]).then(([inv, out]) => {
        renderLineChart('chart-inventory-trend',
            (inv || []).map(r => ({ date: r.date, value: r.cumulative })),
            { color: '#3498db', fillColor: 'rgba(52,152,219,0.12)' });
        renderLineChart('chart-outbound-trend',
            (out || []).map(r => ({ date: r.date, value: r.quantity })),
            { color: '#e67e22', fillColor: 'rgba(230,126,34,0.14)' });
    });
}

async function loadProducts() {
    setActive('products');
    const products = await api.get('/api/products' + (state.productsKw
        ? '?keyword=' + encodeURIComponent(state.productsKw) : ''));
    state.products = products;
    document.getElementById('view').innerHTML = Views.products(products, state.productsKw);
}

async function loadInbound() {
    setActive('inbound');
    state.orderType = 'IN';   // 同步类型,「查看」按钮 viewOrderDetail 用得到
    await ensureProducts();
    let recent = [];
    try {
        const d = await api.get('/api/inbound/orders?pageSize=10');
        recent = d.list || [];
    } catch (e) {}
    document.getElementById('view').innerHTML = Views.inbound(state.products, recent);
}

async function loadOutbound() {
    setActive('outbound');
    state.orderType = 'OUT';  // 同步类型,「查看」按钮 viewOrderDetail 用得到
    await ensureProducts();
    let recent = [];
    try {
        const d = await api.get('/api/outbound/orders?pageSize=10');
        recent = d.list || [];
    } catch (e) {}
    document.getElementById('view').innerHTML = Views.outbound(state.products, recent);
}

async function ensureProducts() {
    if (state.products.length === 0) {
        state.products = await api.get('/api/products');
    }
}

async function loadSuppliers() {
    setActive('suppliers');
    state.suppliers = await api.get('/api/suppliers' + (state.supplierKw ? '?keyword=' + encodeURIComponent(state.supplierKw) : ''));
    document.getElementById('view').innerHTML = Views.suppliers(state.suppliers);
}

async function loadLocations() {
    setActive('locations');
    state.locations = await api.get('/api/locations' + (state.locationKw ? '?keyword=' + encodeURIComponent(state.locationKw) : ''));
    document.getElementById('view').innerHTML = Views.locations(state.locations);
}

async function loadInventory() {
    setActive('inventory');
    await ensureLocations();
    updateInventoryExportHref();   // 同步导出按钮过滤参数
    const qs = new URLSearchParams();
    if (state.inventoryKw)     qs.set('sku', state.inventoryKw);
    if (state.inventoryLoc)    qs.set('location', state.inventoryLoc);
    if (state.inventoryStatus) qs.set('status', state.inventoryStatus);
    qs.set('page', String(state.inventoryPage || 1));
    qs.set('pageSize', '20');
    const data = await api.get('/api/inventory/items?' + qs.toString());
    document.getElementById('view').innerHTML = Views.inventory(data, state, state.locations);
    // innerHTML 重写后再更新一次,确保新按钮 href 也带过滤
    updateInventoryExportHref();
}

/* ---------- 库存盘点 ---------- */
async function loadStockChecks() {
    setActive('stock-checks');
    const f = state.stockCheckFilter || {};
    const qs = new URLSearchParams();
    if (f.beginDate) qs.set('beginDate', f.beginDate);
    if (f.endDate)   qs.set('endDate',   f.endDate);
    if (f.status)    qs.set('status',    f.status);
    qs.set('page', String(state.stockCheckPage || 1));
    qs.set('pageSize', '20');
    try {
        const data = await api.get('/api/stock-checks?' + qs.toString());
        document.getElementById('view').innerHTML = Views.stockChecks(data, f);
    } catch (e) {
        toast(e.message, 'error');
    }
}

function searchStockChecks() {
    state.stockCheckFilter = {
        beginDate: document.getElementById('sc-begin').value || '',
        endDate  : document.getElementById('sc-end').value   || '',
        status   : document.getElementById('sc-status').value || ''
    };
    state.stockCheckPage = 1;
    loadStockChecks();
}
function resetStockCheckFilter() {
    state.stockCheckFilter = { beginDate: '', endDate: '', status: '' };
    state.stockCheckPage = 1;
    loadStockChecks();
}
function gotoStockCheckPage(p) {
    state.stockCheckPage = Math.max(1, p);
    loadStockChecks();
}

function openNewStockCheckModal() {
    showModal('新建盘点单', Views.newStockCheckForm(), async () => {
        const scope    = document.getElementById('nsc-scope').value;
        const keyword  = document.getElementById('nsc-keyword').value.trim();
        const remark   = document.getElementById('nsc-remark').value.trim();
        if (scope !== 'ALL' && !keyword) {
            toast('非「全部」模式必须输入关键字', 'error');
            return false;
        }
        try {
            const c = await api.post('/api/stock-checks', { scope, keyword, remark });
            toast(`盘点单 ${c.checkNo} 已创建,共 ${c.totalCount} 条明细`, 'success');
            state.stockCheckPage = 1;
            await loadStockChecks();
            // 直接打开详情
            openStockCheckDetail(c.id);
        } catch (e) { toast(e.message, 'error'); return false; }
    });
    // scope change 显示/隐藏 keyword 输入
    const sel = document.getElementById('nsc-scope');
    const kw = document.getElementById('nsc-keyword');
    const kwLabel = document.getElementById('nsc-keyword-label');
    function sync() {
        const isAll = sel.value === 'ALL';
        kw.disabled = isAll;
        if (isAll) { kw.value = ''; kwLabel.textContent = '关键字'; }
        else if (sel.value === 'SKU') kwLabel.textContent = 'SKU';
        else if (sel.value === 'LOCATION') kwLabel.textContent = '库位编码';
    }
    sel.addEventListener('change', sync);
    sync();
}

/* ---------- 入库单 → 每件条码展示 ---------- */
async function viewInboundOrderBarcodes(orderId, sku, productName) {
    try {
        const items = await api.get(`/api/inventory/items/by-inbound/${orderId}`);
        // 过滤该 SKU 的(同一入库单可能有多种商品)
        const filtered = items.filter(it => it.sku === sku);
        if (filtered.length === 0) {
            toast('该行商品未找到对应的库存单元', 'error');
            return;
        }
        // 网格展示:每个单元一张条码
        const cards = filtered.map(it => `
            <div style="display:inline-block;border:1px solid #e1e4e8;padding:10px;margin:6px;border-radius:6px;background:#fff;text-align:center;width:240px;">
                <img src="/api/inventory/items/${it.id}/barcode.png" alt="${escapeHtml(it.code)}"
                     style="max-width:100%;height:auto;display:block;margin:0 auto;" />
                <div style="font-family:monospace;font-size:13px;letter-spacing:1px;margin-top:6px;color:#2c3e50;">
                    ${escapeHtml(it.code)}
                </div>
                <div style="font-size:11px;color:#8492a6;margin-top:2px;">
                    ${escapeHtml(it.locationCode || '-')} · 状态:${statusLabel(it.status)}
                </div>
                <a href="/api/inventory/items/${it.id}/barcode.png" download="${escapeHtml(it.code)}.png"
                   style="display:inline-block;margin-top:6px;font-size:12px;color:#3498db;text-decoration:none;">
                   ⬇ 下载
                </a>
            </div>
        `).join('');
        showModal(`${productName} · ${filtered.length} 件条码`, `
            <div style="background:#f4f6f9;padding:8px 12px;border-radius:4px;margin-bottom:12px;font-size:13px;color:#5d6d7e;">
                SKU <strong>${escapeHtml(sku)}</strong> · 入库单 ID=${orderId} · 共 <strong>${filtered.length}</strong> 个独立 24 位编码
            </div>
            <div style="max-height:60vh;overflow-y:auto;">${cards}</div>
        `);
        // 把"确定"按钮换成"关闭"
        const ok = document.getElementById('modal-ok');
        const newOk = ok.cloneNode(true);
        newOk.textContent = '关闭';
        ok.parentNode.replaceChild(newOk, ok);
    } catch (e) {
        toast(e.message, 'error');
    }
}

async function printAllInboundBarcodes(orderId) {
    try {
        const items = await api.get(`/api/inventory/items/by-inbound/${orderId}`);
        if (items.length === 0) {
            toast('该入库单暂无库存单元', 'error');
            return;
        }
        // 新窗口打印,布局 A4 每页多张
        const w = window.open('', '_blank');
        const css = `
            body { font-family: -apple-system, "Microsoft YaHei", sans-serif; padding: 16px; background:#fff; }
            h2 { margin:0 0 12px 0; font-size:18px; color:#2c3e50; }
            .meta { font-size:12px; color:#7f8c8d; margin-bottom:18px; }
            .grid { display:grid; grid-template-columns: repeat(3, 1fr); gap:12px; }
            .card { border:1px solid #e1e4e8; padding:10px; text-align:center; border-radius:6px; page-break-inside: avoid; }
            .card img { max-width:100%; height:auto; }
            .code { font-family:monospace; font-size:12px; letter-spacing:1px; margin-top:6px; }
            @media print { body { padding: 8px; } }
        `;
        const cards = items.map(it => `
            <div class="card">
                <img src="${window.location.origin}/api/inventory/items/${it.id}/barcode.png" alt="${escapeHtml(it.code)}" />
                <div class="code">${escapeHtml(it.code)}</div>
                <div style="font-size:11px;color:#7f8c8d;">${escapeHtml(it.sku)} · ${escapeHtml(it.locationCode || '-')}</div>
            </div>`).join('');
        w.document.write(`
            <!doctype html><html><head><meta charset="utf-8"><title>入库单 ${orderId} 条码</title>
            <style>${css}</style></head><body>
                <h2>入库单 #${orderId} 条码标签</h2>
                <div class="meta">共 ${items.length} 个独立 24 位编码 · 打印时间:${new Date().toLocaleString()}</div>
                <div class="grid">${cards}</div>
                <script>window.onload = () => setTimeout(() => window.print(), 300);<\/script>
            </body></html>
        `);
        w.document.close();
    } catch (e) {
        toast(e.message, 'error');
    }
}

/* ---------- 出库单 → 展示消耗的条码 ---------- */
async function viewOutboundOrderBarcodes(orderId, sku, productName) {
    try {
        const items = await api.get(`/api/inventory/items/by-outbound/${orderId}`);
        // 过滤该 SKU 的(同一出库单可能有多种商品)
        const filtered = items.filter(it => it.sku === sku);
        if (filtered.length === 0) {
            toast('该行商品未找到对应的库存单元(可能为老数据)', 'error');
            return;
        }
        const cards = filtered.map(it => `
            <div style="display:inline-block;border:1px solid #e1e4e8;padding:10px;margin:6px;border-radius:6px;background:#fff;text-align:center;width:240px;">
                <img src="/api/inventory/items/${it.id}/barcode.png" alt="${escapeHtml(it.code)}"
                     style="max-width:100%;height:auto;display:block;margin:0 auto;" />
                <div style="font-family:monospace;font-size:13px;letter-spacing:1px;margin-top:6px;color:#2c3e50;">
                    ${escapeHtml(it.code)}
                </div>
                <div style="font-size:11px;color:#8492a6;margin-top:2px;">
                    ${escapeHtml(it.locationCode || '-')} · 状态:${statusLabel(it.status)}
                </div>
                <a href="/api/inventory/items/${it.id}/barcode.png" download="${escapeHtml(it.code)}.png"
                   style="display:inline-block;margin-top:6px;font-size:12px;color:#3498db;text-decoration:none;">
                   ⬇ 下载
                </a>
            </div>
        `).join('');
        showModal(`${productName} · 出库 ${filtered.length} 件`, `
            <div style="background:#f4f6f9;padding:8px 12px;border-radius:4px;margin-bottom:12px;font-size:13px;color:#5d6d7e;">
                SKU <strong>${escapeHtml(sku)}</strong> · 出库单 ID=${orderId} · 共消耗 <strong>${filtered.length}</strong> 个独立 24 位编码
            </div>
            <div style="max-height:60vh;overflow-y:auto;">${cards}</div>
        `);
        const ok = document.getElementById('modal-ok');
        const newOk = ok.cloneNode(true);
        newOk.textContent = '关闭';
        ok.parentNode.replaceChild(newOk, ok);
    } catch (e) {
        toast(e.message, 'error');
    }
}

async function printAllOutboundBarcodes(orderId) {
    try {
        const items = await api.get(`/api/inventory/items/by-outbound/${orderId}`);
        if (items.length === 0) {
            toast('该出库单暂无消耗的库存单元', 'error');
            return;
        }
        const w = window.open('', '_blank');
        const css = `
            body { font-family: -apple-system, "Microsoft YaHei", sans-serif; padding: 16px; background:#fff; }
            h2 { margin:0 0 12px 0; font-size:18px; color:#2c3e50; }
            .meta { font-size:12px; color:#7f8c8d; margin-bottom:18px; }
            .grid { display:grid; grid-template-columns: repeat(3, 1fr); gap:12px; }
            .card { border:1px solid #e1e4e8; padding:10px; text-align:center; border-radius:6px; page-break-inside: avoid; }
            .card img { max-width:100%; height:auto; }
            .code { font-family:monospace; font-size:12px; letter-spacing:1px; margin-top:6px; }
            .strike { color:#c0392b; font-size:11px; }
            @media print { body { padding: 8px; } }
        `;
        const cards = items.map(it => `
            <div class="card">
                <img src="${window.location.origin}/api/inventory/items/${it.id}/barcode.png" alt="${escapeHtml(it.code)}" />
                <div class="code">${escapeHtml(it.code)}</div>
                <div style="font-size:11px;color:#7f8c8d;">${escapeHtml(it.sku)} · ${escapeHtml(it.locationCode || '-')}</div>
                <div class="strike">[已出库]</div>
            </div>`).join('');
        w.document.write(`
            <!doctype html><html><head><meta charset="utf-8"><title>出库单 ${orderId} 条码</title>
            <style>${css}</style></head><body>
                <h2>出库单 #${orderId} 条码标签(已消耗)</h2>
                <div class="meta">共 ${items.length} 个独立 24 位编码 · 打印时间:${new Date().toLocaleString()}</div>
                <div class="grid">${cards}</div>
                <script>window.onload = () => setTimeout(() => window.print(), 300);<\/script>
            </body></html>
        `);
        w.document.close();
    } catch (e) {
        toast(e.message, 'error');
    }
}

function statusLabel(s) {
    if (!s) return '-';
    switch (s) {
        case 'IN_STOCK':    return '在库';
        case 'OUT':         return '已出库';
        case 'SCRAPPED':    return '已报废';
        case 'TRANSFERRED': return '已调拨';
        default: return s;
    }
}

async function openStockCheckDetail(id) {
    try {
        const c = await api.get('/api/stock-checks/' + id);
        showModal(`盘点单 ${c.checkNo}`, Views.stockCheckDetail(c), async () => {
            // 提交按钮
            if (c.status === 'DRAFT' && c.countedCount === c.totalCount) {
                try {
                    const updated = await api.post(`/api/stock-checks/${c.id}/commit`);
                    toast(`已提交,差异 ${updated.diffCount} 条`, 'success');
                    await loadStockChecks();
                } catch (e) { toast(e.message, 'error'); return false; }
            } else if (c.status === 'DRAFT') {
                toast(`还有 ${c.totalCount - c.countedCount} 条未填实盘数`, 'error');
                return false;
            } else {
                closeModal();
            }
        });
        // 给"导出"按钮挂事件
        const exp = document.getElementById('sc-export-btn');
        if (exp) exp.onclick = () => window.open(`/api/stock-checks/${c.id}/export.csv`, '_blank');
        // 给"取消"按钮(草稿状态)挂事件
        const cancelBtn = document.getElementById('sc-cancel-btn');
        if (cancelBtn) cancelBtn.onclick = async () => {
            try {
                await api.post(`/api/stock-checks/${c.id}/cancel`);
                toast('已取消', 'success');
                await loadStockChecks();
            } catch (e) { toast(e.message, 'error'); }
        };
        // 给每个明细行的"保存实盘"按钮挂事件
        c.items.forEach(it => {
            const btn = document.getElementById('sci-save-' + it.id);
            if (!btn) return;
            btn.onclick = async () => {
                const qtyEl  = document.getElementById('sci-qty-'  + it.id);
                const remEl  = document.getElementById('sci-rem-'  + it.id);
                const qty    = qtyEl ? Number(qtyEl.value) : 0;
                const remark = remEl ? remEl.value.trim() : '';
                if (!Number.isFinite(qty) || qty < 0) {
                    toast('实盘数必须 ≥ 0', 'error'); return;
                }
                try {
                    await api.post(`/api/stock-checks/${c.id}/items/${it.id}/count`, { countedQty: qty, remark });
                    toast('已保存', 'success');
                    await openStockCheckDetail(c.id);  // 刷新
                } catch (e) { toast(e.message, 'error'); }
            };
        });
        // 弹窗底部按钮动态文案
        const ok = document.getElementById('modal-ok');
        const newOk = ok.cloneNode(true);
        if (c.status === 'DRAFT') {
            newOk.textContent = (c.countedCount === c.totalCount) ? '提交盘点' : '关闭';
        } else {
            newOk.textContent = '关闭';
        }
        ok.parentNode.replaceChild(newOk, ok);
    } catch (e) {
        toast(e.message, 'error');
    }
}

async function loadUsers() {
    setActive('users');
    const html = await UserAdminView.render();
    document.getElementById('view').innerHTML = html;
}

/* ---------- 出入库单(列表 + 详情) ---------- */
async function loadOrders() {
    setActive('orders');
    const t = state.orderType || 'IN';
    const f = state.orderFilter || {};
    const qs = new URLSearchParams();
    if (f.beginDate) qs.set('beginDate', f.beginDate);
    if (f.endDate)   qs.set('endDate',   f.endDate);
    if (f.keyword)   qs.set('keyword',   f.keyword);
    if (f.operator)  qs.set('operator',  f.operator);
    qs.set('page', String(state.orderPage || 1));
    qs.set('pageSize', '20');
    const url = (t === 'IN' ? '/api/inbound/orders' : '/api/outbound/orders') + '?' + qs.toString();
    const data = await api.get(url);
    document.getElementById('view').innerHTML = Views.orders(t, data, f);
}

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
function gotoOrderPage(p) {
    state.orderPage = Math.max(1, p);
    loadOrders();
}

async function viewOrderDetail(orderNo) {
    const t = state.orderType || 'IN';
    const base = t === 'IN' ? '/api/inbound' : '/api/outbound';
    // 防止快速重复点击导致 modal 抖动 / 重复渲染
    if (state._viewingOrder === orderNo) return;
    state._viewingOrder = orderNo;
    try {
        // 通过 orderNo 反查 id
        const data = await api.get(`${base}/orders?keyword=${encodeURIComponent(orderNo)}&pageSize=1`);
        const hit = (data.list || []).find(o => o.orderNo === orderNo);
        if (!hit) { toast('未找到单据', 'error'); return; }
        const full = await api.get(`${base}/orders/${hit.id}`);
        if (!full || !full.id) { toast('单据数据为空', 'error'); return; }
        // 异步加载该单据关联的库存单元(每件 24 位编码),按 sku 聚合,用于 modal 内联展示
        const inventoryPath = t === 'IN'
            ? `/api/inventory/items/by-inbound/${hit.id}`
            : `/api/inventory/items/by-outbound/${hit.id}`;
        try {
            const items = await api.get(inventoryPath);
            const map = {};
            for (const it of items) {
                if (!map[it.sku]) map[it.sku] = [];
                map[it.sku].push(it);
            }
            full.barcodesBySku = map;
        } catch (_) {
            full.barcodesBySku = {};
        }
        openOrderDetailModal(t, full);
    } catch (e) { toast(e.message, 'error'); }
    finally { state._viewingOrder = null; }
}

function openOrderDetailModal(type, o) {
    const title = (type === 'IN' ? '入库单 ' : '出库单 ') + (o.orderNo || '');
    const exportUrl = (type === 'IN' ? '/api/inbound' : '/api/outbound') + `/orders/${o.id}/export.csv`;
    document.getElementById('modal-title').textContent = title;
    const inner = type === 'IN' ? Views.inboundOrderModal(o) : Views.outboundOrderModal(o);
    document.getElementById('modal-body').innerHTML = inner;
    document.getElementById('modal-mask').hidden = false;
    const t = document.getElementById('order-print-time');
    if (t) t.textContent = new Date().toLocaleString();
    const ok = document.getElementById('modal-ok');
    const newOk = ok.cloneNode(true);
    newOk.textContent = '关闭';
    ok.parentNode.replaceChild(newOk, ok);
    newOk.addEventListener('click', closeModal);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
}

/* ---------- 搜索 ---------- */
function searchProducts() {
    state.productsKw = document.getElementById('kw-product').value.trim();
    loadProducts();
}

/* ---------- 商品新增/编辑/删除 ---------- */
async function newProduct() {
    await ensureSuppliers();
    await ensureLocations();
    showModal('新增商品', Views.productForm({}, state.suppliers, state.locations), async () => {
        const body = readProductForm();
        if (!body) return false;
        try {
            await api.post('/api/products', body);
            toast('新增成功', 'success');
            state.products = []; // 强制刷新
            await loadProducts();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}

async function editProduct(id) {
    try {
        const p = await api.get('/api/products/' + id);
        await ensureSuppliers();
        await ensureLocations();
        showModal('编辑商品 #' + id, Views.productForm(p, state.suppliers, state.locations), async () => {
            const body = readProductForm();
            if (!body) return false;
            try {
                await api.put('/api/products/' + id, body);
                toast('保存成功', 'success');
                state.products = [];
                await loadProducts();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    } catch (e) { toast(e.message, 'error'); }
}

function delProduct(id, name) {
    confirmModal(`确认删除商品「${name}」?该操作不可恢复。`, async () => {
        try {
            await api.delete('/api/products/' + id);
            toast('已删除', 'success');
            state.products = [];
            await loadProducts();
        } catch (e) { toast(e.message, 'error'); }
    });
}

async function ensureSuppliers() {
    if (!state.suppliers || state.suppliers.length === 0) {
        state.suppliers = await api.get('/api/suppliers');
    }
}

async function ensureLocations() {
    if (!state.locations || state.locations.length === 0) {
        state.locations = await api.get('/api/locations');
    }
}

function readProductForm() {
    const id    = document.getElementById('pf-id').value;
    const sku   = document.getElementById('pf-sku').value.trim();
    const name  = document.getElementById('pf-name').value.trim();
    const locEl = document.getElementById('pf-loc');
    const location = locEl ? locEl.value.trim() : '';
    if (!sku)  { toast('SKU 不能为空', 'error'); return null; }
    if (!name) { toast('名称不能为空', 'error'); return null; }
    if (!location) { toast('请选择库位', 'error'); return null; }

    // 收集选中的 supplier id
    const sel = document.getElementById('pf-suppliers');
    const def = document.getElementById('pf-default-supplier').value;
    const selectedIds = sel ? Array.from(sel.selectedOptions).map(o => Number(o.value)).filter(Boolean) : [];
    // 容错:默认供应商如果不在多选里,自动加入,避免"只改默认供应商下拉、多选没动"时丢数据
    const defNum = def ? Number(def) : null;
    const finalIds = selectedIds.slice();
    if (defNum && !finalIds.includes(defNum)) {
        finalIds.push(defNum);
    }
    const suppliers = finalIds.map(sid => ({
        supplierId: sid,
        isDefault: sid === defNum
    }));

    return {
        sku, name,
        category : document.getElementById('pf-cat').value.trim(),
        unit     : document.getElementById('pf-unit').value.trim(),
        location,
        quantity : Number(document.getElementById('pf-qty').value || 0),
        unitPrice: Number(document.getElementById('pf-price').value || 0),
        lowStock : Number(document.getElementById('pf-low').value || 10),
        remark   : document.getElementById('pf-remark').value.trim(),
        suppliers
    };
}

/* ---------- 供应商管理 ---------- */
function searchSuppliers() {
    state.supplierKw = document.getElementById('sup-kw').value.trim();
    loadSuppliers();
}
async function newSupplier() {
    showModal('新增供应商', Views.supplierForm({}), async () => {
        const body = readSupplierForm();
        if (!body) return false;
        try {
            await api.post('/api/suppliers', body);
            toast('已新增', 'success');
            await loadSuppliers();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}
async function editSupplier(id) {
    try {
        const s = await api.get('/api/suppliers/' + id);
        showModal('编辑供应商 #' + id, Views.supplierForm(s), async () => {
            const body = readSupplierForm();
            if (!body) return false;
            try {
                await api.put('/api/suppliers/' + id, body);
                toast('已保存', 'success');
                await loadSuppliers();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    } catch (e) { toast(e.message, 'error'); }
}
function delSupplier(id, name) {
    confirmModal(`确认删除供应商「${name}」?该操作不可恢复。`, async () => {
        try {
            await api.delete('/api/suppliers/' + id);
            toast('已删除', 'success');
            await loadSuppliers();
        } catch (e) { toast(e.message, 'error'); }
    });
}
function readSupplierForm() {
    const name = document.getElementById('sf-name').value.trim();
    if (!name) { toast('名称不能为空', 'error'); return null; }
    return {
        name,
        contact: document.getElementById('sf-contact').value.trim(),
        phone  : document.getElementById('sf-phone').value.trim(),
        email  : document.getElementById('sf-email').value.trim(),
        address: document.getElementById('sf-address').value.trim(),
        remark : document.getElementById('sf-remark').value.trim(),
        enabled: document.getElementById('sf-enabled').value === 'true'
    };
}

/* ---------- 库位管理 ---------- */
function searchLocations() {
    state.locationKw = document.getElementById('loc-kw').value.trim();
    loadLocations();
}
async function newLocation() {
    showModal('新增库位', Views.locationForm({}), async () => {
        const body = readLocationForm();
        if (!body) return false;
        try {
            await api.post('/api/locations', body);
            toast('已新增', 'success');
            state.locations = []; // 强制刷新(确保商品表单拉得到最新)
            await loadLocations();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}
async function editLocation(id) {
    try {
        const l = await api.get('/api/locations/' + id);
        showModal('编辑库位 #' + id, Views.locationForm(l), async () => {
            const body = readLocationForm();
            if (!body) return false;
            try {
                await api.put('/api/locations/' + id, body);
                toast('已保存', 'success');
                state.locations = [];
                await loadLocations();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    } catch (e) { toast(e.message, 'error'); }
}
function delLocation(id, code) {
    confirmModal(`确认删除库位「${code}」?该操作不可恢复。\n\n注意:商品的库位字段仍会保留文本,但下拉里不再出现。`, async () => {
        try {
            await api.del('/api/locations/' + id);
            toast('已删除', 'success');
            state.locations = [];
            await loadLocations();
        } catch (e) { toast(e.message, 'error'); }
    });
}
function readLocationForm() {
    const code = document.getElementById('lf-code').value.trim();
    if (!code) { toast('库位编码不能为空', 'error'); return null; }
    return {
        code,
        name   : document.getElementById('lf-name').value.trim(),
        remark : document.getElementById('lf-remark').value.trim(),
        enabled: document.getElementById('lf-enabled').value === 'true'
    };
}

/* ---------- 库存管理 ---------- */
function searchInventory() {
    state.inventoryKw     = document.getElementById('inv-kw').value.trim();
    state.inventoryLoc    = document.getElementById('inv-loc').value;
    state.inventoryStatus = document.getElementById('inv-status').value || 'IN_STOCK';
    state.inventoryPage = 1;
    updateInventoryExportHref();
    loadInventory();
}
function resetInventoryFilter() {
    state.inventoryKw = ''; state.inventoryLoc = ''; state.inventoryStatus = 'IN_STOCK';
    state.inventoryPage = 1;
    updateInventoryExportHref();
    loadInventory();
}
/** 让导出按钮的 href 与当前过滤同步 */
function updateInventoryExportHref() {
    const a = document.getElementById('inv-export-btn');
    if (!a) return;
    const qs = new URLSearchParams();
    if (state.inventoryKw)     qs.set('sku', state.inventoryKw);
    if (state.inventoryLoc)    qs.set('location', state.inventoryLoc);
    if (state.inventoryStatus) qs.set('status', state.inventoryStatus);
    const q = qs.toString();
    a.href = '/api/inventory/items/export.csv' + (q ? '?' + q : '');
}
function gotoInventoryPage(p) {
    state.inventoryPage = Math.max(1, p);
    loadInventory();
}

function viewBarcode(id, code) {
    showModal('条码 / 24 位编码', `
        <div style="text-align:center;padding:16px 0;">
            <img src="/api/inventory/items/${id}/barcode.png" alt="${escapeHtml(code)}"
                 style="max-width:100%;border:1px solid #eee;padding:8px;background:#fff;" />
            <div style="margin-top:12px;font-family:monospace;font-size:18px;letter-spacing:1px;color:#2c3e50;">
                ${escapeHtml(code)}
            </div>
            <div style="margin-top:6px;color:#8492a6;font-size:12px;">
                长按图片可保存 / 分享到打印机
            </div>
        </div>
    `);
    // 把"确定"按钮换成"关闭"
    const ok = document.getElementById('modal-ok');
    const newOk = ok.cloneNode(true);
    newOk.textContent = '关闭';
    ok.parentNode.replaceChild(newOk, ok);
    newOk.addEventListener('click', closeModal);
}

function scrapInventory(id, code) {
    showModal('报废库存单元', `
        <p style="margin:0 0 12px;font-size:13px;color:#5d6d7e;">
            将报废以下库存单元,该操作不可恢复。
        </p>
        <div style="background:#f4f6f9;padding:8px 12px;border-radius:4px;font-family:monospace;margin-bottom:12px;">
            ${escapeHtml(code)}
        </div>
        <label style="display:block;font-size:13px;color:#5d6d7e;margin-bottom:4px;">报废原因(可选)</label>
        <textarea id="scrap-remark" class="input" rows="3" placeholder="例如:过期 / 损坏 / 测试报废 ..."></textarea>
    `, async () => {
        try {
            await api.post(`/api/inventory/items/${id}/scrap`, {
                remark: (document.getElementById('scrap-remark') || {}).value || ''
            });
            toast('已报废', 'success');
            await loadInventory();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}

async function transferInventory(id, code, currentLoc) {
    await ensureLocations();
    const locOptions = state.locations
        .filter(l => l.enabled !== false && l.code !== currentLoc)
        .map(l => `<option value="${escapeHtml(l.code)}">${escapeHtml(l.code)}${l.name && l.name !== l.code ? ' / ' + escapeHtml(l.name) : ''}</option>`)
        .join('');
    showModal('调拨库存单元', `
        <p style="margin:0 0 12px;font-size:13px;color:#5d6d7e;">
            将以下库存单元从「${escapeHtml(currentLoc)}」调拨到新库位。
        </p>
        <div style="background:#f4f6f9;padding:8px 12px;border-radius:4px;font-family:monospace;margin-bottom:12px;">
            ${escapeHtml(code)}
        </div>
        <label style="display:block;font-size:13px;color:#5d6d7e;margin-bottom:4px;">目标库位<span style="color:#c0392b;">*</span></label>
        <select id="transfer-to" class="select" style="width:100%;">
            ${locOptions || '<option value="" disabled>暂无可用库位,请先到「基础管理 → 库位管理」添加</option>'}
        </select>
    `, async () => {
        const sel = document.getElementById('transfer-to');
        const to = sel ? sel.value : '';
        if (!to) { toast('请选择目标库位', 'error'); return false; }
        try {
            await api.post(`/api/inventory/items/${id}/transfer`, { toLocation: to });
            toast('已调拨', 'success');
            await loadInventory();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}

/* ---------- 新增入库/出库单 弹窗 ---------- */
async function openNewOrderModal(type) {
    await ensureProducts();
    await ensureSuppliers();   // 入库单下拉框需要供应商列表
    document.getElementById('modal-title').textContent = type === 'IN' ? '新增入库单' : '新增出库单';
    document.getElementById('modal-body').innerHTML = Views.newOrderModal(type, state.products, state.suppliers);
    document.getElementById('modal-mask').hidden = false;
    const ok = document.getElementById('modal-ok');
    const newOk = ok.cloneNode(true);
    newOk.textContent = '提交';
    ok.parentNode.replaceChild(newOk, ok);
    newOk.addEventListener('click', submitNewOrder);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
    // 绑定商品 change 事件 + 初次预填默认供应商(仅入库有 .nw-supplier)
    document.querySelectorAll('#nw-rows .nw-product').forEach(sel => {
        sel.addEventListener('change', onNewOrderProductChange);
        if (sel.value) onNewOrderProductChange.call(sel);
    });
    renumberNewOrderRows();
}

function onNewOrderProductChange() {
    const opt = this.selectedOptions[0];
    if (!opt) return;
    const def = opt.getAttribute('data-default') || '';
    const tr = this.closest('tr');
    const sup = tr.querySelector('.nw-supplier');   // 入库有,出库为 null
    if (sup && !sup.value) sup.value = def;
}

function addNewOrderRow() {
    const tbody = document.getElementById('nw-rows');
    if (!tbody) return;
    const first = tbody.querySelector('tr.nw-row');
    const clone = first.cloneNode(true);
    clone.querySelector('.nw-qty').value = '';
    const sup = clone.querySelector('.nw-supplier');
    if (sup) sup.value = '';
    clone.querySelector('.nw-row-remark').value = '';
    tbody.appendChild(clone);
    clone.querySelector('.nw-product').addEventListener('change', onNewOrderProductChange);
    renumberNewOrderRows();
}
function removeNewOrderRow(btn) {
    const tbody = document.getElementById('nw-rows');
    if (!tbody) return;
    const tr = btn.closest('tr');
    if (!tr) return;
    if (tbody.querySelectorAll('tr.nw-row').length <= 1) {
        tr.querySelector('.nw-qty').value = '';
        const sup = tr.querySelector('.nw-supplier');
        if (sup) sup.value = '';
        tr.querySelector('.nw-row-remark').value = '';
        return;
    }
    tr.remove();
    renumberNewOrderRows();
}
function renumberNewOrderRows() {
    const rows = document.querySelectorAll('#nw-rows tr.nw-row');
    rows.forEach((r, i) => {
        const idx = r.querySelector('.row-idx');
        if (idx) idx.textContent = (i + 1);
    });
}

function readNewOrderItems() {
    const rows = document.querySelectorAll('#nw-rows tr.nw-row');
    const items = [];
    let hasError = false;
    rows.forEach((tr) => {
        const productId = Number(tr.querySelector('.nw-product').value);
        const qtyRaw    = tr.querySelector('.nw-qty').value;
        const quantity  = Number(qtyRaw);
        const supEl     = tr.querySelector('.nw-supplier');   // 出库单该元素不存在
        const supplier  = supEl ? supEl.value.trim() : '';
        const rowRemark = tr.querySelector('.nw-row-remark').value.trim();
        if (!productId && !qtyRaw) return;
        if (!productId) { toast('存在未选择商品的行', 'error'); hasError = true; return; }
        if (!Number.isFinite(quantity) || quantity <= 0) {
            toast('数量必须大于 0', 'error'); hasError = true; return;
        }
        items.push({ productId, quantity, supplier, rowRemark });
    });
    return hasError ? null : items;
}

async function submitNewOrder() {
    const type = document.getElementById('nw-type').value;
    const items = readNewOrderItems();
    if (items == null) return;
    if (items.length === 0) { toast('请至少填写一条明细', 'error'); return; }

    const body = {
        externalNo: (document.getElementById('nw-external-no') || {}).value?.trim() || '',
        remark:     (document.getElementById('nw-remark') || {}).value?.trim() || '',
        items
    };
    if (type === 'OUT') {
        body.customer = (document.getElementById('nw-customer') || {}).value?.trim() || '';
        const need = new Map();
        for (const it of items) need.set(it.productId, (need.get(it.productId) || 0) + it.quantity);
        for (const [pid, qty] of need) {
            const p = state.products.find(x => x.id === pid);
            if (!p) { toast('商品不存在 id=' + pid, 'error'); return; }
            if (p.quantity < qty) {
                toast(`库存不足:SKU ${p.sku} 当前 ${p.quantity},需要 ${qty}`, 'error');
                return;
            }
        }
    }

    const url = type === 'IN' ? '/api/inbound/batch' : '/api/outbound/batch';
    try {
        const order = await api.post(url, body);
        toast(`${type === 'IN' ? '入库' : '出库'}成功:共 ${order.itemCount} 行 / ${order.totalQuantity} 件`, 'success');
        state.products = [];
        await ensureProducts();
        closeModal();
        if (type === 'IN') openInboundOrderModal(order);
        else              openOutboundOrderModal(order);
        if (type === 'IN') await loadInbound(); else await loadOutbound();
    } catch (e) { toast(e.message, 'error'); }
}

function openInboundOrderModal(order) {
    document.getElementById('modal-title').textContent = '入库单 ' + (order.orderNo || '');
    document.getElementById('modal-body').innerHTML = Views.inboundOrderModal(order);
    document.getElementById('modal-mask').hidden = false;
    const t = document.getElementById('order-print-time');
    if (t) t.textContent = new Date().toLocaleString();
    const ok = document.getElementById('modal-ok');
    const newOk = ok.cloneNode(true);
    newOk.textContent = '关闭';
    ok.parentNode.replaceChild(newOk, ok);
    newOk.addEventListener('click', closeModal);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
}

function openOutboundOrderModal(order) {
    document.getElementById('modal-title').textContent = '出库单 ' + (order.orderNo || '');
    document.getElementById('modal-body').innerHTML = Views.outboundOrderModal(order);
    document.getElementById('modal-mask').hidden = false;
    const t = document.getElementById('order-print-time');
    if (t) t.textContent = new Date().toLocaleString();
    const ok = document.getElementById('modal-ok');
    const newOk = ok.cloneNode(true);
    newOk.textContent = '关闭';
    ok.parentNode.replaceChild(newOk, ok);
    newOk.addEventListener('click', closeModal);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
}

function printInboundOrder() { window.print(); }
function printOutboundOrder() { window.print(); }

/* ---------- 启动 ---------- */
window.addEventListener('DOMContentLoaded', async () => {
    if (!Auth.getToken()) { location.href = '/login.html'; return; }
    try {
        const me = await api.get('/api/auth/me');
        state.currentUser = me.username;
        state.currentRole = me.role;
        document.getElementById('me-name').textContent = me.username + ' (' + me.role + ')';
        const logoutBtn = document.getElementById('logout-btn');
        logoutBtn.hidden = false;
        logoutBtn.addEventListener('click', () => Auth.logout());

        // 按角色隐藏导航项(含下拉容器)
        document.querySelectorAll('[data-roles]').forEach(el => {
            const roles = el.dataset.roles.split(',');
            if (!roles.includes(me.role)) el.hidden = true;
        });

        // 点击"基础管理"父项切换下拉(open 类 — 桌面:hover 自动展开,移动/键盘需点击)
        const basicParent = document.getElementById('nav-basic');
        if (basicParent) {
            basicParent.addEventListener('click', (e) => {
                e.preventDefault();
                basicParent.parentElement.classList.toggle('open');
            });
        }

        const r = (location.hash || '#/dashboard').replace('#/', '');
        setActive(r);
        (routes[r] || loadDashboard)();
    } catch (e) {
        // api._handle 已跳 /login
    }
});