/* ============================================
 *  app.js  -  Hash 路由 + 视图装配 + 事件
 * ============================================ */

const state = {
    products: [],
    productsKw: '',
    ledgerType: 'ALL',
    ledgerKw: '',
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
    ledger   : loadLedger,
    users    : loadUsers
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
}

/* ---------- 各视图加载 ---------- */
async function loadDashboard() {
    setActive('dashboard');
    const stats = await api.get('/api/dashboard/stats');
    document.getElementById('view').innerHTML = Views.dashboard(stats);
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
    if (state.products.length === 0) {
        state.products = await api.get('/api/products');
    }
    document.getElementById('view').innerHTML = Views.inbound(state.products);
}

async function loadOutbound() {
    setActive('outbound');
    if (state.products.length === 0) {
        state.products = await api.get('/api/products');
    }
    document.getElementById('view').innerHTML = Views.outbound(state.products);
}

async function loadLedger() {
    setActive('ledger');
    const qs = new URLSearchParams();
    if (state.ledgerType) qs.set('type', state.ledgerType);
    if (state.ledgerKw)   qs.set('keyword', state.ledgerKw);
    qs.set('limit', '200');
    const logs = await api.get('/api/ledger?' + qs.toString());
    document.getElementById('view').innerHTML = Views.ledger(
        logs, state.ledgerType, state.ledgerKw);
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
    try {
        // 通过 orderNo 反查 id
        const data = await api.get(`${base}/orders?keyword=${encodeURIComponent(orderNo)}&pageSize=1`);
        const hit = (data.list || []).find(o => o.orderNo === orderNo);
        if (!hit) { toast('未找到单据', 'error'); return; }
        const full = await api.get(`${base}/orders/${hit.id}`);
        openOrderDetailModal(t, full);
    } catch (e) { toast(e.message, 'error'); }
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
function searchLedger() {
    state.ledgerType = document.getElementById('lg-type').value;
    state.ledgerKw   = document.getElementById('lg-kw').value.trim();
    loadLedger();
}

/* ---------- 商品新增/编辑/删除 ---------- */
async function newProduct() {
    showModal('新增商品', Views.productForm({}), async () => {
        const body = readProductForm();
        if (!body) return false;
        try {
            await api.post('/api/products', body);
            toast('新增成功', 'success');
            await loadProducts();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}

async function editProduct(id) {
    try {
        const p = await api.get('/api/products/' + id);
        showModal('编辑商品 #' + id, Views.productForm(p), async () => {
            const body = readProductForm();
            if (!body) return false;
            try {
                await api.put('/api/products/' + id, body);
                toast('保存成功', 'success');
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
            await loadProducts();
        } catch (e) { toast(e.message, 'error'); }
    });
}

function readProductForm() {
    const id    = document.getElementById('pf-id').value;
    const sku   = document.getElementById('pf-sku').value.trim();
    const name  = document.getElementById('pf-name').value.trim();
    if (!sku)  { toast('SKU 不能为空', 'error'); return null; }
    if (!name) { toast('名称不能为空', 'error'); return null; }
    return {
        sku, name,
        category : document.getElementById('pf-cat').value.trim(),
        unit     : document.getElementById('pf-unit').value.trim(),
        location : document.getElementById('pf-loc').value.trim(),
        quantity : Number(document.getElementById('pf-qty').value || 0),
        unitPrice: Number(document.getElementById('pf-price').value || 0),
        lowStock : Number(document.getElementById('pf-low').value || 10),
        remark   : document.getElementById('pf-remark').value.trim(),
    };
}

/* ---------- 入库(多货) ---------- */
function addInboundRow() {
    const tbody = document.getElementById('ib-rows');
    if (!tbody) return;
    const first = tbody.querySelector('tr.ib-row');
    const clone = first.cloneNode(true);
    clone.querySelector('.ib-qty').value = '';
    clone.querySelector('.ib-supplier').value = '';
    clone.querySelector('.ib-row-remark').value = '';
    tbody.appendChild(clone);
    renumberInboundRows();
}
function removeInboundRow(btn) {
    const tbody = document.getElementById('ib-rows');
    if (!tbody) return;
    const tr = btn.closest('tr');
    if (!tr) return;
    if (tbody.querySelectorAll('tr.ib-row').length <= 1) {
        tr.querySelector('.ib-qty').value = '';
        tr.querySelector('.ib-supplier').value = '';
        tr.querySelector('.ib-row-remark').value = '';
        return;
    }
    tr.remove();
    renumberInboundRows();
}
function renumberInboundRows() {
    const rows = document.querySelectorAll('#ib-rows tr.ib-row');
    rows.forEach((r, i) => {
        const idx = r.querySelector('.row-idx');
        if (idx) idx.textContent = (i + 1);
    });
}

function readInboundItems() {
    const rows = document.querySelectorAll('#ib-rows tr.ib-row');
    const items = [];
    let hasError = false;
    rows.forEach((tr) => {
        const productId = Number(tr.querySelector('.ib-product').value);
        const qtyRaw    = tr.querySelector('.ib-qty').value;
        const quantity  = Number(qtyRaw);
        const supplier  = tr.querySelector('.ib-supplier').value.trim();
        const rowRemark = tr.querySelector('.ib-row-remark').value.trim();
        if (!productId && !qtyRaw) return; // 跳过空行
        if (!productId) { toast('存在未选择商品的行', 'error'); hasError = true; return; }
        if (!Number.isFinite(quantity) || quantity <= 0) {
            toast('入库数量必须大于 0', 'error'); hasError = true; return;
        }
        items.push({ productId, quantity, supplier, rowRemark });
    });
    return hasError ? null : items;
}

async function submitInbound() {
    const items = readInboundItems();
    if (items == null) return;
    if (items.length === 0) { toast('请至少填写一条入库明细', 'error'); return; }

    const body = {
        externalNo: document.getElementById('ib-external-no').value.trim(),
        remark:     document.getElementById('ib-remark').value.trim(),
        items
    };

    try {
        const order = await api.post('/api/inbound/batch', body);
        const suppliers = new Set(items.map(i => (i.supplier || '').trim()).filter(Boolean));
        const spInfo = suppliers.size > 0 ? `(${suppliers.size} 个供应商)` : '';
        toast(`入库成功 ${spInfo} 共 ${order.itemCount} 行 / ${order.totalQuantity} 件`, 'success');
        state.products = await api.get('/api/products');
        openInboundOrderModal(order);
        document.getElementById('ib-external-no').value = '';
        document.getElementById('ib-remark').value = '';
        const tbody = document.getElementById('ib-rows');
        if (tbody) {
            tbody.innerHTML = '';
            for (let i = 0; i < 3; i++) addInboundRow();
        }
    } catch (e) { toast(e.message, 'error'); }
}

function openInboundOrderModal(order) {
    document.getElementById('modal-title').textContent = '入库单 ' + (order.orderNo || '');
    document.getElementById('modal-body').innerHTML = Views.inboundOrderModal(order);
    document.getElementById('modal-mask').hidden = false;
    // 打印时间
    const t = document.getElementById('order-print-time');
    if (t) t.textContent = new Date().toLocaleString();
    // 重新绑 OK 按钮:仅关闭
    const ok = document.getElementById('modal-ok');
    const newOk = ok.cloneNode(true);
    newOk.textContent = '关闭';
    ok.parentNode.replaceChild(newOk, ok);
    newOk.addEventListener('click', closeModal);
    // 模态变宽
    const modal = document.querySelector('.modal');
    if (modal) {
        modal.classList.add('modal-wide');
    }
}

function printInboundOrder() {
    // 走 CSS @media print 隐藏非打印区域
    window.print();
}

/* ---------- 出库(多货) ---------- */
function addOutboundRow() {
    const tbody = document.getElementById('ob-rows');
    if (!tbody) return;
    const first = tbody.querySelector('tr.ob-row');
    const clone = first.cloneNode(true);
    clone.querySelector('.ob-qty').value = '';
    clone.querySelector('.ob-row-remark').value = '';
    tbody.appendChild(clone);
    renumberOutboundRows();
}
function removeOutboundRow(btn) {
    const tbody = document.getElementById('ob-rows');
    if (!tbody) return;
    const tr = btn.closest('tr');
    if (!tr) return;
    if (tbody.querySelectorAll('tr.ob-row').length <= 1) {
        tr.querySelector('.ob-qty').value = '';
        tr.querySelector('.ob-row-remark').value = '';
        return;
    }
    tr.remove();
    renumberOutboundRows();
}
function renumberOutboundRows() {
    const rows = document.querySelectorAll('#ob-rows tr.ob-row');
    rows.forEach((r, i) => {
        const idx = r.querySelector('.row-idx');
        if (idx) idx.textContent = (i + 1);
    });
}

function readOutboundItems() {
    const rows = document.querySelectorAll('#ob-rows tr.ob-row');
    const items = [];
    let hasError = false;
    rows.forEach((tr) => {
        const productId = Number(tr.querySelector('.ob-product').value);
        const qtyRaw    = tr.querySelector('.ob-qty').value;
        const quantity  = Number(qtyRaw);
        const rowRemark = tr.querySelector('.ob-row-remark').value.trim();
        if (!productId && !qtyRaw) return; // 跳过空行
        if (!productId) { toast('存在未选择商品的行', 'error'); hasError = true; return; }
        if (!Number.isFinite(quantity) || quantity <= 0) {
            toast('出库数量必须大于 0', 'error'); hasError = true; return;
        }
        items.push({ productId, quantity, rowRemark });
    });
    return hasError ? null : items;
}

async function submitOutbound() {
    const items = readOutboundItems();
    if (items == null) return;
    if (items.length === 0) { toast('请至少填写一条出库明细', 'error'); return; }

    // 前端预校验:用 state.products 的缓存逐项检查当前库存(同商品合并后再校验)
    const need = new Map();
    for (const it of items) {
        need.set(it.productId, (need.get(it.productId) || 0) + it.quantity);
    }
    for (const [pid, qty] of need) {
        const p = state.products.find(x => x.id === pid);
        if (!p) { toast('商品不存在 id=' + pid, 'error'); return; }
        if (p.quantity < qty) {
            toast(`库存不足:SKU ${p.sku} 当前库存 ${p.quantity},需要出库 ${qty}`, 'error');
            return;
        }
    }

    const body = {
        customer:   document.getElementById('ob-customer').value.trim(),
        externalNo: document.getElementById('ob-external-no').value.trim(),
        remark:     document.getElementById('ob-remark').value.trim(),
        items
    };

    try {
        const order = await api.post('/api/outbound/batch', body);
        toast(`出库成功,共 ${order.itemCount} 种 / ${order.totalQuantity} 件`, 'success');
        state.products = await api.get('/api/products');
        openOutboundOrderModal(order);
        // 重置表单
        document.getElementById('ob-customer').value = '';
        document.getElementById('ob-external-no').value = '';
        document.getElementById('ob-remark').value = '';
        const tbody = document.getElementById('ob-rows');
        if (tbody) {
            tbody.innerHTML = '';
            for (let i = 0; i < 3; i++) addOutboundRow();
        }
    } catch (e) { toast(e.message, 'error'); }
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

function printOutboundOrder() {
    window.print();
}

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

        // 按角色隐藏导航项
        document.querySelectorAll('.nav-item[data-roles]').forEach(el => {
            const roles = el.dataset.roles.split(',');
            if (!roles.includes(me.role)) el.hidden = true;
        });

        const r = (location.hash || '#/dashboard').replace('#/', '');
        setActive(r);
        (routes[r] || loadDashboard)();
    } catch (e) {
        // api._handle 已跳 /login
    }
});