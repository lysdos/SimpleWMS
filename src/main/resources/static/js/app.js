/* ============================================
 *  app.js  -  Hash 路由 + 视图装配 + 事件
 * ============================================ */

const state = {
    products: [],
    productsKw: '',
    suppliers: [],
    supplierKw: '',
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
    users    : loadUsers,
    suppliers: loadSuppliers
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
    await ensureSuppliers();
    showModal('新增商品', Views.productForm({}, state.suppliers), async () => {
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
        showModal('编辑商品 #' + id, Views.productForm(p, state.suppliers), async () => {
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

function readProductForm() {
    const id    = document.getElementById('pf-id').value;
    const sku   = document.getElementById('pf-sku').value.trim();
    const name  = document.getElementById('pf-name').value.trim();
    if (!sku)  { toast('SKU 不能为空', 'error'); return null; }
    if (!name) { toast('名称不能为空', 'error'); return null; }

    // 收集选中的 supplier id
    const sel = document.getElementById('pf-suppliers');
    const def = document.getElementById('pf-default-supplier').value;
    const selectedIds = sel ? Array.from(sel.selectedOptions).map(o => Number(o.value)).filter(Boolean) : [];
    const suppliers = selectedIds.map(sid => ({
        supplierId: sid,
        isDefault: String(sid) === String(def)
    }));

    return {
        sku, name,
        category : document.getElementById('pf-cat').value.trim(),
        unit     : document.getElementById('pf-unit').value.trim(),
        location : document.getElementById('pf-loc').value.trim(),
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

/* ---------- 新增入库/出库单 弹窗 ---------- */
async function openNewOrderModal(type) {
    await ensureProducts();
    document.getElementById('modal-title').textContent = type === 'IN' ? '新增入库单' : '新增出库单';
    document.getElementById('modal-body').innerHTML = Views.newOrderModal(type, state.products);
    document.getElementById('modal-mask').hidden = false;
    const ok = document.getElementById('modal-ok');
    const newOk = ok.cloneNode(true);
    newOk.textContent = '提交';
    ok.parentNode.replaceChild(newOk, ok);
    newOk.addEventListener('click', submitNewOrder);
    const modal = document.querySelector('.modal');
    if (modal) modal.classList.add('modal-wide');
    // 绑定商品 change 事件 + 初次预填默认供应商
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
}

function addNewOrderRow() {
    const tbody = document.getElementById('nw-rows');
    if (!tbody) return;
    const first = tbody.querySelector('tr.nw-row');
    const clone = first.cloneNode(true);
    clone.querySelector('.nw-qty').value = '';
    clone.querySelector('.nw-supplier').value = '';
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
        tr.querySelector('.nw-supplier').value = '';
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
        const supplier  = tr.querySelector('.nw-supplier').value.trim();
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