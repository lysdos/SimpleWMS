/* ============================================
 *  app.js  -  Hash 路由 + 视图装配 + 事件
 *  已接入 i18n(语言切换会自动重渲染当前页)
 * ============================================ */

const state = {
    products: [],
    productsKw: '',
    productsSupplier: '',
    suppliers: [],
    supplierKw: '',
    supplierStatus: '',
    locations: [],
    locationKw: '',
    locationStatus: '',
    userKw: '',
    userRole: '',
    userStatus: '',
    userDateFrom: '',
    userDateTo: '',
    inventoryKw: '',
    inventoryLoc: '',
    inventoryStatus: 'IN_STOCK',
    inventoryExpiryStatus: '',
    inventoryExpiryFrom: '',
    inventoryExpiryTo: '',
    inventoryPage: 1,
    stockCheckFilter: { beginDate: '', endDate: '', status: '' },
    stockCheckPage: 1,
    orderType: 'ALL',
    orderFilter: { beginDate: '', endDate: '', keyword: '', createdBy: '', status: '' },
    orderPage: 1,
    orderPageSize: 10,
    inboundCardStatus: 'ALL',
    inboundRecentPage: 1,
    inboundRecentPageSize: 10,
    outboundCardStatus: 'ALL',
    outboundRecentPage: 1,
    outboundRecentPageSize: 10,
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
        return;
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
    Promise.all([
        api.get('/api/dashboard/inventory-trend?days=30').catch(() => []),
        api.get('/api/dashboard/outbound-trend?days=30').catch(() => [])
    ]).then(([inv, out]) => {
        renderLineChart('chart-inventory-trend',
            (inv || []).map(r => ({ date: r.date, value: r.cumulative })),
            { color: '#2563eb', fillColor: 'rgba(37,99,235,0.10)' });
        renderLineChart('chart-outbound-trend',
            (out || []).map(r => ({ date: r.date, value: r.quantity })),
            { color: '#f59e0b', fillColor: 'rgba(245,158,11,0.12)' });
    });
    renderExpiringList();
}

async function renderExpiringList() {
    const el = document.getElementById('expiring-list');
    if (!el) return;
    try {
        const [expiring, expired] = await Promise.all([
            api.get('/api/dashboard/expiring?kind=EXPIRING&limit=10').catch(() => []),
            api.get('/api/dashboard/expiring?kind=EXPIRED&limit=10').catch(() => [])
        ]);
        el.innerHTML = Views.expiringList(expiring || [], expired || []);
    } catch (e) {
        el.innerHTML = `<span style="color:var(--c-text-3);">—</span>`;
    }
}

async function loadProducts() {
    setActive('products');
    await ensureSuppliers();
    const qs = new URLSearchParams();
    if (state.productsKw) qs.set('keyword', state.productsKw);
    if (state.productsSupplier) qs.set('supplier', state.productsSupplier);
    const products = await api.get('/api/products' + (qs.toString() ? '?' + qs.toString() : ''));
    state.products = products;
    document.getElementById('view').innerHTML = Views.products(products, state.suppliers, state.productsKw, state.productsSupplier);
}

async function loadInbound() {
    setActive('inbound');
    await ensureProducts();
    document.getElementById('view').innerHTML = Views.inbound(state.products, []);
    await renderInboundRecent();
}

async function loadOutbound() {
    setActive('outbound');
    await ensureProducts();
    document.getElementById('view').innerHTML = Views.outbound(state.products, []);
    await renderOutboundRecent();
}

async function renderInboundRecent() {
    const st = state.inboundCardStatus || 'ALL';
    const page = state.inboundRecentPage || 1;
    const pageSize = state.inboundRecentPageSize || 10;
    const url = '/api/inbound/orders?status=' + encodeURIComponent(st) + '&page=' + page + '&pageSize=' + pageSize;
    try {
        const d = await api.get(url);
        const rows = (d && d.list) || [];
        const total = (d && d.total) || 0;
        const pages = Math.max(1, Math.ceil(total / pageSize));
        const tb = document.getElementById('inbound-recent-tbody');
        if (tb) tb.innerHTML = Views.inboundRecentRows(rows);
        const pager = document.getElementById('inbound-recent-pager');
        if (pager) pager.innerHTML = Views.recentPager('inbound', page, pages, total, pageSize);
        const title = document.getElementById('inbound-recent-title');
        if (title) title.textContent = t('inbound.recent_title');
        Views.applyCardTabs('inbound', st);
    } catch (e) {}
}

async function renderOutboundRecent() {
    const st = state.outboundCardStatus || 'ALL';
    const page = state.outboundRecentPage || 1;
    const pageSize = state.outboundRecentPageSize || 10;
    const url = '/api/outbound/orders?status=' + encodeURIComponent(st) + '&page=' + page + '&pageSize=' + pageSize;
    try {
        const d = await api.get(url);
        const rows = (d && d.list) || [];
        const total = (d && d.total) || 0;
        const pages = Math.max(1, Math.ceil(total / pageSize));
        const tb = document.getElementById('outbound-recent-tbody');
        if (tb) tb.innerHTML = Views.outboundRecentRows(rows);
        const pager = document.getElementById('outbound-recent-pager');
        if (pager) pager.innerHTML = Views.recentPager('outbound', page, pages, total, pageSize);
        const title = document.getElementById('outbound-recent-title');
        if (title) title.textContent = t('outbound.recent_title');
        Views.applyCardTabs('outbound', st);
    } catch (e) {}
}

function setInboundCardStatus(st) {
    state.inboundCardStatus = st;
    renderInboundRecent();
}

function setOutboundCardStatus(st) {
    state.outboundCardStatus = st;
    state.outboundRecentPage = 1;
    renderOutboundRecent();
}

function gotoInboundRecentPage(p) {
    state.inboundRecentPage = Math.max(1, p);
    renderInboundRecent();
}

function gotoOutboundRecentPage(p) {
    state.outboundRecentPage = Math.max(1, p);
    renderOutboundRecent();
}

function changeInboundRecentPageSize(el) {
    state.inboundRecentPageSize = parseInt(el.value, 10) || 10;
    state.inboundRecentPage = 1;
    renderInboundRecent();
}

function changeOutboundRecentPageSize(el) {
    state.outboundRecentPageSize = parseInt(el.value, 10) || 10;
    state.outboundRecentPage = 1;
    renderOutboundRecent();
}

async function ensureProducts() {
    if (state.products.length === 0) {
        state.products = await api.get('/api/products');
    }
}

async function loadSuppliers() {
    setActive('suppliers');
    const qs = new URLSearchParams();
    if (state.supplierKw) qs.set('keyword', state.supplierKw);
    if (state.supplierStatus) qs.set('status', state.supplierStatus);
    state.suppliers = await api.get('/api/suppliers' + (qs.toString() ? '?' + qs.toString() : ''));
    document.getElementById('view').innerHTML = Views.suppliers(state.suppliers, state.supplierStatus);
}

