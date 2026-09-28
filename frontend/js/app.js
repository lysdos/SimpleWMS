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
    locationWarehouseId: '',
    // 库位码 → 仓库名(null = 未分配仓库)的全量映射,只给显示用。
    // null = 还没加载。**必须与 state.locations 分开**:loadLocations 会把
    // 筛选后的结果直接赋给 state.locations,拿它反查会缺项。
    locMap: null,
    warehouses: [],
    warehouseKw: '',
    warehouseStatus: '',
    userKw: '',
    userRole: '',
    userStatus: '',
    userDateFrom: '',
    userDateTo: '',
    inventoryKw: '',
    inventoryLoc: '',
    inventoryWarehouse: '',
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
    warehouses: loadWarehouses,
    inventory: loadInventory,
    'stock-checks': loadStockChecks
};

/**
 * 路由渲染的唯一出口。
 * 各 loader 都是 async(内部 await api.get),以前这里没有 catch —— 接口一失败就是
 * 未处理的 promise rejection:控制台报错、页面停在上一屏,看着像"点了没反应"。
 */
async function runRoute(route) {
    try {
        await prepareLocMap();
        await (routes[route] || loadDashboard)();
    } catch (e) {
        toast(e.message || t('err.network'), 'error');
    }
}

async function go(route) {
    if (location.hash !== '#/' + route) {
        location.hash = '#/' + route;
        return;
    }
    await runRoute(route);
}

window.addEventListener('hashchange', async () => {
    const r = (location.hash || '#/dashboard').replace('#/', '');
    setActive(r);
    await runRoute(r);
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
    } catch (e) {
        // 「最近单据」是首页的次要区块,但也不能静默失败:以前这个空 catch 会让
        // 表格留着上一次的行,看着像当前数据。清空 + 提示 + 照样画 tab。
        toast(e.message || t('err.network'), 'error');
        const tb = document.getElementById('inbound-recent-tbody');
        if (tb) tb.innerHTML = Views.inboundRecentRows([]);
        Views.applyCardTabs('inbound', st);
    }
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
    } catch (e) {
        // 同 renderInboundRecent:失败要清空 + 提示,不能留着上一次的行假装成功
        toast(e.message || t('err.network'), 'error');
        const tb = document.getElementById('outbound-recent-tbody');
        if (tb) tb.innerHTML = Views.outboundRecentRows([]);
        Views.applyCardTabs('outbound', st);
    }
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

/**
 * 供应商列表页。
 *
 * 筛选结果**只用于本屏渲染**,不写回 `state.suppliers` —— 那份缓存是各弹窗的
 * 「供应商下拉」和商品页筛选器的数据源,必须是全量。以前直接赋值,于是在供应商页
 * 筛一次「停用」,再打开新增商品弹窗,候选里就只剩停用供应商了。
 * 无筛选时这一次结果本身就是全量,顺手当缓存用;增删改后由调用方清空缓存触发重取。
 * (与 loadLocations 同一套做法,见那边的注释。)
 */
async function loadSuppliers() {
    setActive('suppliers');
    const qs = new URLSearchParams();
    if (state.supplierKw) qs.set('keyword', state.supplierKw);
    if (state.supplierStatus) qs.set('status', state.supplierStatus);
    const query = qs.toString();
    const list = await api.get('/api/suppliers' + (query ? '?' + query : ''));
    if (!query) state.suppliers = list;
    document.getElementById('view').innerHTML = Views.suppliers(list, state.supplierStatus);
}

async function loadLocations() {
    setActive('locations');
    await ensureWarehouses();
    const qs = new URLSearchParams();
    if (state.locationKw) qs.set('keyword', state.locationKw);
    if (state.locationStatus) qs.set('status', state.locationStatus);
    if (state.locationWarehouseId) qs.set('warehouseId', state.locationWarehouseId);
    // 这份结果**只用于本屏渲染,不写回 state.locations**。
    // state.locations 是各弹窗「库位下拉」的数据源,必须是**全量**;写回去的话,在库位管理页
    // 筛过之后,商品/出入库单的库位下拉就只剩筛出来的那几个 —— 更糟的是商品自己那个**启用中**
    // 的库位会因为"不在这份清单里"被渲染成「(已停用或已删除)」。
    // 筛选条件本身就存在 state.locationKw / locationStatus / locationWarehouseId 里,
    // 所以不需要再缓存一份筛选结果。
    const data = await api.get('/api/locations' + (qs.toString() ? '?' + qs.toString() : ''));
    document.getElementById('view').innerHTML =
        Views.locations(data, state.locationStatus, state.warehouses, state.locationWarehouseId);
}