async function loadLocations() {
    setActive('locations');
    const qs = new URLSearchParams();
    if (state.locationKw) qs.set('keyword', state.locationKw);
    if (state.locationStatus) qs.set('status', state.locationStatus);
    state.locations = await api.get('/api/locations' + (qs.toString() ? '?' + qs.toString() : ''));
    document.getElementById('view').innerHTML = Views.locations(state.locations, state.locationStatus);
}

async function loadInventory() {
    setActive('inventory');
    await ensureLocations();
    updateInventoryExportHref();
    const qs = new URLSearchParams();
    if (state.inventoryKw)     qs.set('sku', state.inventoryKw);
    if (state.inventoryLoc)    qs.set('location', state.inventoryLoc);
    if (state.inventoryStatus) qs.set('status', state.inventoryStatus);
    if (state.inventoryExpiryStatus) qs.set('expiryStatus', state.inventoryExpiryStatus);
    if (state.inventoryExpiryFrom)   qs.set('expiryFrom', state.inventoryExpiryFrom);
    if (state.inventoryExpiryTo)     qs.set('expiryTo', state.inventoryExpiryTo);
    qs.set('page', String(state.inventoryPage || 1));
    qs.set('pageSize', '20');
    const data = await api.get('/api/inventory/items?' + qs.toString());
    document.getElementById('view').innerHTML = Views.inventory(data, state, state.locations);
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
    showModal(t('stock_check.modal.title.new'), Views.newStockCheckForm(), async () => {
        const scope   = (document.querySelector('input[name="nsc-scope"]:checked') || {}).value || 'ALL';
        const kwInput = document.getElementById('nsc-keyword');
        const sel     = document.getElementById('nsc-location-select');
        let keyword = '';
        if (scope === 'SKU')      keyword = (kwInput.value || '').trim();
        else if (scope === 'LOCATION') keyword = sel ? sel.value : '';
        const remark  = document.getElementById('nsc-remark').value.trim();

        if (scope === 'SKU' && !keyword) {
            toast(t('stock_check.err.sku_empty'), 'error');
            return false;
        }
        if (scope === 'LOCATION' && !keyword) {
            toast(t('stock_check.err.loc_empty'), 'error');
            return false;
        }
        try {
            const c = await api.post('/api/stock-checks', { scope, keyword, remark });
            toast(t('stock_check.toast.created', { n: c.checkNo, m: c.totalCount }), 'success');
            state.stockCheckPage = 1;
            await loadStockChecks();
            openStockCheckDetail(c.id);
        } catch (e) { toast(e.message, 'error'); return false; }
    });

    // 模式 radio 联动:ALL 隐藏输入,SKU 显示输入框,LOCATION 显示库位下拉
    const radios   = document.querySelectorAll('input[name="nsc-scope"]');
    const wrap     = document.getElementById('nsc-keyword-wrap');
    const lbl      = document.getElementById('nsc-keyword-label');
    const kwInput  = document.getElementById('nsc-keyword');

    function paintScopeUi() {
        const cur = (document.querySelector('input[name="nsc-scope"]:checked') || {}).value || 'ALL';
        // 卡片高亮
        document.querySelectorAll('.nsc-scope-opt').forEach(el => {
            const v = el.querySelector('input').value;
            el.style.borderColor = (v === cur) ? 'var(--c-primary)' : 'var(--divider)';
            el.style.background  = (v === cur) ? '#E8F0FF' : 'transparent';
        });
        if (cur === 'ALL') {
            wrap.style.display = 'none';
            kwInput.value = '';
        } else if (cur === 'SKU') {
            wrap.style.display = '';
            // 文字输入框
            wrap.innerHTML = `
                <label id="nsc-keyword-label">${t('stock_check.scope.sku')}</label>
                <input id="nsc-keyword" class="input" placeholder="${t('stock_check.kw.sku')}">
            `;
        } else if (cur === 'LOCATION') {
            wrap.style.display = '';
            // 库位下拉
            api.get('/api/locations').then(locs => {
                const opts = (locs || []).map(l =>
                    `<option value="${escapeHtml(l.code)}">${escapeHtml(l.code)} · ${escapeHtml(l.name)}</option>`).join('');
                wrap.innerHTML = `
                    <label id="nsc-keyword-label">${t('stock_check.scope.location')}</label>
                    <select id="nsc-location-select" class="select" style="width:100%;">
                        <option value="">-- ${t('stock_check.kw.location')} --</option>
                        ${opts}
                    </select>
                `;
            }).catch(e => toast(e.message, 'error'));
        }
    }
    radios.forEach(r => r.addEventListener('change', paintScopeUi));
    paintScopeUi();
}

/* ---------- 入库单 → 每件条码展示 ---------- */
async function viewInboundOrderBarcodes(orderId, sku, productName) {
    try {
        const items = await api.get(`/api/inventory/items/by-inbound/${orderId}`);
        const filtered = items.filter(it => it.sku === sku);
        if (filtered.length === 0) {
            toast(t('err.no_inventory_for_line'), 'error');
            return;
        }
        const cards = filtered.map(it => `
            <div style="display:inline-block;border:1px solid var(--c-border);padding:10px;margin:6px;border-radius:var(--r-md);background:#fff;text-align:center;width:240px;">
                <img src="/api/inventory/items/${it.id}/barcode.png" alt="${escapeHtml(it.code)}"
                     style="max-width:100%;height:auto;display:block;margin:0 auto;" />
                <div style="font-family:var(--font-mono);font-size:13px;letter-spacing:1px;margin-top:6px;color:var(--c-text);">
                    ${escapeHtml(it.code)}
                </div>
                <div style="font-size:11px;color:var(--c-text-3);margin-top:2px;">
                    ${escapeHtml(it.locationCode || '-')} · ${t('barcode.status')}:${statusLabel(it.status)}
                </div>
                <a href="/api/inventory/items/${it.id}/barcode.png" download="${escapeHtml(it.code)}.png"
                   style="display:inline-block;margin-top:6px;font-size:12px;color:var(--c-brand);text-decoration:none;">
                   ⬇ ${t('common.export')}
                </a>
            </div>
        `).join('');
        showModal(`${productName} · ${filtered.length} ${t('outbound_order.barcode_label')}`, `
            <div style="background:var(--c-bg-soft);padding:8px 12px;border-radius:var(--r-sm);margin-bottom:12px;font-size:13px;color:var(--c-text-2);">
                ${t('inbound_order.barcode.sku')} <strong>${escapeHtml(sku)}</strong> · ${t('inbound.col.order_no')} ID=${orderId} · ${t('inbound_order.barcode.count_total')} <strong>${filtered.length}</strong> ${t('inbound_order.barcode.unit_codes')}
            </div>
            <div style="max-height:60vh;overflow-y:auto;">${cards}</div>
        `);
        freshModalOk(t('common.close'));
    } catch (e) {
        toast(e.message, 'error');
    }
}

async function printAllInboundBarcodes(orderId) {
    try {
        const items = await api.get(`/api/inventory/items/by-inbound/${orderId}`);
        if (items.length === 0) {
            toast(t('err.no_inventory_inbound'), 'error');
            return;
        }
        const w = window.open('', '_blank');
        const css = `
            body { font-family: -apple-system, "Microsoft YaHei", sans-serif; padding: 16px; background:#fff; }
            h2 { margin:0 0 12px 0; font-size:18px; color:#1f2937; }
            .meta { font-size:12px; color:#6b7280; margin-bottom:18px; }
            .grid { display:grid; grid-template-columns: repeat(3, 1fr); gap:12px; }
            .card { border:1px solid #e5e7eb; padding:10px; text-align:center; border-radius:6px; page-break-inside: avoid; }
            .card img { max-width:100%; height:auto; }
            .code { font-family:monospace; font-size:12px; letter-spacing:1px; margin-top:6px; }
            @media print { body { padding: 8px; } }
        `;
        const cards = items.map(it => `
            <div class="card">
                <img src="${window.location.origin}/api/inventory/items/${it.id}/barcode.png" alt="${escapeHtml(it.code)}" />
                <div class="code">${escapeHtml(it.code)}</div>
                <div style="font-size:11px;color:#6b7280;">${escapeHtml(it.sku)} · ${escapeHtml(it.locationCode || '-')}</div>
            </div>`).join('');
        w.document.write(`
            <!doctype html><html><head><meta charset="utf-8"><title>${t('inbound_order.print_title', { id: orderId })}</title>
            <style>${css}</style></head><body>
                <h2>${t('inbound_order.print_subtitle')}</h2>
                <div class="meta">${t('outbound_order.barcode_count', { n: items.length })} · ${t('inbound_order.print_printed_at')}:${new Date().toLocaleString()}</div>
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
        const filtered = items.filter(it => it.sku === sku);
        if (filtered.length === 0) {
            toast(t('err.no_inventory_for_out_line'), 'error');
            return;
        }
        const cards = filtered.map(it => `
            <div style="display:inline-block;border:1px solid var(--c-border);padding:10px;margin:6px;border-radius:var(--r-md);background:#fff;text-align:center;width:240px;">
                <img src="/api/inventory/items/${it.id}/barcode.png" alt="${escapeHtml(it.code)}"
                     style="max-width:100%;height:auto;display:block;margin:0 auto;" />
                <div style="font-family:var(--font-mono);font-size:13px;letter-spacing:1px;margin-top:6px;color:var(--c-text);">
                    ${escapeHtml(it.code)}
                </div>
                <div style="font-size:11px;color:var(--c-text-3);margin-top:2px;">
                    ${escapeHtml(it.locationCode || '-')} · ${t('barcode.status')}:${statusLabel(it.status)}
                </div>
                <a href="/api/inventory/items/${it.id}/barcode.png" download="${escapeHtml(it.code)}.png"
                   style="display:inline-block;margin-top:6px;font-size:12px;color:var(--c-brand);text-decoration:none;">
                   ⬇ ${t('common.export')}
                </a>
            </div>
        `).join('');
        showModal(`${productName} · ${t('outbound_order.title')} ${filtered.length}`, `
            <div style="background:var(--c-bg-soft);padding:8px 12px;border-radius:var(--r-sm);margin-bottom:12px;font-size:13px;color:var(--c-text-2);">
                ${t('inbound_order.barcode.sku')} <strong>${escapeHtml(sku)}</strong> · ${t('outbound.col.order_no')} ID=${orderId} · ${t('common.total')} <strong>${filtered.length}</strong> ${t('inbound_order.barcode.unit_codes')}
            </div>
            <div style="max-height:60vh;overflow-y:auto;">${cards}</div>
        `);
        freshModalOk(t('common.close'));
    } catch (e) {
        toast(e.message, 'error');
    }
}

async function printAllOutboundBarcodes(orderId) {
    try {
        const items = await api.get(`/api/inventory/items/by-outbound/${orderId}`);
        if (items.length === 0) {
            toast(t('err.no_inventory_outbound'), 'error');
            return;
        }
        const w = window.open('', '_blank');
        const css = `
            body { font-family: -apple-system, "Microsoft YaHei", sans-serif; padding: 16px; background:#fff; }
            h2 { margin:0 0 12px 0; font-size:18px; color:#1f2937; }
            .meta { font-size:12px; color:#6b7280; margin-bottom:18px; }
            .grid { display:grid; grid-template-columns: repeat(3, 1fr); gap:12px; }
            .card { border:1px solid #e5e7eb; padding:10px; text-align:center; border-radius:6px; page-break-inside: avoid; }
            .card img { max-width:100%; height:auto; }
            .code { font-family:monospace; font-size:12px; letter-spacing:1px; margin-top:6px; }
            .strike { color:#ef4444; font-size:11px; }
            @media print { body { padding: 8px; } }
        `;
        const cards = items.map(it => `
            <div class="card">
                <img src="${window.location.origin}/api/inventory/items/${it.id}/barcode.png" alt="${escapeHtml(it.code)}" />
                <div class="code">${escapeHtml(it.code)}</div>
                <div style="font-size:11px;color:#6b7280;">${escapeHtml(it.sku)} · ${escapeHtml(it.locationCode || '-')}</div>
                <div class="strike">${t('outbound_order.strike')}</div>
            </div>`).join('');
        w.document.write(`
            <!doctype html><html><head><meta charset="utf-8"><title>${t('outbound_order.print_title', { id: orderId })}</title>
            <style>${css}</style></head><body>
                <h2>${t('outbound_order.print_title', { id: orderId })}</h2>
                <div class="meta">${t('outbound_order.barcode_count', { n: items.length })} · ${t('inbound_order.print_printed_at')}:${new Date().toLocaleString()}</div>
                <div class="grid">${cards}</div>
                <script>window.onload = () => setTimeout(() => window.print(), 300);<\/script>
            </body></html>
        `);
        w.document.close();
    } catch (e) {
        toast(e.message, 'error');
    }
}

/* ---------- 库存盘点详情 ---------- */
async function openStockCheckDetail(id) {
    try {
        const c = await api.get('/api/stock-checks/' + id);
        showModal(t('stock_check.modal.title.detail') + ' ' + c.checkNo, Views.stockCheckDetail(c));
        const modal = document.querySelector('.modal');
        if (modal) modal.classList.add('modal-wide');
        // 底部按钮行是 sticky 贴底的,去掉 body 底部内边距才能贴到真正的底边
        const mbody = document.getElementById('modal-body');
        if (mbody) mbody.style.paddingBottom = '0';
        // 导出菜单:点击按钮向上弹出(全部/盘盈/盘亏),选中后带 filter 下载
        const expBtn = document.getElementById('sc-export-btn');
        const expMenu = document.getElementById('sc-export-menu');
        if (expBtn && expMenu) {
            expBtn.onclick = (ev) => {
                ev.stopPropagation();
                if (!expMenu.hidden) { expMenu.hidden = true; return; }
                expMenu.hidden = false;
                // 明细很少时 modal-body 很矮,向上弹出会被裁剪 → 上方放不下就向下弹
                const btnRect = expBtn.getBoundingClientRect();
                const bodyRect = document.getElementById('modal-body').getBoundingClientRect();
                const menuH = expMenu.getBoundingClientRect().height;
                expMenu.classList.toggle('down', (btnRect.top - bodyRect.top) < menuH + 8);
            };
            expMenu.querySelectorAll('button').forEach(b => {
                b.onclick = (ev) => {
                    ev.stopPropagation();
                    expMenu.hidden = true;
                    const f = b.dataset.filter;
                    const fname = `stock-check-${c.checkNo || c.id}${f === 'all' ? '' : f === 'over' ? '-盘盈' : '-盘亏'}.csv`;
                    api.download(`/api/stock-checks/${c.id}/export.csv?filter=${f}`, fname)
                        .catch(e => toast(e.message, 'error'));
                };
            });
            // 点击弹窗其它位置收起菜单
            const mbodyEl = document.getElementById('modal-body');
            if (mbodyEl) mbodyEl.onclick = () => { expMenu.hidden = true; };
        }
        const commitBtn = document.getElementById('sc-commit-btn');
        if (commitBtn) commitBtn.onclick = async () => {
            try {
                const updated = await api.post(`/api/stock-checks/${c.id}/commit`);
                toast(t('stock_check.btn.committed_diff', { n: updated.diffCount }), 'success');
                closeModal();
                await loadStockChecks();
            } catch (e) { toast(e.message, 'error'); }
        };
        const cancelBtn = document.getElementById('sc-cancel-btn');
        if (cancelBtn) cancelBtn.onclick = async () => {
            try {
                await api.post(`/api/stock-checks/${c.id}/cancel`);
                toast(t('common.success.cancel'), 'success');
                closeModal();
                await loadStockChecks();
            } catch (e) { toast(e.message, 'error'); }
        };
        c.items.forEach(it => {
            const btn = document.getElementById('sci-save-' + it.id);
            if (!btn) return;
            btn.onclick = async () => {
                const qtyEl  = document.getElementById('sci-qty-'  + it.id);
                const remEl  = document.getElementById('sci-rem-'  + it.id);
                const qty    = qtyEl ? Number(qtyEl.value) : 0;
                const remark = remEl ? remEl.value.trim() : '';
                if (!Number.isFinite(qty) || qty < 0) {
                    toast(t('stock_check.err.qty_invalid'), 'error'); return;
                }
                try {
                    await api.post(`/api/stock-checks/${c.id}/items/${it.id}/count`, { countedQty: qty, remark });
                    toast(t('stock_check.btn.saved'), 'success');
                    await openStockCheckDetail(c.id);
                } catch (e) { toast(e.message, 'error'); }
            };
        });
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
    const tt = state.orderType || 'ALL';
    const f = state.orderFilter || {};
    const qs = new URLSearchParams();
    if (f.beginDate) qs.set('beginDate', f.beginDate);
    if (f.endDate)   qs.set('endDate',   f.endDate);
    if (f.keyword)   qs.set('keyword',   f.keyword);
    if (f.createdBy)  qs.set('createdBy',  f.createdBy);
    if (f.status)    qs.set('status',    f.status);
    qs.set('page', String(state.orderPage || 1));
    qs.set('pageSize', String(state.orderPageSize || 10));
    const url = (tt === 'ALL' ? '/api/orders' : (tt === 'IN' ? '/api/inbound/orders' : '/api/outbound/orders')) + '?' + qs.toString();
    const data = await api.get(url);
    document.getElementById('view').innerHTML = Views.orders(tt, data, f);
}

function searchOrders() {
    state.orderType  = document.getElementById('od-type').value || 'ALL';
    state.orderFilter = {
        beginDate: document.getElementById('od-begin').value || '',
        endDate:   document.getElementById('od-end').value   || '',
        keyword:   document.getElementById('od-kw').value    || '',
        createdBy:  document.getElementById('od-op').value    || '',
        status:    document.getElementById('od-status').value ?? ''
    };
    state.orderPageSize = parseInt(document.getElementById('od-page-size')?.value, 10) || 10;
    state.orderPage = 1;
    loadOrders();
}
function resetOrderFilter() {
    state.orderType = 'ALL';
    state.orderFilter = { beginDate: '', endDate: '', keyword: '', createdBy: '', status: '' };
    state.orderPage = 1;
    state.orderPageSize = 10;
    loadOrders();
}
function gotoOrderPage(p) {
    state.orderPage = Math.max(1, p);
    loadOrders();
}

/* ---------- 出入库单复选框 & 导出 ---------- */
function toggleAllOrderCbs(master) {
    document.querySelectorAll('.od-cb').forEach(cb => { cb.checked = master.checked; });
    updateExportBtns();
}

function updateExportBtns() {
    const checked = document.querySelectorAll('.od-cb:checked');
    const btn = document.getElementById('btn-export-selected');
    const countEl = document.getElementById('od-selected-count');
    if (btn) btn.disabled = checked.length === 0;
    if (countEl) countEl.textContent = checked.length > 0 ? t('orders.selected_count', { n: checked.length }) : '';
}

function getSelectedOrderIds() {
    return Array.from(document.querySelectorAll('.od-cb:checked')).map(cb => parseInt(cb.dataset.id, 10));
}

async function exportSelectedOrders() {
    const ids = getSelectedOrderIds();
    if (ids.length === 0) return;
    const tt = state.orderType || 'ALL';
    // 按类型分组导出
    const inIds = [], outIds = [];
    document.querySelectorAll('.od-cb:checked').forEach(cb => {
        const id = parseInt(cb.dataset.id, 10);
        if (cb.dataset.type === 'IN') inIds.push(id); else outIds.push(id);
    });
    if (inIds.length > 0) await downloadZip('/api/inbound/orders/export.zip', inIds, 'inbound_orders.zip');
    if (outIds.length > 0) await downloadZip('/api/outbound/orders/export.zip', outIds, 'outbound_orders.zip');
}

async function exportAllOrders() {
    const tt = state.orderType || 'ALL';
    const f = state.orderFilter || {};
    const qs = new URLSearchParams();
    if (f.beginDate) qs.set('beginDate', f.beginDate);
    if (f.endDate)   qs.set('endDate',   f.endDate);
    if (f.keyword)   qs.set('keyword',   f.keyword);
    if (f.createdBy)  qs.set('createdBy',  f.createdBy);
    if (f.status)    qs.set('status',    f.status);
    qs.set('page', '1');
    qs.set('pageSize', '9999');
    try {
        if (tt === 'ALL' || tt === 'IN') {
            const inData = await api.get('/api/inbound/orders?' + qs.toString());
            const inIds = (inData.list || []).map(o => o.id);
            if (inIds.length > 0) await downloadZip('/api/inbound/orders/export.zip', inIds, 'inbound_orders.zip');
        }
        if (tt === 'ALL' || tt === 'OUT') {
            const outData = await api.get('/api/outbound/orders?' + qs.toString());
            const outIds = (outData.list || []).map(o => o.id);
            if (outIds.length > 0) await downloadZip('/api/outbound/orders/export.zip', outIds, 'outbound_orders.zip');
        }
        toast(t('orders.export_done'));
    } catch (e) { toast(e.message, 'error'); }
}

async function downloadZip(url, ids, filename) {
    const resp = await fetch(url, {
        method: 'POST',
        headers: api._hdr(),   // 必须带 Authorization,否则后端 401
        body: JSON.stringify(ids)
    });
    if (resp.status === 401) {
        Auth.clearToken();
        if (!location.pathname.endsWith('/login.html')) location.href = '/login.html';
        throw new Error('未登录');
    }
    if (!resp.ok) throw new Error(await resp.text());
    const blob = await resp.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    URL.revokeObjectURL(a.href);
}

async function editInboundOrder(id) {
    try {
        const full = await api.get('/api/inbound/orders/' + id);
        if (!full || !full.id) { toast(t('err.empty'), 'error'); return; }
        openEditOrderModal('IN', full);
    } catch (e) { toast(e.message, 'error'); }
}

async function editOutboundOrder(id) {
    try {
        const full = await api.get('/api/outbound/orders/' + id);
        if (!full || !full.id) { toast(t('err.empty'), 'error'); return; }
        openEditOrderModal('OUT', full);
    } catch (e) { toast(e.message, 'error'); }
}

function openEditOrderModal(type, order) {
    document.getElementById('modal-title').textContent = t(type === 'IN' ? 'inbound.modal.title.edit' : 'outbound.modal.title.edit');
    document.getElementById('modal-body').innerHTML = Views.editOrderModal(type, order);
    document.getElementById('modal-mask').hidden = false;
    freshModalOk(t('common.save')).addEventListener('click', () => submitEditOrder(type, order.id));
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.remove('modal-wide');
}

async function submitEditOrder(type, orderId) {
    const externalNo = document.getElementById('edit-external-no')?.value || '';
    const remark = document.getElementById('edit-remark')?.value || '';
    const base = type === 'IN' ? '/api/inbound' : '/api/outbound';
    try {
        await api.put(`${base}/orders/${orderId}`, { externalNo, remark });
        toast(t('common.success.save'));
        closeModal();
        // 刷新当前视图:orders 页面或入库/出库登记页
        if (document.getElementById('od-type')) {
            loadOrders();
        } else if (type === 'IN') {
            renderInboundRecent();
        } else {
            renderOutboundRecent();
        }
    } catch (e) { toast(e.message, 'error'); }
}

async function viewOrderDetail(id, typeHint) {
    const tt = typeHint || state.orderType || 'IN';
    const base = tt === 'IN' ? '/api/inbound' : '/api/outbound';
    if (state._viewingOrder === id) return;
    state._viewingOrder = id;
    try {
        // 不再用 orderNo 拼进 inline onclick;按 ID 直接拉详情
        const full = await api.get(`${base}/orders/${id}`);
        if (!full || !full.id) { toast(t('err.empty'), 'error'); return; }
        const inventoryPath = tt === 'IN'
            ? `/api/inventory/items/by-inbound/${id}`
            : `/api/inventory/items/by-outbound/${id}`;
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
        openOrderDetailModal(tt, full);
    } catch (e) { toast(e.message, 'error'); }
    finally { state._viewingOrder = null; }
}

function openOrderDetailModal(type, o) {
    const title = (type === 'IN' ? t('inbound_order.title') + ' ' : t('outbound_order.title') + ' ') + (o.orderNo || '');
    const exportUrl = (type === 'IN' ? '/api/inbound' : '/api/outbound') + `/orders/${o.id}/export.csv`;
    document.getElementById('modal-title').textContent = title;
    const inner = type === 'IN' ? Views.inboundOrderModal(o) : Views.outboundOrderModal(o);
    document.getElementById('modal-body').innerHTML = inner;
    document.getElementById('modal-mask').hidden = false;
    const tm = document.getElementById('order-print-time');
    if (tm) tm.textContent = new Date().toLocaleString();
    freshModalOk(t('common.close')).addEventListener('click', closeModal);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
}

/* 提交入库单 */
async function commitInboundOrder(id, orderNo) {
    if (!confirm(t('inbound.confirm_commit', { n: orderNo }))) return;
    try {
        await api.post(`/api/inbound/orders/${id}/commit`);
        toast(t('inbound.toast.committed', { n: orderNo }));
        closeModal();
        await loadInbound();
    } catch (e) {
        toast(e.message || t('err.network'), 'error');
    }
}

/* 取消入库单 */
async function cancelInboundOrder(id, orderNo) {
    if (!confirm(t('inbound.confirm_cancel', { n: orderNo }))) return;
    try {
        await api.post(`/api/inbound/orders/${id}/cancel`);
        toast(t('inbound.toast.cancelled', { n: orderNo }));
        closeModal();
        await loadInbound();
    } catch (e) {
        toast(e.message || t('err.network'), 'error');
    }
}

/* 提交出库单(只传 ID,显示单号从已加载列表里取;防止 orderNo 被注入 inline onclick) */
async function commitOutboundOrder(id) {
    const o = (state.outboundRecentOrders || []).find(x => x.id === id)
              || (state.outboundOrders || []).find(x => x.id === id);
    const no = o ? o.orderNo : ('#' + id);
    if (!confirm(t('outbound.confirm_commit', { n: no }))) return;
    try {
        await api.post(`/api/outbound/orders/${id}/commit`);
        toast(t('outbound.toast.committed', { n: no }));
        closeModal();
        await loadOutbound();
    } catch (e) {
        toast(e.message || t('err.network'), 'error');
    }
}

/* 取消出库单 */
async function cancelOutboundOrder(id) {
    const o = (state.outboundRecentOrders || []).find(x => x.id === id)
              || (state.outboundOrders || []).find(x => x.id === id);
    const no = o ? o.orderNo : ('#' + id);
    if (!confirm(t('outbound.confirm_cancel', { n: no }))) return;
    try {
        await api.post(`/api/outbound/orders/${id}/cancel`);
        toast(t('outbound.toast.cancelled', { n: no }));
        closeModal();
        await loadOutbound();
    } catch (e) {
        toast(e.message || t('err.network'), 'error');
    }
}

/* ---------- 搜索 ---------- */
function searchProducts() {
    state.productsKw = document.getElementById('kw-product').value.trim();
    state.productsSupplier = document.getElementById('product-supplier-filter')?.value || '';
    loadProducts();
}

/* ---------- 商品新增/编辑/删除 ---------- */
async function newProduct() {
    await ensureSuppliers();
    await ensureLocations();
    showModal(t('product.form.title.new'), Views.productForm({}, state.suppliers, state.locations), async () => {
        const body = readProductForm();
        if (!body) return false;
        try {
            await api.post('/api/products', body);
            toast(t('common.success.create'), 'success');
            state.products = [];
            await loadProducts();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}

async function viewProduct(id) {
    try {
        const p = await api.get('/api/products/' + id);
        showModal(t('product.form.title.view'), Views.productDetail(p), null, true);
    } catch (e) { toast(e.message, 'error'); }
}

async function editProduct(id) {
    try {
        const p = await api.get('/api/products/' + id);
        await ensureSuppliers();
        await ensureLocations();
        showModal(t('product.form.title.edit'), Views.productForm(p, state.suppliers, state.locations), async () => {
            const body = readProductForm();
            if (!body) return false;
            try {
                await api.put('/api/products/' + id, body);
                toast(t('common.success.save'), 'success');
                state.products = [];
                await loadProducts();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    } catch (e) { toast(e.message, 'error'); }
}

function delProduct(id) {
    // 仅传 ID,显示名从已加载的 state 里取(避免把商品名拼进 inline onclick 造成 XSS 逃逸)
    const p = (state.products || []).find(x => x.id === id);
    const label = p ? p.name : ('#' + id);
    confirmModal(`${t('common.confirm_delete')}「${label}」?${t('common.delete_irreversible')}`, async () => {
        try {
            await api.del('/api/products/' + id);
            toast(t('common.success.delete'), 'success');
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
    if (!sku)  { toast(t('product.error.sku_required'), 'error'); return null; }
    if (!name) { toast(t('product.error.name_required'), 'error'); return null; }
    if (!location) { toast(t('product.error.loc_required'), 'error'); return null; }

    const sel = document.getElementById('pf-suppliers');
    const def = document.getElementById('pf-default-supplier').value;
    const selectedIds = sel ? Array.from(sel.selectedOptions).map(o => Number(o.value)).filter(Boolean) : [];
    const defNum = def ? Number(def) : null;
    const finalIds = selectedIds.slice();
    if (defNum && !finalIds.includes(defNum)) {
        finalIds.push(defNum);
    }
    const suppliers = finalIds.map(sid => ({
        supplierId: sid,
        isDefault: sid === defNum
    }));

    const shelfLifeRaw = document.getElementById('pf-shelf-life').value.trim();
    const warnDaysRaw  = document.getElementById('pf-warn-days').value.trim();
    return {
        sku, name,
        category : document.getElementById('pf-cat').value.trim(),
        unit     : document.getElementById('pf-unit').value.trim(),
        location,
        quantity : Number(document.getElementById('pf-qty').value || 0),
        unitPrice: Number(document.getElementById('pf-price').value || 0),
        lowStock : Number(document.getElementById('pf-low').value || 10),
        defaultShelfLifeDays: shelfLifeRaw === '' ? null : Number(shelfLifeRaw),
        expiryWarnDays      : warnDaysRaw  === '' ? null : Number(warnDaysRaw),
        remark   : document.getElementById('pf-remark').value.trim(),
        suppliers
    };
}

/* ---------- 供应商管理 ---------- */
function searchSuppliers() {
    state.supplierKw = document.getElementById('sup-kw').value.trim();
    state.supplierStatus = document.getElementById('sup-status-filter')?.value || '';
    loadSuppliers();
}
async function newSupplier() {
    showModal(t('supplier.form.new'), Views.supplierForm({}), async () => {
        const body = readSupplierForm();
        if (!body) return false;
        try {
            await api.post('/api/suppliers', body);
            toast(t('supplier.toast.created'), 'success');
            await loadSuppliers();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}
async function viewSupplier(id) {
    try {
        const s = await api.get('/api/suppliers/' + id);
        showModal(t('supplier.form.view'), Views.supplierDetail(s), null, true);
    } catch (e) { toast(e.message, 'error'); }
}
async function editSupplier(id) {
    try {
        const s = await api.get('/api/suppliers/' + id);
        showModal(t('supplier.form.edit'), Views.supplierForm(s), async () => {
            const body = readSupplierForm();
            if (!body) return false;
            try {
                await api.put('/api/suppliers/' + id, body);
                toast(t('common.success.save'), 'success');
                await loadSuppliers();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    } catch (e) { toast(e.message, 'error'); }
}
function delSupplier(id) {
    const s = (state.suppliers || []).find(x => x.id === id);
    const label = s ? s.name : ('#' + id);
    confirmModal(`${t('common.confirm_delete')}「${label}」?${t('common.delete_irreversible')}`, async () => {
        try {
            await api.del('/api/suppliers/' + id);
            toast(t('common.success.delete'), 'success');
            await loadSuppliers();
        } catch (e) { toast(e.message, 'error'); }
    });
}
function readSupplierForm() {
    const name = document.getElementById('sf-name').value.trim();
    if (!name) { toast(t('supplier.error.name_required'), 'error'); return null; }
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
    state.locationStatus = document.getElementById('loc-status-filter')?.value || '';
    loadLocations();
}
async function newLocation() {
    showModal(t('location.form.new'), Views.locationForm({}), async () => {
        const body = readLocationForm();
        if (!body) return false;
        try {
            await api.post('/api/locations', body);
            toast(t('supplier.toast.created'), 'success');
            state.locations = [];
            await loadLocations();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}
async function viewLocation(id) {
    try {
        const l = await api.get('/api/locations/' + id);
        showModal(t('location.form.view'), Views.locationDetail(l), null, true);
    } catch (e) { toast(e.message, 'error'); }
}
async function editLocation(id) {
    try {
        const l = await api.get('/api/locations/' + id);
        showModal(t('location.form.edit'), Views.locationForm(l), async () => {
            const body = readLocationForm();
            if (!body) return false;
            try {
                await api.put('/api/locations/' + id, body);
                toast(t('common.success.save'), 'success');
                state.locations = [];
                await loadLocations();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    } catch (e) { toast(e.message, 'error'); }
}
function delLocation(id) {
    const l = (state.locations || []).find(x => x.id === id);
    const label = l ? l.code : ('#' + id);
    confirmModal(`${t('common.confirm_delete')}「${label}」?${t('common.delete_irreversible')}\n\n${t('location.del_tip')}`, async () => {
        try {
            await api.del('/api/locations/' + id);
            toast(t('common.success.delete'), 'success');
            state.locations = [];
            await loadLocations();
        } catch (e) { toast(e.message, 'error'); }
    });
}
function readLocationForm() {
    const code = document.getElementById('lf-code').value.trim();
    if (!code) { toast(t('location.error.code_required'), 'error'); return null; }
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
    state.inventoryExpiryStatus = document.getElementById('inv-expiry').value || '';
    state.inventoryExpiryFrom   = document.getElementById('inv-expiry-from').value || '';
    state.inventoryExpiryTo     = document.getElementById('inv-expiry-to').value || '';
    state.inventoryPage = 1;
    updateInventoryExportHref();
    loadInventory();
}
function resetInventoryFilter() {
    state.inventoryKw = ''; state.inventoryLoc = ''; state.inventoryStatus = 'IN_STOCK';
    state.inventoryExpiryStatus = ''; state.inventoryExpiryFrom = ''; state.inventoryExpiryTo = '';
    state.inventoryPage = 1;
    updateInventoryExportHref();
    loadInventory();
}
function updateInventoryExportHref() {
    const a = document.getElementById('inv-export-btn');
    if (!a) return;
    const qs = new URLSearchParams();
    if (state.inventoryKw)     qs.set('sku', state.inventoryKw);
    if (state.inventoryLoc)    qs.set('location', state.inventoryLoc);
    if (state.inventoryStatus) qs.set('status', state.inventoryStatus);
    if (state.inventoryExpiryStatus) qs.set('expiryStatus', state.inventoryExpiryStatus);
    if (state.inventoryExpiryFrom)   qs.set('expiryFrom', state.inventoryExpiryFrom);
    if (state.inventoryExpiryTo)     qs.set('expiryTo', state.inventoryExpiryTo);
    const q = qs.toString();
    a.href = '/api/inventory/items/export.csv' + (q ? '?' + q : '');
}
function gotoInventoryPage(p) {
    state.inventoryPage = Math.max(1, p);
    loadInventory();
}

function viewBarcode(id, code) {
    showModal(t('inventory.modal.barcode_title'), `
        <div style="text-align:center;padding:16px 0;">
            <img src="/api/inventory/items/${id}/barcode.png" alt="${escapeHtml(code)}"
                 style="max-width:100%;border:1px solid #e5e7eb;padding:8px;background:#fff;border-radius:var(--r-md);" />
            <div style="margin-top:12px;font-family:var(--font-mono);font-size:18px;letter-spacing:1px;color:var(--c-text);">
                ${escapeHtml(code)}
            </div>
            <div style="margin-top:6px;color:var(--c-text-3);font-size:12px;">
                ${t('inventory.modal.barcode_tip')}
            </div>
        </div>
    `);
    freshModalOk(t('common.close')).addEventListener('click', closeModal);
}

function scrapInventory(id, code) {
    showModal(t('inventory.modal.scrap_title'), `
        <p style="margin:0 0 12px;font-size:13px;color:var(--c-text-2);">
            ${t('inventory.modal.scrap_tip')}
        </p>
        <div style="background:var(--c-bg-soft);padding:8px 12px;border-radius:var(--r-sm);font-family:var(--font-mono);margin-bottom:12px;">
            ${escapeHtml(code)}
        </div>
        <label style="display:block;font-size:13px;color:var(--c-text-2);margin-bottom:4px;">${t('inventory.modal.scrap_reason')}</label>
        <textarea id="scrap-remark" class="input" rows="3" placeholder="${t('inventory.modal.scrap_placeholder')}"></textarea>
    `, async () => {
        try {
            await api.post(`/api/inventory/items/${id}/scrap`, {
                remark: (document.getElementById('scrap-remark') || {}).value || ''
            });
            toast(t('inventory.modal.scrap_done'), 'success');
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
    showModal(t('inventory.modal.transfer_title'), `
        <p style="margin:0 0 12px;font-size:13px;color:var(--c-text-2);">
            ${t('inventory.modal.transfer_tip', { from: currentLoc })}
        </p>
        <div style="background:var(--c-bg-soft);padding:8px 12px;border-radius:var(--r-sm);font-family:var(--font-mono);margin-bottom:12px;">
            ${escapeHtml(code)}
        </div>
        <label style="display:block;font-size:13px;color:var(--c-text-2);margin-bottom:4px;">${t('inventory.modal.transfer_target')}<span class="req">*</span></label>
        <select id="transfer-to" class="select" style="width:100%;">
            ${locOptions || `<option value="" disabled>${t('inventory.modal.transfer_empty')}</option>`}
        </select>
    `, async () => {
        const sel = document.getElementById('transfer-to');
        const to = sel ? sel.value : '';
        if (!to) { toast(t('inventory.modal.transfer_pick'), 'error'); return false; }
        try {
            await api.post(`/api/inventory/items/${id}/transfer`, { toLocation: to });
            toast(t('inventory.modal.transfer_done'), 'success');
            await loadInventory();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}

/* ---------- 新增入库/出库单 弹窗 ---------- */
async function openNewOrderModal(type) {
    // 强制拉一次最新 product / supplier(避免下拉里"在库"数字滞后)
    state.products = await api.get('/api/products');
    state.suppliers = await api.get('/api/suppliers');
    document.getElementById('modal-title').textContent = type === 'IN' ? t('inbound.modal.title.new') : t('outbound.modal.title.new');
    document.getElementById('modal-body').innerHTML = Views.newOrderModal(type, state.products, state.suppliers);
    document.getElementById('modal-mask').hidden = false;
    freshModalOk(t('common.submit')).addEventListener('click', submitNewOrder);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
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
    const sup = tr.querySelector('.nw-supplier');
    if (sup && !sup.value) sup.value = def;
    // 入库行:带出商品默认保质期天数(可改)
    const shelf = tr.querySelector('.nw-shelf-life');
    if (shelf && !shelf.value) {
        const dfltShelf = opt.getAttribute('data-shelf-life') || '';
        if (dfltShelf !== '') shelf.value = dfltShelf;
    }
}

function addNewOrderRow() {
    const tbody = document.getElementById('nw-rows');
    if (!tbody) return;
    const first = tbody.querySelector('tr.nw-row');
    const clone = first.cloneNode(true);
    clone.querySelector('.nw-qty').value = '';
    const sup = clone.querySelector('.nw-supplier');
    if (sup) sup.value = '';
    const pd = clone.querySelector('.nw-prod-date');
    if (pd) pd.value = '';
    const shelf = clone.querySelector('.nw-shelf-life');
    if (shelf) shelf.value = '';
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
        const pd = tr.querySelector('.nw-prod-date');
        if (pd) pd.value = '';
        const shelf = tr.querySelector('.nw-shelf-life');
        if (shelf) shelf.value = '';
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
        const supEl     = tr.querySelector('.nw-supplier');
        const supplier  = supEl ? supEl.value.trim() : '';
        const prodDateEl = tr.querySelector('.nw-prod-date');
        const productionDate = prodDateEl ? prodDateEl.value : '';
        const shelfEl = tr.querySelector('.nw-shelf-life');
        const shelfRaw = shelfEl ? shelfEl.value.trim() : '';
        const shelfLifeDays = shelfRaw === '' ? null : Number(shelfRaw);
        const rowRemark = tr.querySelector('.nw-row-remark').value.trim();
        if (!productId && !qtyRaw) return;
        if (!productId) { toast(t('new_order.err.no_product'), 'error'); hasError = true; return; }
        if (!Number.isFinite(quantity) || quantity <= 0) {
            toast(t('new_order.err.qty_invalid'), 'error'); hasError = true; return;
        }
        items.push({
            productId, quantity, supplier, rowRemark,
            productionDate, shelfLifeDays
        });
    });
    return hasError ? null : items;
}

async function submitNewOrder() {
    const type = document.getElementById('nw-type').value;
    const items = readNewOrderItems();
    if (items == null) return false;
    if (items.length === 0) { toast(t('new_order.err.no_items'), 'error'); return true; }

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
            if (!p) { toast(t('new_order.err.product_missing') + ' id=' + pid, 'error'); return true; }
            if (p.quantity < qty) {
                toast(t('new_order.err.stock_low') + `: SKU ${p.sku} ${p.quantity}/${qty}`, 'error');
                return true;
            }
        }
    }

    // 防双击:已提交中则忽略后续点击(showModal 的 keep=true 让按钮在失败时也保留,
    // 但这里再把按钮置 disabled 直到请求完成,避免用户连续点击 2 次产生重复单)。
    const okBtn = document.getElementById('modal-ok');
    if (okBtn && okBtn._inFlight) return true;
    if (okBtn) { okBtn._inFlight = true; okBtn.disabled = true; okBtn.textContent = t('common.submitting'); }

    const url = type === 'IN' ? '/api/inbound/batch' : '/api/outbound/batch';
    try {
        const order = await api.post(url, body);
        const cnt = t('new_order.toast.count_suffix', { a: order.itemCount, b: order.totalQuantity });
        toast(`${type === 'IN' ? t('new_order.toast.in_done') : t('new_order.toast.out_done')}: ${cnt}`, 'success');
        // 成功路径也要复位按钮:之前只在 catch 里复位,导致 disabled 一直留在 DOM 上,
        // 紧接着 open*OrderModal 的 cloneNode 会把它复制给「关闭」按钮,
        // 之后每次开弹窗再层层继承 —— 提交按钮全变灰,只有刷新页面才能恢复。
        if (okBtn) { okBtn._inFlight = false; okBtn.disabled = false; okBtn.textContent = t('common.submit'); }
        state.products = [];
        await ensureProducts();
        if (type === 'IN') openInboundOrderModal(order);
        else              openOutboundOrderModal(order);
        if (type === 'IN') await loadInbound(); else await loadOutbound();
        return false;   // 关闭弹窗
    } catch (e) {
        toast(e.message, 'error');
        // 失败保留弹窗,让用户改正后再提交;恢复按钮
        if (okBtn) { okBtn._inFlight = false; okBtn.disabled = false; okBtn.textContent = t('common.submit'); }
        return true;
    }
}

function openInboundOrderModal(order) {
    document.getElementById('modal-title').textContent = t('inbound_order.title') + ' ' + (order.orderNo || '');
    document.getElementById('modal-body').innerHTML = Views.inboundOrderModal(order);
    document.getElementById('modal-mask').hidden = false;
    const tm = document.getElementById('order-print-time');
    if (tm) tm.textContent = new Date().toLocaleString();
    freshModalOk(t('common.close')).addEventListener('click', closeModal);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
}

function openOutboundOrderModal(order) {
    document.getElementById('modal-title').textContent = t('outbound_order.title') + ' ' + (order.orderNo || '');
    document.getElementById('modal-body').innerHTML = Views.outboundOrderModal(order);
    document.getElementById('modal-mask').hidden = false;
    const tm = document.getElementById('order-print-time');
    if (tm) tm.textContent = new Date().toLocaleString();
    freshModalOk(t('common.close')).addEventListener('click', closeModal);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
}

function printInboundOrder() { window.print(); }
function printOutboundOrder() { window.print(); }

/* ---------- 语言切换 ---------- */
function refreshLangSwitcher() {
    document.querySelectorAll('#lang-switch button[data-lang]').forEach(b => {
        b.classList.toggle('active', b.dataset.lang === I18N.getLang());
    });
}

/* ---------- 启动 ---------- */
window.addEventListener('DOMContentLoaded', async () => {
    /* 应用静态 i18n(title/subtitle/导航等) */
    I18N.applyI18n(document);
    refreshLangSwitcher();

    /* 绑定语言切换 */
    const sw = document.getElementById('lang-switch');
    if (sw) {
        sw.addEventListener('click', (e) => {
            const b = e.target.closest('button[data-lang]');
            if (!b) return;
            I18N.setLang(b.dataset.lang);
            refreshLangSwitcher();
        });
    }
    /* 语言切换后会重渲染当前 view,这里再刷新一下按钮状态(若刚切换过来) */
    window.addEventListener('i18n:change', () => { I18N.applyI18n(document); refreshLangSwitcher(); });

    if (!Auth.getToken()) { location.href = '/login.html'; return; }
    try {
        const me = await api.get('/api/auth/me');
        state.currentUser = me.username;
        state.currentRole = me.role;
        document.getElementById('me-name').textContent = me.username + ' (' + me.role + ')';
        const logoutBtn = document.getElementById('logout-btn');
        logoutBtn.hidden = false;
        logoutBtn.addEventListener('click', () => Auth.logout());

        document.querySelectorAll('[data-roles]').forEach(el => {
            const roles = el.dataset.roles.split(',');
            if (!roles.includes(me.role)) el.hidden = true;
        });

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