async function loadInventory() {
    setActive('inventory');
    await ensureLocations();
    await ensureWarehouses();   // 仓库筛选下拉要用
    updateInventoryExportHref();
    const qs = new URLSearchParams();
    if (state.inventoryKw)     qs.set('sku', state.inventoryKw);
    if (state.inventoryLoc)    qs.set('location', state.inventoryLoc);
    if (state.inventoryWarehouse) qs.set('warehouseId', state.inventoryWarehouse);
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

/* ---------- 多选行列表(替代原生 <select multiple>) ----------
   原生多选里不加 Ctrl/⌘ 的点击会**覆盖**已选 —— 选第二个必须按修饰键,
   与 PDA 的 setMultiChoiceItems(点一下切换一个)也不一致。
   现在的列表把 checkbox 包在 <label> 里,点整行即切换,不需要修饰键。 */

/** 取多选列表当前勾选的值,按行序返回 */
function checkedValues(listId) {
    const list = document.getElementById(listId);
    if (!list) return [];
    return Array.from(list.querySelectorAll('input[type="checkbox"]:checked')).map(b => b.value);
}

/**
 * 读内联 onclick 传进来的 data-* 值(调用方写成 `onclick="f(id, this)"` + `data-xxx="…"`)。
 *
 * 为什么不直接把值塞进 JS 字符串:那样要 escapeHtml 一层,而浏览器先把属性里的实体
 * **解码回引号**、再当 JS 解析 —— 编码里带个单引号就拼成 `f(7, 'A'B')`,整条 onclick
 * 直接语法错误(按钮点了没反应),再离谱点还能被拼成可执行代码。走属性则永不进 JS 源码。
 */
function btnData(el, key, fallback) {
    const v = (el && el.dataset) ? el.dataset[key] : '';
    return v || fallback;
}

/**
 * 过滤:只切行的显隐,不重填 —— 重填会把已勾选的项丢掉。
 * 一行都不剩时打开「没匹配」那行(列表本身为空走的是 .ms-empty,与这里无关)。
 */
function paintFilter(listId, boxId) {
    const list = document.getElementById(listId);
    const box  = document.getElementById(boxId);
    if (!list) return;
    const kw = (box && box.value ? box.value : '').trim().toLowerCase();
    const rows = Array.from(list.querySelectorAll('.ms-item'));
    let shown = 0;
    rows.forEach(el => {
        const hit = !kw || el.textContent.toLowerCase().includes(kw);
        el.style.display = hit ? '' : 'none';
        if (hit) shown++;
    });
    const nomatch = list.querySelector('.ms-nomatch');
    if (nomatch) nomatch.hidden = !(rows.length > 0 && shown === 0);
}

/**
 * 装配一个多选列表:可选过滤框 + 顶部「已选 N 个」+ 到上限后禁用其余未勾选的行。
 * change 用事件委托绑在容器上,所以之后重填 innerHTML(异步到达 / 切仓库)不必重新绑定。
 * 返回 sync:重填内容后调一次,把计数与禁用位重算。
 * opts.countFn(必给)因为「已选 N 个」后面跟的名词各处不同;
 * opts.max 项数上限;opts.maxChars 勾选值拼成逗号串后的字符上限 —— 都为 0 表示不限;
 * opts.filterBoxId 给了就挂上过滤监听并先刷一遍(重填内容后要自己再调 paintFilter)。
 */
function bindCheckList(listId, opts) {
    opts = opts || {};
    const max      = opts.max || 0;
    const maxChars = opts.maxChars || 0;
    const list = document.getElementById(listId);
    const count = document.getElementById(listId + '-count');
    const sync = () => {
        if (!list) return;
        const boxes = Array.from(list.querySelectorAll('input[type="checkbox"]'));
        const picked = boxes.filter(b => b.checked);
        if (count) count.textContent = opts.countFn(picked.length);
        if (!max && !maxChars) return;
        const joined = picked.map(b => b.value).join(',');
        boxes.forEach(b => {
            // 长度要按「把这一行也拼进去之后」算,不是按当前串长
            const cand = joined ? joined + ',' + b.value : b.value;
            const off = !b.checked
                && ((max && picked.length >= max) || (maxChars && cand.length > maxChars));
            b.disabled = off;
            const item = b.closest('.ms-item');
            if (item) item.classList.toggle('is-off', off);
        });
    };
    if (list) list.addEventListener('change', sync);
    sync();
    if (opts.filterBoxId) {
        const box = document.getElementById(opts.filterBoxId);
        if (box) box.addEventListener('input', () => paintFilter(listId, opts.filterBoxId));
        paintFilter(listId, opts.filterBoxId);
    }
    return sync;
}

function openNewStockCheckModal() {
    showModal(t('stock_check.modal.title.new'), Views.newStockCheckForm(), async () => {
        const scope = (document.querySelector('input[name="nsc-scope"]:checked') || {}).value || 'ALL';
        const whSel = document.getElementById('nsc-warehouse-select');
        let keyword = '';
        let warehouseId = null;
        if (scope === 'SKU') {
            // 多选列表 -> 后端收的仍是逗号分隔串(接口没变,限额也没变)
            keyword = checkedValues('nsc-sku-list').join(',');
        } else if (scope === 'WAREHOUSE') {
            warehouseId = (whSel && whSel.value) ? Number(whSel.value) : null;
            // 不勾库位 = 空关键字,后端按"整个仓库"处理
            keyword = checkedValues('nsc-loc-list').join(',');
        }
        const remark  = document.getElementById('nsc-remark').value.trim();

        if (scope === 'SKU' && !keyword) {
            toast(t('stock_check.err.sku_empty'), 'error');
            return false;
        }
        if (scope === 'WAREHOUSE' && !warehouseId) {
            toast(t('stock_check.err.warehouse_empty'), 'error');
            return false;
        }
        try {
            const c = await api.post('/api/stock-checks', { scope, keyword, warehouseId, remark });
            toast(t('stock_check.toast.created', { n: c.checkNo, m: c.totalCount }), 'success');
            state.stockCheckPage = 1;
            await loadStockChecks();
            openStockCheckDetail(c.id);
        } catch (e) { toast(e.message, 'error'); return false; }
    });

    // 模式 radio 联动:ALL 无输入 / SKU 过滤框+多选列表 / WAREHOUSE 仓库下拉+库位多选(不勾 = 整个仓库)
    const radios   = document.querySelectorAll('input[name="nsc-scope"]');
    const skuWrap  = document.getElementById('nsc-sku-wrap');
    const whWrap   = document.getElementById('nsc-warehouse-wrap');
    let skuCache   = null;   // 商品列表只在弹窗存活期间拉一次
    let skuLoading = false;

    // 上限与后端 StockCheckService 的 MAX_ITEMS / MAX_KEYWORD_CHARS 对齐,让超限在
    // **前端就选不动**,而不是提交后吃一个 400。SKU 列表与库位列表共用同一组上限 ——
    // 后端也是在同一个 parseList 里按「解析后的项数」和「原始串长」两道卡。
    const MAX_PICK = 50;
    const MAX_KEYWORD_CHARS = 500;
    const syncSku = bindCheckList('nsc-sku-list', {
        max: MAX_PICK, maxChars: MAX_KEYWORD_CHARS, filterBoxId: 'nsc-sku-filter',
        countFn: n => t('stock_check.sku.selected_fmt', { n })
    });
    const syncLoc = bindCheckList('nsc-loc-list', {
        max: MAX_PICK, maxChars: MAX_KEYWORD_CHARS, filterBoxId: 'nsc-loc-filter',
        countFn: n => t('stock_check.loc.selected_fmt', { n })
    });

    function fillSkuOptions() {
        const list = document.getElementById('nsc-sku-list');
        if (!list) return;
        list.innerHTML = Views.multiCheckListItems(
            (skuCache || []).map(p => ({ value: p.sku, label: p.sku + ' · ' + (p.name || '') })),
            { empty: t('new_order.placeholder.no_product'),
              nomatch: t('stock_check.sku.filter_nomatch') });
        syncSku();
        // 重填会把行上的 display 清掉,而用户可能在商品返回**之前**就已经打了过滤词
        // (过滤框是立刻可见的,列表却是异步填的)—— 不重放一次,过滤框有字但全列都显示。
        paintFilter('nsc-sku-list', 'nsc-sku-filter');
    }

    function ensureSkuList() {
        // 只填一次:选项不依赖别的状态,而**重填会清掉用户已勾选的 SKU**
        // (切到「按仓库」再切回来就会走这里)。在飞的那次也别重复发请求。
        if (skuCache || skuLoading) return;
        skuLoading = true;
        api.get('/api/products')
           .then(list => { skuCache = list || []; skuLoading = false; fillSkuOptions(); })
           .catch(e => { skuLoading = false; toast(e.message, 'error'); });
    }

    function paintScopeUi() {
        const cur = (document.querySelector('input[name="nsc-scope"]:checked') || {}).value || 'ALL';
        // 卡片高亮
        document.querySelectorAll('.nsc-scope-opt').forEach(el => {
            const v = el.querySelector('input').value;
            el.style.borderColor = (v === cur) ? 'var(--c-primary)' : 'var(--divider)';
            el.style.background  = (v === cur) ? '#E8F0FF' : 'transparent';
        });
        whWrap.style.display  = (cur === 'WAREHOUSE') ? '' : 'none';
        skuWrap.style.display = (cur === 'SKU') ? '' : 'none';
        if (cur === 'SKU') {
            ensureSkuList();
            paintFilter('nsc-sku-list', 'nsc-sku-filter');   // 切回来时按当前过滤词刷一遍
        } else if (cur === 'WAREHOUSE') {
            // 仓库下拉 + 库位多选(库位跟着所选仓库联动)。
            // 这里单独拉一次 /api/locations 而不是用 state.locations —— 后者会被库位管理页
            // 的筛选结果改写,勾选项会莫名缺一块(见 state.locMap 那段注释)。
            // 切仓库时库位列表会重填、勾选随之清空:这是**对的** —— 后端只收单个
            // warehouseId,勾了别的仓库的库位也进不了范围(IN 子句被 warehouse_id 收窄)。
            ensureWarehouses()
                .then(() => api.get('/api/locations'))
                .then(locs => {
                    const whSel = document.getElementById('nsc-warehouse-select');
                    const whs = (state.warehouses || []).filter(w => w.enabled !== false);
                    whSel.innerHTML = whs.map(w =>
                        `<option value="${w.id}">${escapeHtml(w.name)}</option>`).join('');
                    const paintLocs = () => {
                        const whId = whSel.value;
                        const mine = (locs || []).filter(l =>
                            l.enabled !== false && String(l.warehouseId || '') === String(whId));
                        document.getElementById('nsc-loc-list').innerHTML = Views.multiCheckListItems(
                            mine.map(l => ({
                                value: l.code,
                                label: l.code + (l.name && l.name !== l.code ? ' · ' + l.name : '')
                            })),
                            { empty: t('location.picker.empty'),
                              nomatch: t('stock_check.loc.filter_nomatch') });
                        syncLoc();
                        // 库位列表是**跟着仓库整体换掉**的,上一个仓库的过滤词对新列表没有意义,
                        // 留着还会把新列表整片滤空(看着像"这个仓库没有库位")—— 随列表一起重置。
                        // 必须先清空、再重放:反过来的话会用旧词滤一遍新列表,把行全藏起来。
                        const lb = document.getElementById('nsc-loc-filter');
                        if (lb) lb.value = '';
                        paintFilter('nsc-loc-list', 'nsc-loc-filter');
                    };
                    whSel.onchange = paintLocs;
                    paintLocs();
                }).catch(e => toast(e.message, 'error'));
        }
    }
    radios.forEach(r => r.addEventListener('change', paintScopeUi));
    paintScopeUi();
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
                <div style="font-size:11px;color:#6b7280;">${escapeHtml(it.sku)} · ${Views.locLabel(it.locationCode)}</div>
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

/* ---------- 库存盘点详情 ---------- */
async function openStockCheckDetail(id) {
    try {
        const c = await api.get('/api/stock-checks/' + id);
        showModal(t('stock_check.modal.title.detail') + ' ' + c.checkNo, Views.stockCheckDetail(c));
        setModalWidth('modal-wide');
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
            // 提交前确认:未盘行会按 0 计入盘亏且不可逆。PDA 那边一直有这个确认,Web 之前没有。
            // 每行「保存」后会重拉整张单(见本函数末尾的 sci-save 处理),所以这里的 c 是最新的。
            const remain = Math.max(0, (c.totalCount || 0) - (c.countedCount || 0));
            if (!confirm(remain > 0
                    ? t('stock_check.confirm.submit_unfinished', { n: remain })
                    : t('stock_check.confirm.submit_all'))) return;
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
    // 按类型分组导出。以前这里没有 try/catch,而它是 onclick 直接调的:
    // downloadZip 一抛错就是未处理的 rejection,界面上什么也不会发生。
    const inIds = [], outIds = [];
    document.querySelectorAll('.od-cb:checked').forEach(cb => {
        const id = parseInt(cb.dataset.id, 10);
        if (cb.dataset.type === 'IN') inIds.push(id); else outIds.push(id);
    });
    try {
        if (inIds.length > 0) await downloadZip('/api/inbound/orders/export.zip', inIds, 'inbound_orders.zip');
        if (outIds.length > 0) await downloadZip('/api/outbound/orders/export.zip', outIds, 'outbound_orders.zip');
    } catch (e) {
        toast(e.message || t('err.network'), 'error');
    }
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
    // 不要把响应体原文塞进错误消息:500 时那是一整段 HTML/堆栈,弹到 toast 里没法看
    if (!resp.ok) throw new Error(t('orders.export_failed', { code: resp.status }));
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
    setModalWidth(null);
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
    // 单类型必须由调用方给出。曾经回落到全局 state.orderType(出入库单页面的下拉),
    // 而它默认 'ALL'、切过出库单就变 'OUT' —— 于是入库登记点"查看"会去查 /api/outbound,
    // 同 id 存在出库单时直接弹出错单据。这里不再读任何共享状态。
    const tt = typeHint || 'IN';
    const base = tt === 'IN' ? '/api/inbound' : '/api/outbound';
    if (state._viewingOrder === id) return;
    state._viewingOrder = id;
    try {
        // 不再用 orderNo 拼进 inline onclick;按 ID 直接拉详情
        // 单据响应里 items[].codes 后端已经按**明细行**填好了
        // (入库:InboundService.getOrderWithCodes 按 inbound_item_id 精确归属;
        //  出库:OutboundService.getOrder 填 codes + 按库位汇总的 picks + 按库位分组的 codesByLocation),
        // 所以这里只拉一次单据就够,不再额外拉 /inventory/items/by-* 再按 SKU 自己分组 ——
        // 多一次请求,而且同商品拆多行时会分错(两行各显示该 SKU 的全部码)。
        const full = await api.get(`${base}/orders/${id}`);
        if (!full || !full.id) { toast(t('err.empty'), 'error'); return; }
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
    setModalWidth('modal-wide');
}

/* 提交入库单 */
async function commitInboundOrder(id, btn) {
    const orderNo = btnData(btn, 'orderNo', '#' + id);
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
async function cancelInboundOrder(id, btn) {
    const orderNo = btnData(btn, 'orderNo', '#' + id);
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
    bindCheckList('pf-supplier-list', {
        filterBoxId: 'pf-supplier-filter',
        countFn: n => t('product.form.suppliers_selected_fmt', { n })
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
        bindCheckList('pf-supplier-list', {
            filterBoxId: 'pf-supplier-filter',
            countFn: n => t('product.form.suppliers_selected_fmt', { n })
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

/**
 * 加载"库位码 → 仓库名"映射,供 Views.locLabel 在单据/库存/盘点里显示"仓库 / 库位"。
 *
 * 走**独立的 state.locMap**,不复用 state.locations:库位管理页的
 * loadLocations 会把筛选结果直接赋给 state.locations,一旦用户按仓库筛过,
 * 拿它反查就会缺项,库位标签会莫名其妙退回光秃秃的码。
 * 只在为空时拉一次;库位/仓库增改删时调 invalidateLocMap() 作废。
 */
async function ensureLocMap() {
    if (state.locMap) return;
    const list = await api.get('/api/locations');
    const map = {};
    for (const l of (list || [])) map[l.code] = l.warehouseName || null;
    state.locMap = map;
}

function invalidateLocMap() {
    state.locMap = null;
}

/**
 * 路由守卫:进任何页面前先把库位→仓库映射备好,让「仓库 / 库位」标签
 * 第一次渲染就是对的,不用等再切一次页面。
 * 拉不到就算了 —— 一个显示用的映射不该把整页卡住,标签会降级成只显示库位码。
 */
async function prepareLocMap() {
    try { await ensureLocMap(); } catch (_) { /* 忽略,降级显示 */ }
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

    const def = document.getElementById('pf-default-supplier').value;
    const selectedIds = checkedValues('pf-supplier-list').map(Number).filter(Boolean);
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
    const body = {
        sku, name,
        category : document.getElementById('pf-cat').value.trim(),
        unit     : document.getElementById('pf-unit').value.trim(),
        location,
        unitPrice: Number(document.getElementById('pf-price').value || 0),
        lowStock : Number(document.getElementById('pf-low').value || 10),
        defaultShelfLifeDays: shelfLifeRaw === '' ? null : Number(shelfLifeRaw),
        expiryWarnDays      : warnDaysRaw  === '' ? null : Number(warnDaysRaw),
        remark   : document.getElementById('pf-remark').value.trim(),
        suppliers
    };
    // 数量只在**新建**时可填:编辑态视图不渲染 #pf-qty(改由出入库/报废维护,
    // 见 ProductDao.update),所以这里判存在再带上,别把表单打开时的旧值写回去。
    const qtyEl = document.getElementById('pf-qty');
    if (qtyEl) body.quantity = Number(qtyEl.value || 0);
    return body;
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
            state.suppliers = [];   // 失效缓存:下次 ensureSuppliers 重取全量
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
                state.suppliers = [];   // 失效缓存:下次 ensureSuppliers 重取全量
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
            state.suppliers = [];   // 失效缓存:下次 ensureSuppliers 重取全量
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
    state.locationWarehouseId = document.getElementById('loc-wh-filter')?.value || '';
    loadLocations();
}
async function newLocation() {
    await ensureWarehouses();
    showModal(t('location.form.new'), Views.locationForm({}, state.warehouses), async () => {
        const body = readLocationForm();
        if (!body) return false;
        try {
            await api.post('/api/locations', body);
            toast(t('supplier.toast.created'), 'success');
            state.locations = [];
            invalidateLocMap();
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
        await ensureWarehouses();
        showModal(t('location.form.edit'), Views.locationForm(l, state.warehouses), async () => {
            const body = readLocationForm();
            if (!body) return false;
            try {
                await api.put('/api/locations/' + id, body);
                toast(t('common.success.save'), 'success');
                state.locations = [];
                invalidateLocMap();
                await loadLocations();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    } catch (e) { toast(e.message, 'error'); }
}
function delLocation(id, btn) {
    // 库位码由按钮的 data-code 带进来。不再从 state.locations 里反查:那份现在只在
    // **下拉要被打开时**才加载,光进库位管理页它可能是空的,反查会退化成「#12」。
    const label = btnData(btn, 'code', '#' + id);
    confirmModal(`${t('common.confirm_delete')}「${label}」?${t('common.delete_irreversible')}\n\n${t('location.del_tip')}`, async () => {
        try {
            await api.del('/api/locations/' + id);
            toast(t('common.success.delete'), 'success');
            state.locations = [];
            invalidateLocMap();
            // 库位数变了,仓库列表上的「库位数」跟着失效
            state.warehouses = [];
            await loadLocations();
        } catch (e) { toast(e.message, 'error'); }
    });
}
function readLocationForm() {
    const code = document.getElementById('lf-code').value.trim();
    if (!code) { toast(t('location.error.code_required'), 'error'); return null; }
    const whVal = document.getElementById('lf-warehouse')?.value || '';
    return {
        code,
        name   : document.getElementById('lf-name').value.trim(),
        warehouseId: whVal ? Number(whVal) : null,
        remark : document.getElementById('lf-remark').value.trim(),
        enabled: document.getElementById('lf-enabled').value === 'true'
    };
}

/* ---------- 仓库管理 ---------- */
async function ensureWarehouses() {
    if (!state.warehouses || state.warehouses.length === 0) {
        state.warehouses = await api.get('/api/warehouses');
    }
}

/**
 * 仓库列表页。筛选结果**只渲染、不写回** `state.warehouses`(与 loadSuppliers /
 * loadLocations 同一套做法):那份缓存是库位表单的「所属仓库」下拉、库位页的仓库筛选器
 * 以及商品页的数据源,必须是全量。以前筛一次「停用」,后面所有下拉就只剩停用仓库。
 * 增删改路径已有 `state.warehouses = []` 失效缓存,所以无筛选时才顺手回填。
 */
async function loadWarehouses() {
    setActive('warehouses');
    const qs = new URLSearchParams();
    if (state.warehouseKw) qs.set('keyword', state.warehouseKw);
    if (state.warehouseStatus) qs.set('status', state.warehouseStatus);
    const query = qs.toString();
    const list = await api.get('/api/warehouses' + (query ? '?' + query : ''));
    if (!query) state.warehouses = list;
    document.getElementById('view').innerHTML = Views.warehouses(list, state.warehouseStatus);
}

function searchWarehouses() {
    state.warehouseKw = document.getElementById('wh-kw').value.trim();
    state.warehouseStatus = document.getElementById('wh-status-filter')?.value || '';
    loadWarehouses();
}
async function newWarehouse() {
    showModal(t('warehouse.form.new'), Views.warehouseForm({}), async () => {
        const body = readWarehouseForm();
        if (!body) return false;
        try {
            await api.post('/api/warehouses', body);
            toast(t('supplier.toast.created'), 'success');
            state.warehouses = [];
            await loadWarehouses();
        } catch (e) { toast(e.message, 'error'); return false; }
    });
}
async function viewWarehouse(id) {
    try {
        const w = await api.get('/api/warehouses/' + id);
        showModal(t('warehouse.form.view'), Views.warehouseDetail(w), null, true);
    } catch (e) { toast(e.message, 'error'); }
}
async function editWarehouse(id) {
    try {
        const w = await api.get('/api/warehouses/' + id);
        showModal(t('warehouse.form.edit'), Views.warehouseForm(w), async () => {
            const body = readWarehouseForm();
            if (!body) return false;
            try {
                await api.put('/api/warehouses/' + id, body);
                toast(t('common.success.save'), 'success');
                // 改了仓库名:库位列表里的「所属仓库」列,以及各处
                // 「仓库 / 库位」标签用的映射,都要跟着刷新
                state.warehouses = [];
                state.locations = [];
                invalidateLocMap();
                await loadWarehouses();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    } catch (e) { toast(e.message, 'error'); }
}
function delWarehouse(id) {
    const w = (state.warehouses || []).find(x => x.id === id);
    const label = w ? w.code : ('#' + id);
    confirmModal(`${t('common.confirm_delete')}「${label}」?${t('common.delete_irreversible')}\n\n${t('warehouse.del_tip')}`, async () => {
        try {
            await api.del('/api/warehouses/' + id);
            toast(t('common.success.delete'), 'success');
            state.warehouses = [];
            state.locations = [];
            invalidateLocMap();
            await loadWarehouses();
        } catch (e) { toast(e.message, 'error'); }
    });
}
function readWarehouseForm() {
    const code = document.getElementById('wf-code').value.trim();
    if (!code) { toast(t('warehouse.error.code_required'), 'error'); return null; }
    const name = document.getElementById('wf-name').value.trim();
    if (!name) { toast(t('warehouse.error.name_required'), 'error'); return null; }
    return {
        code,
        name,
        remark : document.getElementById('wf-remark').value.trim(),
        enabled: document.getElementById('wf-enabled').value === 'true'
    };
}

/* ---------- 库存管理 ---------- */
function searchInventory() {
    state.inventoryKw     = document.getElementById('inv-kw').value.trim();
    state.inventoryLoc    = document.getElementById('inv-loc').value;
    state.inventoryWarehouse = document.getElementById('inv-wh')?.value || '';
    // 不能用 || 'IN_STOCK':下拉新增了"全部"(空值),|| 会把它强行改回在库,导致选了"全部"没反应
    state.inventoryStatus = document.getElementById('inv-status')?.value ?? 'IN_STOCK';
    state.inventoryExpiryStatus = document.getElementById('inv-expiry').value || '';
    state.inventoryExpiryFrom   = document.getElementById('inv-expiry-from').value || '';
    state.inventoryExpiryTo     = document.getElementById('inv-expiry-to').value || '';
    state.inventoryPage = 1;
    updateInventoryExportHref();
    loadInventory();
}
function resetInventoryFilter() {
    state.inventoryKw = ''; state.inventoryLoc = ''; state.inventoryWarehouse = '';
    state.inventoryStatus = 'IN_STOCK';
    state.inventoryExpiryStatus = ''; state.inventoryExpiryFrom = ''; state.inventoryExpiryTo = '';
    state.inventoryPage = 1;
    updateInventoryExportHref();
    loadInventory();
}
/**
 * 把当前筛选条件拼成导出 URL,挂在按钮的 data-url 上。
 *
 * 这里以前是 `a.href = ...`(靠后端白名单免登录),现在导出接口要求鉴权,
 * 顶层导航带不了 Authorization,所以改走 api.download(见 downloadInventoryCsv)。
 */
function updateInventoryExportHref() {
    const a = document.getElementById('inv-export-btn');
    if (!a) return;
    const qs = new URLSearchParams();
    if (state.inventoryKw)     qs.set('sku', state.inventoryKw);
    if (state.inventoryLoc)    qs.set('location', state.inventoryLoc);
    if (state.inventoryWarehouse) qs.set('warehouseId', state.inventoryWarehouse);
    if (state.inventoryStatus) qs.set('status', state.inventoryStatus);
    if (state.inventoryExpiryStatus) qs.set('expiryStatus', state.inventoryExpiryStatus);
    if (state.inventoryExpiryFrom)   qs.set('expiryFrom', state.inventoryExpiryFrom);
    if (state.inventoryExpiryTo)     qs.set('expiryTo', state.inventoryExpiryTo);
    const q = qs.toString();
    a.dataset.url = '/api/inventory/items/export.csv' + (q ? '?' + q : '');
}

function downloadInventoryCsv(btn) {
    const url = (btn && btn.dataset.url) || '/api/inventory/items/export.csv';
    const name = 'inventory-' + new Date().toISOString().slice(0, 10) + '.csv';
    api.download(url, name).catch(e => toast(e.message || t('err.network'), 'error'));
}
function gotoInventoryPage(p) {
    state.inventoryPage = Math.max(1, p);
    loadInventory();
}

function viewBarcode(id, btn) {
    const code = btnData(btn, 'code', '#' + id);
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

/**
 * 报废弹窗。后端这条是 ADMIN-only(SCRAPPED 是不可逆终态,误报废只能进 SQL 修),
 * 所以列表里只对 ADMIN 显示入口 —— 不给操作员一个点了必然 403 的按钮。
 * 调拨可逆(挪错再挪一次),后端已不设门槛,入口对所有人开放。
 */
function scrapInventory(id, btn) {
    const code = btnData(btn, 'code', '#' + id);
    showModal(t('inventory.modal.scrap_title'), `
        <p style="margin:0 0 12px;font-size:13px;color:var(--c-text-2);">
            ${t('inventory.modal.scrap_tip')}
        </p>
        <div style="background:var(--c-bg-soft);padding:8px 12px;border-radius:var(--r-sm);font-family:var(--font-mono);margin-bottom:12px;">
            ${escapeHtml(code)}
        </div>
        <label style="display:block;font-size:13px;color:var(--c-text-2);margin-bottom:4px;">${t('inventory.modal.scrap_reason')}</label>
        <textarea id="scrap-remark" class="input" rows="3" maxlength="255" placeholder="${t('inventory.modal.scrap_placeholder')}"></textarea>
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

async function transferInventory(id, btn) {
    const code = btnData(btn, 'code', '#' + id);
    const currentLoc = btnData(btn, 'currentLoc', '');
    await ensureLocations();
    // 目标库位按所属仓库分组,多仓库下单子长了也不至于混在一起
    const locOptions = Views.locationOptgroups(
        state.locations.filter(l => l.enabled !== false && l.code !== currentLoc),
        l => `<option value="${escapeHtml(l.code)}">${escapeHtml(l.code)}${l.name && l.name !== l.code ? ' / ' + escapeHtml(l.name) : ''}</option>`);
    showModal(t('inventory.modal.transfer_title'), `
        <p style="margin:0 0 12px;font-size:13px;color:var(--c-text-2);">
            ${t('inventory.modal.transfer_tip', { from: escapeHtml(currentLoc) })}
        </p>
        <div style="background:var(--c-bg-soft);padding:8px 12px;border-radius:var(--r-sm);font-family:var(--font-mono);margin-bottom:12px;">
            ${escapeHtml(code)}
        </div>
        <label style="display:block;font-size:13px;color:var(--c-text-2);margin-bottom:4px;">${t('inventory.modal.transfer_target')}<span class="req">*</span></label>
        <select id="transfer-to" class="select" style="width:100%;">
            ${locOptions
                ? `<option value="" disabled selected>${t('inventory.modal.transfer_pick')}</option>${locOptions}`
                : `<option value="" disabled>${t('inventory.modal.transfer_empty')}</option>`}
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
    // 入库行的库位下拉要用(出库不用库位,由系统自动跨库位取货)
    await ensureLocations();
    document.getElementById('modal-title').textContent = type === 'IN' ? t('inbound.modal.title.new') : t('outbound.modal.title.new');
    document.getElementById('modal-body').innerHTML = Views.newOrderModal(type, state.products, state.suppliers, state.locations);
    document.getElementById('modal-mask').hidden = false;
    freshModalOk(t('common.submit')).addEventListener('click', submitNewOrder);
    // 入库明细有 9 列(含库位),要比普通宽弹窗再宽一档
    setModalWidth('modal-xwide');
    document.querySelectorAll('#nw-rows .nw-product').forEach(sel => {
        sel.addEventListener('change', onNewOrderProductChange);
        if (sel.value) onNewOrderProductChange.call(sel);
    });
    renumberNewOrderRows();
}

/**
 * 按本行已选商品重排供应商候选:该商品关联过的置顶(默认供应商第一),其余供应商兜底。
 * 重建 innerHTML 会把当前选中一起清掉,所以先取旧值 —— 用户手动选过的必须还原,
 * 只有原来为空时才用商品的默认供应商顶上(沿用"只在空的时候填"的规矩)。
 */
function syncRowSupplier(tr) {
    const sup = tr.querySelector('.nw-supplier');
    const prodSel = tr.querySelector('.nw-product');
    if (!sup || !prodSel) return;
    const opt = prodSel.selectedOptions[0];
    const prev = sup.value;
    let refs = [];
    try { refs = JSON.parse((opt && opt.getAttribute('data-suppliers')) || '[]'); } catch (e) { refs = []; }
    const def = (opt && opt.getAttribute('data-default')) || '';
    sup.innerHTML = Views.supplierOptionsHtml(refs, state.suppliers, prev || def);
}

function onNewOrderProductChange() {
    const opt = this.selectedOptions[0];
    if (!opt) return;
    const tr = this.closest('tr');
    syncRowSupplier(tr);
    // 入库行:带出商品默认保质期天数(可改)
    const shelf = tr.querySelector('.nw-shelf-life');
    if (shelf && !shelf.value) {
        const dfltShelf = opt.getAttribute('data-shelf-life') || '';
        if (dfltShelf !== '') shelf.value = dfltShelf;
    }
    // 入库行:带出商品默认库位(可改)。
    // 只在空的时候填:用户已经手动改过库位(比如主仓放不下改到副仓)时,
    // 再换商品不该把他的选择冲掉。填进去的值在下拉里可见,不存在"偷偷改库位"。
    const loc = tr.querySelector('.nw-loc');
    if (loc && !loc.value) {
        const dfltLoc = opt.getAttribute('data-loc') || '';
        if (dfltLoc !== '') loc.value = dfltLoc;
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
    const loc = clone.querySelector('.nw-loc');
    if (loc) loc.value = '';
    clone.querySelector('.nw-row-remark').value = '';
    // 克隆体会连上一行重建过的供应商候选(可能带着那个商品的「该商品供应商」分组)
    // 一起复制过来;商品下拉在克隆后回到占位项,这里按本行实际的商品状态重排。
    syncRowSupplier(clone);
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
        const loc = tr.querySelector('.nw-loc');
        if (loc) loc.value = '';
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
        // 入库行的库位;空串=用商品默认库位(后端负责兜底)
        const locEl = tr.querySelector('.nw-loc');
        const location = locEl ? locEl.value.trim() : '';
        const rowRemark = tr.querySelector('.nw-row-remark').value.trim();
        if (!productId && !qtyRaw) return;
        if (!productId) { toast(t('new_order.err.no_product'), 'error'); hasError = true; return; }
        if (!Number.isFinite(quantity) || quantity <= 0) {
            toast(t('new_order.err.qty_invalid'), 'error'); hasError = true; return;
        }
        items.push({
            productId, quantity, supplier, rowRemark,
            productionDate, shelfLifeDays, location
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
    setModalWidth('modal-wide');
}

function openOutboundOrderModal(order) {
    document.getElementById('modal-title').textContent = t('outbound_order.title') + ' ' + (order.orderNo || '');
    document.getElementById('modal-body').innerHTML = Views.outboundOrderModal(order);
    document.getElementById('modal-mask').hidden = false;
    const tm = document.getElementById('order-print-time');
    if (tm) tm.textContent = new Date().toLocaleString();
    freshModalOk(t('common.close')).addEventListener('click', closeModal);
    setModalWidth('modal-wide');
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
        // 首次进站(以及 F5 刷新)也走一遍路由渲染,否则 state.locMap 是空的,
        // 「仓库 / 库位」标签在首屏会退化成光秃秃的库位码 —— 而首屏正是用户最常看到的一屏。
        // 与 go()/hashchange 共用 runRoute,失败同样会 toast(以前这里被空 catch 吞掉,
        // 首屏接口一挂就是一片空白,连个提示都没有)。
        await runRoute(r);
    } catch (e) {
        // 登录态相关的失败:api._handle 已负责清 token 并跳 /login
    }
});
