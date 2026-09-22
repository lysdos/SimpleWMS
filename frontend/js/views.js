/* ============================================
 *  views.js  -  各模块 HTML 模板(已接入 i18n)
 *  每个函数返回 HTML 字符串,由 app.js 注入到 #view
 *  硬编码中文全部走 t('key'),切换语言时由 app.js 重渲染
 * ============================================ */

/* ---------- 轻量 SVG 折线图(无外部依赖) ---------- */
function renderLineChart(containerId, points, options) {
    options = options || {};
    const color = options.color || '#2563eb';
    const fillColor = options.fillColor || 'rgba(37,99,235,0.10)';
    const suffix = options.suffix || '';

    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = '';

    if (!points || points.length === 0) {
        el.innerHTML = '<div style="color:#9ca3af;font-size:13px;text-align:center;line-height:240px;">' + t('common.empty_data') + '</div>';
        return;
    }

    const W = el.clientWidth || 480;
    const H = 240;
    const padL = 42, padR = 12, padT = 14, padB = 26;
    const cw = W - padL - padR;
    const ch = H - padT - padB;

    const values = points.map(p => Number(p.value) || 0);
    let vMin = Math.min.apply(null, values);
    let vMax = Math.max.apply(null, values);
    if (vMin === vMax) { vMin = 0; vMax = vMax * 2 || 10; }
    const vRange = vMax - vMin;
    vMax = vMax + vRange * 0.10;

    const xStep = points.length > 1 ? cw / (points.length - 1) : 0;

    const yTicks = [];
    for (let i = 0; i <= 4; i++) {
        const v = vMin + (vMax - vMin) * i / 4;
        yTicks.push({ y: padT + ch - (ch * i / 4), label: Math.round(v) });
    }
    const xLabelIdx = points.length <= 1
        ? [0]
        : [0, Math.floor(points.length / 2), points.length - 1];

    const linePts = points.map((p, i) => {
        const x = padL + i * xStep;
        const ratio = (Number(p.value) - vMin) / (vMax - vMin);
        const y = padT + ch - ratio * ch;
        return { x, y, p };
    });
    const polyPts = linePts.map(pt => `${pt.x.toFixed(1)},${pt.y.toFixed(1)}`).join(' ');
    const linePath = linePts.map((pt, i) =>
        `${i === 0 ? 'M' : 'L'} ${pt.x.toFixed(1)} ${pt.y.toFixed(1)}`
    ).join(' ');
    const closePath = ` L ${linePts[linePts.length-1].x.toFixed(1)} ${(padT+ch).toFixed(1)} L ${padL.toFixed(1)} ${(padT+ch).toFixed(1)} Z`;

    let svg = `<svg viewBox="0 0 ${W} ${H}" width="100%" height="${H}" preserveAspectRatio="none" style="display:block;">`;
    yTicks.forEach(yt => {
        svg += `<line x1="${padL}" y1="${yt.y.toFixed(1)}" x2="${W-padR}" y2="${yt.y.toFixed(1)}" stroke="#eef2f6" stroke-width="1"/>`;
        svg += `<text x="${padL-6}" y="${(yt.y+3).toFixed(1)}" font-size="10" fill="#9ca3af" text-anchor="end">${yt.label}${suffix}</text>`;
    });
    xLabelIdx.forEach(i => {
        const pt = linePts[i];
        const d = points[i].date || '';
        svg += `<text x="${pt.x.toFixed(1)}" y="${(padT+ch+14).toFixed(1)}" font-size="10" fill="#9ca3af" text-anchor="middle">${d.slice(5)}</text>`;
    });
    svg += `<path d="${linePath}${closePath}" fill="${fillColor}" stroke="none"/>`;
    svg += `<path d="${linePath}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`;
    linePts.forEach((pt) => {
        svg += `<circle cx="${pt.x.toFixed(1)}" cy="${pt.y.toFixed(1)}" r="3" fill="#fff" stroke="${color}" stroke-width="2"/>`;
    });
    svg += `</svg>`;
    el.innerHTML = svg;

    const legendEl = document.getElementById(containerId + '-legend');
    if (legendEl) {
        const total = values.reduce((a, b) => a + b, 0);
        const peak = values.length ? Math.max.apply(null, values) : 0;
        const peakDate = points.find(p => Number(p.value) === peak)?.date || '-';
        legendEl.innerHTML = `${t('chart.peak')}: <strong style="color:${color}">${peak}${suffix}</strong> @ ${peakDate} · ${t('chart.total')}: <strong>${total}${suffix}</strong> · ${points.length} ${t('chart.days')}`;
    }
}

const Views = {

    /* ================== 1. 仪表盘 ================== */
    dashboard(stats) {
        const vc = stats.lowStockCount > 0 ? 'stat-card warn' : 'stat-card ok';
        return `
        <div class="page-head">
            <div>
                <h2>${t('dashboard.title')}</h2>
                <div class="desc">${t('dashboard.desc')}</div>
            </div>
        </div>
        <div class="stat-grid">
            <div class="stat-card">
                <div class="label">${t('dashboard.product_count')}</div>
                <div class="value">${stats.productCount}</div>
            </div>
            <div class="stat-card gold">
                <div class="label">${t('dashboard.total_qty')}</div>
                <div class="value">${stats.totalQuantity}</div>
            </div>
            <div class="stat-card ok">
                <div class="label">${t('dashboard.total_value')}</div>
                <div class="value">¥ ${formatMoney(stats.totalValue)}</div>
            </div>
            <div class="${vc}">
                <div class="label">${t('dashboard.low_stock')}</div>
                <div class="value">${stats.lowStockCount}</div>
            </div>
            <div class="${stats.expiringSoonCount > 0 ? 'stat-card warn' : 'stat-card ok'}">
                <div class="label">${t('dashboard.expiring_soon')}</div>
                <div class="value">${stats.expiringSoonCount ?? 0}</div>
            </div>
            <div class="${stats.expiredCount > 0 ? 'stat-card danger' : 'stat-card ok'}">
                <div class="label">${t('dashboard.expired')}</div>
                <div class="value">${stats.expiredCount ?? 0}</div>
            </div>
        </div>
        <div class="card" style="margin-top:24px;" id="expiring-card">
            <div class="card-title">${t('dashboard.expiring.title')}</div>
            <div id="expiring-list" style="color:var(--c-text-3);font-size:13px;">${t('common.loading')}</div>
        </div>
        <div class="split" style="margin-top:24px;">
            <div class="card">
                <div class="card-title">${t('dashboard.inv_trend.title')}</div>
                <div id="chart-inventory-trend" style="width:100%;height:240px;position:relative;">
                    <div style="color:#9ca3af;font-size:13px;text-align:center;line-height:240px;">${t('common.loading')}</div>
                </div>
                <div id="chart-inventory-trend-legend" style="font-size:12px;color:#9ca3af;margin-top:6px;"></div>
            </div>
            <div class="card">
                <div class="card-title">${t('dashboard.out_trend.title')}</div>
                <div id="chart-outbound-trend" style="width:100%;height:240px;position:relative;">
                    <div style="color:#9ca3af;font-size:13px;text-align:center;line-height:240px;">${t('common.loading')}</div>
                </div>
                <div id="chart-outbound-trend-legend" style="font-size:12px;color:#9ca3af;margin-top:6px;"></div>
            </div>
        </div>`;
    },

    /* 仪表盘:临期/过期批次明细列表 */
    expiringList(expiring, expired) {
        const badge = (expired) => expired
            ? `<span class="badge" style="background:#fee2e2;color:#b91c1c;">${t('inventory.expiry.expired')}</span>`
            : `<span class="badge" style="background:#fef3c7;color:#92400e;">${t('inventory.expiry.expiring')}</span>`;
        const row = (r, isExpired) => `
            <tr>
                <td><span style="font-family:var(--font-mono);font-size:12px;">${escapeHtml(r.code)}</span></td>
                <td><strong>${escapeHtml(r.sku)}</strong></td>
                <td>${escapeHtml(r.productName)}</td>
                <td>${Views.locLabel(r.locationCode)}</td>
                <td>${escapeHtml(r.expiryDate || '-')}</td>
                <td>${r.daysLeft < 0 ? '0' : r.daysLeft} ${t('inventory.expiry.day')}</td>
                <td>${badge(isExpired)}</td>
            </tr>`;
        const section = (title, list, isExpired, emptyKey) => `
            <div style="margin-bottom:10px;">
                <div style="font-size:13px;font-weight:600;margin-bottom:6px;">${title}</div>
                ${list.length === 0
                    ? `<div style="color:var(--c-text-3);font-size:13px;">${emptyKey}</div>`
                    : `<div class="table-wrap"><table class="table">
                        <thead><tr>
                            <th>${t('inventory.col.code')}</th><th>${t('inventory.col.sku')}</th>
                            <th>${t('inventory.col.product')}</th><th>${t('inventory.col.loc')}</th>
                            <th>${t('dashboard.expiry.col.date')}</th><th>${t('dashboard.expiry.col.left')}</th>
                            <th>${t('inventory.col.status')}</th>
                        </tr></thead>
                        <tbody>${list.map(r => row(r, isExpired)).join('')}</tbody>
                    </table></div>`}
            </div>`;
        return section(t('dashboard.expiring_soon'), expiring, false, t('dashboard.expiry.empty_ok'))
             + section(t('dashboard.expired'), expired, true, t('dashboard.expiry.empty_ok'));
    },

    /* ================== 2. 商品管理 ================== */
    products(products, suppliers, keyword, supplierFilter) {
        const rows = products.length === 0
            ? `<tr><td colspan="11" class="empty">${t('product.empty')}</td></tr>`
            : products.map(p => {
                const lowBadge = p.quantity <= p.lowStock
                    ? `<span class="badge low">${t('product.low_badge')}</span>` : '';
                const supp = p.defaultSupplier
                    ? escapeHtml(p.defaultSupplier)
                    : `<span style="color:#9ca3af;">${t('product.empty_supplier')}</span>`;
                return `
                <tr>
                    <td>${p.id}</td>
                    <td><strong>${escapeHtml(p.sku)}</strong></td>
                    <td>${escapeHtml(p.name)} ${lowBadge}</td>
                    <td>${escapeHtml(p.category || '')}</td>
                    <td>${escapeHtml(p.unit || '')}</td>
                    <td>${supp}</td>
                    <td class="num">${p.quantity}</td>
                    <td class="num">¥${formatMoney(p.unitPrice)}</td>
                    <td class="num">¥${formatMoney((p.unitPrice||0)*p.quantity)}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="viewProduct(${p.id})">${t('common.view')}</button>
                        <button class="btn-link" onclick="editProduct(${p.id})">${t('common.edit')}</button>
                        <button class="btn-link danger" onclick="delProduct(${p.id})">${t('common.delete')}</button>
                    </td>
                </tr>`;
            }).join('');
        const supplierOpts = (suppliers || []).map(s =>
            `<option value="${escapeHtml(s.name)}" ${supplierFilter === s.name ? 'selected' : ''}>${escapeHtml(s.name)}</option>`
        ).join('');
        return `
        <div class="page-head">
            <div>
                <h2>${t('product.title')}</h2>
                <div class="desc">${t('product.desc')}</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="newProduct()">${t('product.create')}</button>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <input class="input input-sm" id="kw-product" placeholder="${t('product.kw_placeholder')}" value="${escapeHtml(keyword||'')}" style="width:240px;">
                <select id="product-supplier-filter" class="select" style="width:150px;" onchange="searchProducts()">
                    <option value="">${t('common.all')} ${t('product.col.default_supplier')}</option>
                    ${supplierOpts}
                </select>
                <button class="btn btn-sm" onclick="searchProducts()">${t('common.search')}</button>
                <button class="btn btn-sm" onclick="document.getElementById('kw-product').value='';document.getElementById('product-supplier-filter').value='';searchProducts()">${t('common.clear')}</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:60px;">ID</th>
                        <th>${t('product.col.sku')}</th>
                        <th>${t('product.col.name')}</th>
                        <th>${t('product.col.category')}</th>
                        <th>${t('product.col.unit')}</th>
                        <th>${t('product.col.default_supplier')}</th>
                        <th class="num">${t('product.col.qty')}</th>
                        <th class="num">${t('product.col.price')}</th>
                        <th class="num">${t('product.col.value')}</th>
                        <th style="width:160px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    },

    /* ================== 3. 入库登记 ================== */
    inbound(products, recentOrders) {
        return `
        <div class="page-head">
            <div>
                <h2>${t('inbound.title')}</h2>
                <div class="desc">${t('inbound.desc')}</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="openNewOrderModal('IN')">${t('inbound.create')}</button>
            </div>
        </div>
        <div class="card">
            <div class="card-title" style="display:flex;align-items:center;justify-content:space-between;">
                <span id="inbound-recent-title">${t('inbound.recent_title')}</span>
                <span id="inbound-recent-tabs"></span>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th>${t('inbound.col.order_no')}</th>
                        <th style="width:90px;">${t('inbound.col.status')}</th>
                        <th>${t('inbound.col.external_no')}</th>
                        <th class="num">${t('inbound.col.items')}</th>
                        <th>${t('orders.col.createdBy')}</th>
                        <th style="width:160px;">${t('orders.col.created_at')}</th>
                        <th style="width:200px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody id="inbound-recent-tbody">${Views.inboundRecentRows(recentOrders)}</tbody>
                </table>
            </div>
            <div id="inbound-recent-pager" class="toolbar" style="margin-top:10px;justify-content:flex-end;"></div>
        </div>`;
    },

    inboundRecentRows(recentOrders) {
        recentOrders = recentOrders || [];
        if (recentOrders.length === 0) {
            return `<tr><td colspan="7" class="empty">${t('inbound.empty')}</td></tr>`;
        }
        return recentOrders.map(o => {
            const st = o.status || 'DRAFT';
            const actionBtns = (st === 'DRAFT')
                ? `<button class="btn-link" onclick="editInboundOrder(${o.id})">${t('common.edit')}</button>
                   <button class="btn-link" onclick="commitInboundOrder(${o.id}, '${escapeHtml(o.orderNo)}')">${t('orders.btn.commit')}</button>
                   <button class="btn-link danger" onclick="cancelInboundOrder(${o.id}, '${escapeHtml(o.orderNo)}')">${t('orders.btn.cancel')}</button>`
                : '';
            return `
            <tr>
                <td><strong>${escapeHtml(o.orderNo)}</strong></td>
                <td><span class="pill ${docStatusClass(st)}">${t('doc.' + st)}</span></td>
                <td>${escapeHtml(o.externalNo || '-')}</td>
                <td class="num">${o.itemCount || 0} / ${o.totalQuantity || 0}</td>
                <td>${escapeHtml(o.createdBy || '')}</td>
                <td>${formatDate(o.createdAt)}</td>
                <td class="actions">
                    <button class="btn-link" onclick="viewOrderDetail(${o.id}, 'IN')">${t('common.view')}</button>
                    ${actionBtns}
                </td>
            </tr>`;
        }).join('');
    },

    recentPager(prefix, page, pages, total, pageSize) {
        return `
            <span style="display:inline-flex;align-items:center;gap:6px;">
                <label style="font-size:13px;color:var(--c-text-3);">${t('common.page_size')}</label>
                <select class="select" style="width:70px;" onchange="change${prefix.charAt(0).toUpperCase() + prefix.slice(1)}RecentPageSize(this)">
                    <option value="10" ${pageSize===10?'selected':''}>10</option>
                    <option value="20" ${pageSize===20?'selected':''}>20</option>
                    <option value="30" ${pageSize===30?'selected':''}>30</option>
                </select>
            </span>
            <span style="display:inline-flex;align-items:center;gap:8px;">
                <span style="color:var(--c-text-3);font-size:13px;">${t('common.total')} ${total} ${t('common.items')} · ${t('common.page')} ${page} / ${pages}</span>
                <button class="btn btn-sm" ${page<=1?'disabled':''} onclick="goto${prefix.charAt(0).toUpperCase() + prefix.slice(1)}RecentPage(${page-1})">${t('common.prev')}</button>
                <button class="btn btn-sm" ${page>=pages?'disabled':''} onclick="goto${prefix.charAt(0).toUpperCase() + prefix.slice(1)}RecentPage(${page+1})">${t('common.next')}</button>
            </span>`;
    },

    /* ================== 4. 出库登记 ================== */
    outbound(products, recentOrders) {
        return `
        <div class="page-head">
            <div>
                <h2>${t('outbound.title')}</h2>
                <div class="desc">${t('outbound.desc')}</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="openNewOrderModal('OUT')">${t('outbound.create')}</button>
            </div>
        </div>
        <div class="card">
            <div class="card-title" style="display:flex;align-items:center;justify-content:space-between;">
                <span id="outbound-recent-title">${t('outbound.recent_title')}</span>
                <span id="outbound-recent-tabs"></span>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th>${t('outbound.col.order_no')}</th>
                        <th style="width:90px;">${t('outbound.col.status')}</th>
                        <th>${t('outbound.col.external_no')}</th>
                        <th>${t('outbound.col.customer')}</th>
                        <th class="num">${t('outbound.col.items')}</th>
                        <th>${t('orders.col.createdBy')}</th>
                        <th style="width:160px;">${t('orders.col.created_at')}</th>
                        <th style="width:200px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody id="outbound-recent-tbody">${Views.outboundRecentRows(recentOrders)}</tbody>
                </table>
            </div>
            <div id="outbound-recent-pager" class="toolbar" style="margin-top:10px;justify-content:flex-end;"></div>
        </div>`;
    },

    outboundRecentRows(recentOrders) {
        recentOrders = recentOrders || [];
        if (recentOrders.length === 0) {
            return `<tr><td colspan="8" class="empty">${t('outbound.empty')}</td></tr>`;
        }
        return recentOrders.map(o => {
            const st = o.status || 'DRAFT';
            const actionBtns = (st === 'DRAFT')
                ? `<button class="btn-link" onclick="editOutboundOrder(${o.id})">${t('common.edit')}</button>
                   <button class="btn-link" onclick="commitOutboundOrder(${o.id})">${t('orders.btn.commit')}</button>
                   <button class="btn-link danger" onclick="cancelOutboundOrder(${o.id})">${t('orders.btn.cancel')}</button>`
                : '';
            return `
            <tr>
                <td><strong>${escapeHtml(o.orderNo)}</strong></td>
                <td><span class="pill ${docStatusClass(st)}">${t('doc.' + st)}</span></td>
                <td>${escapeHtml(o.externalNo || '-')}</td>
                <td>${escapeHtml(o.customer || '-')}</td>
                <td class="num">${o.itemCount || 0} / ${o.totalQuantity || 0}</td>
                <td>${escapeHtml(o.createdBy || '')}</td>
                <td>${formatDate(o.createdAt)}</td>
                <td class="actions">
                    <button class="btn-link" onclick="viewOrderDetail(${o.id}, 'OUT')">${t('common.view')}</button>
                    ${actionBtns}
                </td>
            </tr>`;
        }).join('');
    },

    /* 卡片 tab 渲染 */
    applyCardTabs(prefix, current) {
        const slot = document.getElementById(prefix + '-recent-tabs');
        if (!slot) return;
        const tabs = [
            { v: 'ALL',       l: t('common.all') },
            { v: 'DRAFT',     l: t('doc.DRAFT') },
            { v: 'COMMITTED', l: t('doc.COMMITTED') },
            { v: 'CANCELLED', l: t('doc.CANCELLED') }
        ];
        const fn = (prefix === 'inbound') ? 'setInboundCardStatus' : 'setOutboundCardStatus';
        slot.innerHTML = tabs.map(tab => {
            const active = (tab.v === current);
            const bg = active ? 'var(--c-bg-elev)' : 'transparent';
            const color = active ? 'var(--c-brand)' : 'var(--c-text-3)';
            const border = active ? '1px solid var(--c-brand)' : '1px solid transparent';
            return `<button type="button" onclick="${fn}('${tab.v}')"
                style="margin-left:6px;padding:5px 14px;font-size:12.5px;border-radius:999px;cursor:pointer;background:${bg};color:${color};border:${border};font-weight:${active ? '600' : '400'};transition:all 0.18s;">${tab.l}</button>`;
        }).join('');
    },

    /* ================== 新增入库/出库单 弹窗 ================== */
    newOrderModal(type, products, suppliers, locations) {
        products = products || [];
        suppliers = suppliers || [];
        locations = locations || [];
        const isIn = type === 'IN';

        // 空占位项必须放在最前:否则商品列表非空时浏览器会自动选中第一项,
        // 预渲染的空白行会被当成"已选商品但数量为空",一提交就报"数量必须大于 0",
        // 整单无法保存(app.js readNewOrderItems 只在商品+数量都为空时才跳过该行)。
        const productOpts = products.length
            ? `<option value="">${t('new_order.placeholder.pick_product')}</option>` + products.map(p => {
                const low = p.quantity <= p.lowStock ? ` [${t('product.low_badge')}]` : '';
                const dflt = p.defaultSupplier ? ` (${t('new_order.label.supplier')}:${escapeHtml(p.defaultSupplier)})` : '';
                return `<option value="${p.id}" data-stock="${p.quantity}" data-default="${escapeHtml(p.defaultSupplier||'')}" data-default-id="${p.defaultSupplierId||''}" data-shelf-life="${p.defaultShelfLifeDays ?? ''}" data-loc="${escapeHtml(p.location||'')}">${escapeHtml(p.sku)} - ${escapeHtml(p.name)} (${p.quantity}${escapeHtml(p.unit||'')})${low}${dflt}</option>`;
            }).join('')
            : `<option value="">${t('new_order.placeholder.no_product')}</option>`;

        // 入库行的库位下拉:按仓库分组(一个商品可能因主仓放不下而分到副仓,
        // 所以要能逐行改)。空值 = 用商品的默认库位,后端会兜底。
        const locOpts = Views.locationOptgroups(
            locations.filter(l => l.enabled !== false),
            l => `<option value="${escapeHtml(l.code)}">${escapeHtml(l.code)}${l.name && l.name !== l.code ? ' / ' + escapeHtml(l.name) : ''}</option>`);

        const supplierOpts = suppliers
            .filter(s => s.enabled !== false)
            .map(s => `<option value="${escapeHtml(s.name)}">${escapeHtml(s.name)}</option>`)
            .join('');

        const blankRow = () => {
            const productCell  = `<select class="select nw-product" style="width:185px;">${productOpts}</select>`;
            const supplierCell = isIn
                ? `<select class="select nw-supplier" style="width:115px;"><option value="">${t('new_order.placeholder.not_set')}</option>${supplierOpts || `<option value="" disabled>${t('new_order.placeholder.no_supplier')}</option>`}</select>`
                : '';
            const prodDateCell = isIn
                ? `<input type="date" class="input nw-prod-date" style="width:115px;">`
                : '';
            const shelfCell = isIn
                ? `<input type="number" min="1" class="input nw-shelf-life" placeholder="—" style="width:70px;">`
                : '';
            const locCell = isIn
                ? `<select class="select nw-loc" style="width:170px;"><option value="">${t('new_order.loc.ph')}</option>${locOpts}</select>`
                : '';
            const qtyCell      = `<input type="number" min="1" class="input nw-qty" placeholder="${t('new_order.ph.qty')}" style="width:70px;">`;
            const remarkCell   = `<input class="input nw-row-remark" placeholder="${t('new_order.ph.row_remark')}" style="width:110px;">`;
            return `
            <tr class="nw-row">
                <td class="num row-idx">1</td>
                <td>${productCell}</td>
                ${isIn ? `<td>${supplierCell}</td>` : ''}
                ${isIn ? `<td>${prodDateCell}</td>` : ''}
                ${isIn ? `<td>${shelfCell}</td>` : ''}
                ${isIn ? `<td>${locCell}</td>` : ''}
                <td>${qtyCell}</td>
                <td>${remarkCell}</td>
                <td class="actions"><button class="btn-link danger" onclick="removeNewOrderRow(this)">${t('common.delete')}</button></td>
            </tr>`;
        };

        // 列宽要和 .modal-xwide(1240px)配套:每列实际占用 = 下面单元格上写的
        // inline 宽度 + 单元格左右内边距(各 14px)。所以 th 的 width 必须 >= inline 宽度 + 28,
        // 否则列被压缩,单元格内容会被裁掉(生产日期的日期值+日历图标就是这么被挡住的)。
        // 合计超出弹窗可用宽度时,「数量」会被推到表格横向滚动区外面,填数量得先左右拉表。
        // 改这里或改 inline 宽度都要重新算一遍总宽。
        const ths = isIn
            ? `<th style="width:36px;">#</th>
               <th style="width:213px;">${t('new_order.label.product')}<span class="req">*</span></th>
               <th style="width:143px;">${t('new_order.label.supplier')}</th>
               <th style="width:145px;">${t('new_order.label.production_date')}</th>
               <th style="width:98px;">${t('new_order.label.shelf_life')}</th>
               <th style="width:198px;">${t('new_order.label.loc')}</th>
               <th style="width:98px;">${t('new_order.label.qty')}<span class="req">*</span></th>
               <th style="min-width:138px;">${t('new_order.label.row_remark')}</th>
               <th style="width:64px;">${t('common.actions')}</th>`
            : `<th style="width:36px;">#</th>
               <th style="width:240px;">${t('new_order.label.product')}<span class="req">*</span></th>
               <th style="width:98px;">${t('new_order.label.qty')}<span class="req">*</span></th>
               <th>${t('new_order.label.row_remark')}</th>
               <th style="width:66px;">${t('common.actions')}</th>`;

        const headFields = isIn ? `
            <div class="form-field" style="flex:1;">
                <label>${t('new_order.label.external_no')}</label>
                <input id="nw-external-no" class="input" placeholder="${t('new_order.ph.external_no_in')}">
            </div>
        ` : `
            <div class="form-field" style="flex:1;">
                <label>${t('new_order.label.customer')}</label>
                <input id="nw-customer" class="input" placeholder="${t('new_order.ph.customer')}">
            </div>
            <div class="form-field" style="flex:1;">
                <label>${t('new_order.label.external_no')}</label>
                <input id="nw-external-no" class="input" placeholder="${t('new_order.ph.external_no_out')}">
            </div>
        `;
        const mergeTip = isIn ? t('new_order.merge_tip_in') : t('new_order.merge_tip_out');
        return `
        <div class="form-row">
            ${headFields}
            <div class="form-field" style="flex:2;">
                <label>${t('new_order.label.remark')}</label>
                <input id="nw-remark" class="input" placeholder="${t('new_order.ph.remark')}">
            </div>
        </div>
        <div class="card-title" style="margin-top:8px;">${isIn ? t('inbound.items') : t('outbound.items')}${mergeTip}</div>
        <div class="table-wrap">
            <table class="table">
                <thead><tr>${ths}</tr></thead>
                <tbody id="nw-rows">${blankRow() + blankRow() + blankRow()}</tbody>
            </table>
        </div>
        <div class="form-row" style="margin-top:10px;justify-content:space-between;align-items:center;">
            <button type="button" class="btn btn-sm btn-success" onclick="addNewOrderRow()">
                <span style="font-size:14px;font-weight:600;margin-right:4px;">+</span> ${t('new_order.btn.add_row')}
            </button>
            <span style="font-size:12px;color:var(--c-text-3);">${isIn ? t('inbound.tip') : t('outbound.tip')}</span>
        </div>
        <input type="hidden" id="nw-type" value="${type}">`;
    },

    /* ================== 编辑入库/出库单 弹窗 ================== */
    editOrderModal(type, order) {
        const isIn = type === 'IN';
        return `
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>${t('new_order.label.external_no')}</label>
                <input id="edit-external-no" class="input" value="${escapeHtml(order.externalNo || '')}">
            </div>
            <div class="form-field" style="flex:1;">
                <label>${t('new_order.label.remark')}</label>
                <input id="edit-remark" class="input" value="${escapeHtml(order.remark || '')}">
            </div>
        </div>
        <div style="margin-top:12px;font-size:13px;color:var(--c-text-3);">
            ${t('orders.col.order_no')}: <strong>${escapeHtml(order.orderNo)}</strong>
            · ${t('common.status')}: <span class="pill ${docStatusClass(order.status)}">${t('doc.' + (order.status || 'DRAFT'))}</span>
            · ${t('orders.col.created_at')}: ${formatDate(order.createdAt)}
        </div>`;
    },

    /**
     * 库位显示:带上所属仓库,如「默认仓库 / A-01-01」。
     *
     * 单据明细、库存单元里存的是**库位码快照**(不是外键),后端响应里没有仓库名,
     * 所以靠 app.js 维护的 state.locMap(码 → 仓库名)反查。多仓库之后光看一个码
     * 不知道货在哪个仓,这个函数就是为了让所有"只印码"的地方都能看出仓库。
     *
     * 查不到(库位已删除、locMap 还没加载)退回原码,不显示成空白 ——
     * 历史单据照样要看。返回值已经过 HTML 转义,调用方不要再包 escapeHtml。
     */
    locLabel(code) {
        if (!code) return '-';
        const esc = escapeHtml(code);
        // views.js 与 app.js 都是经典脚本,共用全局词法作用域;调用发生在渲染期,
        // 那时 app.js 早已执行完,不存在 TDZ 问题。
        const map = state.locMap;
        if (!map || !(code in map)) return esc;
        const wh = map[code] || t('location.warehouse.none');
        return escapeHtml(wh) + ' / ' + esc;
    },

    /**
     * 库位文案。空库位码(老单元没记库位)说「未指定库位」——
     * 全系统一个说法:前端分区抬头、CSV 的「库位」列、PDA 都对齐这一个词。
     */
    locText(code) {
        if (!code) return escapeHtml(t('outbound_order.loc.unassigned'));
        return Views.locLabel(code);
    },

    /**
     * 拣货分布:把后端给的「库位码 → 件数」渲染成
     * "主仓库 / A-01-01 × 30 · 副仓库 / B-02-02 × 30"。
     *
     * 出库改成"自动跨库位找货"后,明细行上不再有单一库位 ——
     * 货实际在哪儿、该去哪儿拿,只有锁定到的单元说得清。所以这是给现场看的
     * 拣货依据,不是装饰。
     *
     * 数据**取后端算好的 picks**,不再由前端拿单元自己分组:同一套数算两遍的话,
     * 以后改取货规则(比如优先同仓)容易只改一边 —— PDA 那边读的就是这个字段。
     *
     * @param picks 库位码 → 件数(见 OutboundOrderItem.picks)
     */
    pickDist(picks) {
        if (!picks) return '';
        return Object.entries(picks)
            .map(([loc, qty]) => `${Views.locText(loc)} × ${qty}`)
            .join(' · ');
    },

    /**
     * 按**仓库**把码分组渲染。入参是「库位码 → 码列表」(见 OutboundOrderItem.codesByLocation)。
     *
     * 分组键必须是仓库,不能按库位码排序:主仓有 A-01-01/Z-99-99、副仓有 M-01-01 时,
     * 按码排会把主仓劈成两段,正好还是"看不出哪个码在哪个仓"。
     * 仓库名靠 state.locMap 反查(与 Views.locLabel 同一份数据),按仓库名排序输出稳定。
     */
    codesByWarehouse(codesByLocation) {
        if (!codesByLocation) return '';
        const locMap = state.locMap || {};
        const groups = new Map();   // 仓库名 → [{loc, codes}]
        Object.entries(codesByLocation).forEach(([loc, codes]) => {
            const wh = loc ? (locMap[loc] || t('location.warehouse.none')) : t('outbound_order.loc.unassigned');
            if (!groups.has(wh)) groups.set(wh, []);
            groups.get(wh).push({ loc, codes: codes || [] });
        });
        return Array.from(groups.entries())
            .sort((a, b) => a[0].localeCompare(b[0], 'zh'))
            .map(([wh, locs]) => `
                <div style="margin-bottom:6px;">
                    <div style="font-size:12px;font-weight:600;color:var(--c-text-2);margin-bottom:2px;">${escapeHtml(wh)}</div>
                    ${locs.map(x => `
                        <div style="margin-bottom:2px;">
                            <span style="font-size:11px;color:var(--c-text-3);margin-right:6px;">${Views.locText(x.loc)}</span>
                            ${x.codes.map(c => `<span class="code-chip">${escapeHtml(c)}</span>`).join('')}
                        </div>`).join('')}
                </div>`).join('');
    },

    /* ================== 出库单详情 Modal ================== */
    outboundOrderModal(o) {
        const rows = (o.items || []).map((it, i) => {
            // it.codes 是后端按行填好的码字符串列表(不是库存单元对象);
            // 按仓库分组显示要看 it.codesByLocation —— 与 codes 同源同序,键集相同
            const codes = it.codes || [];
            const codesText = codes.length === 0
                ? `<span style="color:#9ca3af;font-size:12px;">${t('outbound_order.no_barcode')}</span>`
                : Views.codesByWarehouse(it.codesByLocation);
            // 库位:手工指定过就印那个库位;自动单印真实拣货分布 —— "去哪儿拿货"看这一格
            const pickText = Views.pickDist(it.picks);
            return `
            <tr>
                <td class="num">${i + 1}</td>
                <td><strong>${escapeHtml(it.sku)}</strong></td>
                <td>${escapeHtml(it.productName)}</td>
                <td>${it.location ? Views.locLabel(it.location) : (pickText || t('outbound_order.loc.auto'))}</td>
                <td class="num">${it.quantity}</td>
                <td class="num">¥${formatMoney(it.unitPrice)}</td>
                <td>${escapeHtml(it.rowRemark || '')}</td>
            </tr>
            <tr class="barcode-row">
                <td colspan="7" style="background:var(--c-bg-soft);padding:6px 12px;">
                    <div style="font-size:12px;color:var(--c-text-3);margin-bottom:4px;">
                        ${t('outbound_order.barcode.count', { n: codes.length })}
                    </div>
                    ${codesText}
                </td>
            </tr>`;
        }).join('');
        return `
        <div class="order-print" id="order-print-area">
            <div class="order-head">
                <h2 style="margin:0 0 6px 0;">${t('outbound_order.title')}</h2>
                <div class="order-meta">
                    <div><label>${t('outbound_order.label.order_no')}:</label><span>${escapeHtml(o.orderNo)}</span></div>
                    <div><label>${t('outbound_order.label.created_at')}:</label><span>${formatDate(o.createdAt)}</span></div>
                    <div><label>${t('outbound_order.label.createdBy')}:</label><span>${escapeHtml(o.createdBy || '')}</span></div>
                    <div><label>${t('outbound_order.label.customer')}:</label><span>${escapeHtml(o.customer || '-')}</span></div>
                    <div><label>${t('outbound_order.label.external_no')}:</label><span>${escapeHtml(o.externalNo || '-')}</span></div>
                    <div><label>${t('outbound_order.label.remark')}:</label><span>${escapeHtml(o.remark || '-')}</span></div>
                </div>
            </div>
            <table class="table" style="margin-top:8px;">
                <thead><tr>
                    <th style="width:50px;">${t('outbound_order.col.idx')}</th>
                    <th>${t('outbound_order.col.sku')}</th>
                    <th>${t('outbound_order.col.product')}</th>
                    <th>${t('outbound_order.col.loc')}</th>
                    <th class="num">${t('outbound_order.col.qty')}</th>
                    <th class="num">${t('outbound_order.col.price')}</th>
                    <th>${t('outbound_order.col.row_remark')}</th>
                </tr></thead>
                <tbody>${rows || `<tr><td colspan="7" class="empty">${t('outbound_order.empty')}</td></tr>`}</tbody>
                <tfoot>
                    <tr>
                        <td colspan="4" style="text-align:right;font-weight:600;">${t('outbound_order.total')}</td>
                        <td class="num" style="font-weight:600;">${o.totalQuantity || 0}</td>
                        <td colspan="2"></td>
                    </tr>
                </tfoot>
            </table>
            <div class="order-sign" style="margin-top:24px;display:flex;justify-content:space-between;color:var(--c-text-2);font-size:13px;">
                <div>${t('outbound_order.sign1')}:_______________</div>
                <div>${t('outbound_order.sign2')}:_______________</div>
                <div>${t('outbound_order.print_time')}:<span id="order-print-time"></span></div>
            </div>
        </div>
        <div class="form-row" style="margin-top:16px;justify-content:flex-end;">
            <button class="btn btn-sm" onclick="printOutboundOrder()">🖨 ${t('common.print')}</button>
            <a class="btn btn-sm" href="/api/outbound/orders/${o.id}/export.csv" style="margin-left:8px;text-decoration:none;">⬇ ${t('common.export')} CSV</a>
            ${o.status === 'DRAFT' ? `
                <button class="btn btn-sm btn-primary" style="margin-left:8px;" onclick="commitOutboundOrder(${o.id})">${t('outbound_order.btn.commit')}</button>
                <button class="btn btn-sm btn-danger" style="margin-left:8px;" onclick="cancelOutboundOrder(${o.id})">${t('outbound_order.btn.cancel')}</button>
            ` : ''}
        </div>`;
    },

    /* 说明:出库单弹窗里原有的「📡 UHF 出库扫码」面板已移除。
     * UHF 读卡器是**可选工具**,不是出库的必经环节:
     *   - 直接在出库单里点「提交出库」= 出货完成;
     *   - 或用 UHF 终端扫码后再提交,同样 = 出货完成。
     * UHF 终端自己拉取 DRAFT 单(轮询刷新),不依赖前台"下发"。
     * 派单相关后端接口(/dispatches、/claim)保留,供 UHF 终端自动认领使用。 */

    /* ================== 5. 出入库单(列表) ================== */
    orders(type, data, filter) {
        const orders = (data && data.list) || [];
        const total  = (data && data.total) || 0;
        const page   = (data && data.page)  || 1;
        const size   = (data && data.pageSize) || 10;
        const pages  = Math.max(1, Math.ceil(total / size));

        const beginVal  = (filter && filter.beginDate) || '';
        const endVal    = (filter && filter.endDate)   || '';
        const kwVal     = (filter && filter.keyword)   || '';
        const opVal     = (filter && filter.createdBy)  || '';
        const statusVal = (filter && filter.status)    ?? '';

        const rows = orders.length === 0
            ? `<tr><td colspan="11" class="empty">${t('orders.empty')}</td></tr>`
            : orders.map(o => {
                const isIn = type === 'ALL'
                    ? (o.type === 'IN' || (o.orderNo && o.orderNo.startsWith('IN-')))
                    : (type === 'IN');
                const tBadge = isIn
                    ? `<span class="badge in">${t('orders.badge.in')}</span>`
                    : `<span class="badge out">${t('orders.badge.out')}</span>`;
                // partner 由后端分开给(入库=行级供应商 / 出库=领用方),与「外部单号」列各归各
                const partner = escapeHtml(o.partner || '-');
                return `
                <tr>
                    <td><input type="checkbox" class="od-cb" data-id="${o.id}" data-type="${isIn ? 'IN' : 'OUT'}" onchange="updateExportBtns()"></td>
                    <td>${o.id}</td>
                    <td>${tBadge}</td>
                    <td><strong>${escapeHtml(o.orderNo)}</strong></td>
                    <td>${partner}</td>
                    <td>${escapeHtml(o.externalNo || '-')}</td>
                    <td class="num">${o.itemCount || 0}</td>
                    <td class="num">${o.totalQuantity || 0}</td>
                    <td>${escapeHtml(o.createdBy || '')}</td>
                    <td>${formatDate(o.createdAt)}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="viewOrderDetail(${o.id}, '${isIn ? 'IN' : 'OUT'}')">${t('common.view')}</button>
                    </td>
                </tr>`;
            }).join('');

        return `
        <div class="page-head">
            <div>
                <h2>${t('orders.title')}</h2>
                <div class="desc">${t('orders.desc')}</div>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <select id="od-type" class="select" style="width:120px;" onchange="searchOrders()">
                    <option value="ALL" ${type==='ALL'?'selected':''}>${t('common.all')}</option>
                    <option value="IN"  ${type==='IN' ?'selected':''}>${t('orders.type.in')}</option>
                    <option value="OUT" ${type==='OUT'?'selected':''}>${t('orders.type.out')}</option>
                </select>
                <span style="display:inline-flex;align-items:center;gap:4px;"><label style="font-size:13px;color:var(--c-text-3);">${t('orders.begin_label')}</label><input id="od-begin" type="date" class="input input-sm" value="${beginVal}"></span>
                <span style="display:inline-flex;align-items:center;gap:4px;"><label style="font-size:13px;color:var(--c-text-3);">${t('orders.end_label')}</label><input id="od-end" type="date" class="input input-sm" value="${endVal}"></span>
                <input id="od-kw" class="input input-sm" placeholder="${t('orders.kw_placeholder')}" value="${escapeHtml(kwVal)}" style="width:280px;">
                <input id="od-op" class="input input-sm" placeholder="${t('orders.op_placeholder')}" value="${escapeHtml(opVal)}" style="width:140px;">
                <label style="font-size:13px;color:var(--c-text-3);">${t('common.status')}</label>
                <select id="od-status" class="select" style="width:110px;" onchange="searchOrders()">
                    <option value=""           ${statusVal===''           ?'selected':''}>${t('common.all')}</option>
                    <option value="DRAFT"      ${statusVal==='DRAFT'      ?'selected':''}>${t('doc.DRAFT')}</option>
                    <option value="COMMITTED"  ${statusVal==='COMMITTED'  ?'selected':''}>${t('doc.COMMITTED')}</option>
                    <option value="CANCELLED"  ${statusVal==='CANCELLED'  ?'selected':''}>${t('doc.CANCELLED')}</option>
                </select>
                <button class="btn btn-sm btn-primary" onclick="searchOrders()">${t('common.query')}</button>
                <button class="btn btn-sm" onclick="resetOrderFilter()">${t('common.reset')}</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:36px;"><input type="checkbox" id="od-cb-all" onchange="toggleAllOrderCbs(this)"></th>
                        <th style="width:60px;">${t('orders.col.id')}</th>
                        <th style="width:80px;">${t('orders.col.type')}</th>
                        <th>${t('orders.col.order_no')}</th>
                        <th>${type==='OUT' ? t('orders.partner_customer')
                             : type==='IN' ? t('orders.partner_supplier')
                             : t('orders.partner')}</th>
                        <th>${t('orders.col.external_no')}</th>
                        <th class="num">${t('orders.col.item_count')}</th>
                        <th class="num">${t('orders.col.total_qty')}</th>
                        <th>${t('orders.col.createdBy')}</th>
                        <th style="width:160px;">${t('orders.col.created_at')}</th>
                        <th style="width:100px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div class="toolbar" style="margin-top:14px;justify-content:space-between;">
                <span style="display:inline-flex;align-items:center;gap:8px;">
                    <button class="btn btn-sm btn-success" id="btn-export-selected" disabled onclick="exportSelectedOrders()">${t('orders.export_selected')}</button>
                    <button class="btn btn-sm" onclick="exportAllOrders()">${t('orders.export_all')}</button>
                    <span id="od-selected-count" style="font-size:12px;color:var(--c-text-3);"></span>
                </span>
                <span style="display:inline-flex;align-items:center;gap:8px;">
                    <span style="display:inline-flex;align-items:center;gap:6px;">
                        <label style="font-size:13px;color:var(--c-text-3);">${t('common.page_size')}</label>
                        <select id="od-page-size" class="select" style="width:70px;" onchange="searchOrders()">
                            <option value="10" ${size===10?'selected':''}>10</option>
                            <option value="20" ${size===20?'selected':''}>20</option>
                            <option value="30" ${size===30?'selected':''}>30</option>
                        </select>
                    </span>
                    <span style="color:var(--c-text-3);font-size:13px;">${t('common.total')} ${total} ${t('common.items')} · ${t('common.page')} ${page} / ${pages}</span>
                    <button class="btn btn-sm" ${page<=1?'disabled':''} onclick="gotoOrderPage(${page-1})">${t('common.prev')}</button>
                    <button class="btn btn-sm" ${page>=pages?'disabled':''} onclick="gotoOrderPage(${page+1})">${t('common.next')}</button>
                </span>
            </div>
        </div>`;
    },

    /* ================== 入库单详情 Modal ================== */
    inboundOrderModal(o) {
        const rows = (o.items || []).map((it, i) => {
            // it.codes 是后端**按明细行**填好的码字符串列表(按 inbound_item_id 精确归属,
            // 见 InboundService.getOrderWithCodes)。同一商品拆成多行时每行只拿到自己的码 ——
            // 早先是前端拉 /by-inbound 再按 SKU 分组,那样两行会各显示该 SKU 的全部码。
            const codes = it.codes || [];
            const codesText = codes.length === 0
                ? `<span style="color:#9ca3af;font-size:12px;">${t('inbound_order.no_barcode')}</span>`
                : '<div class="codes-list">' + codes.map(c => `<span class="code-chip">${escapeHtml(c)}</span>`).join('') + '</div>';
            const batchText = (it.productionDate || '-')
                + (it.shelfLifeDays != null ? ` / ${it.shelfLifeDays}${t('inbound.shelf_life_unit')}` : '');
            return `
            <tr>
                <td class="num">${i + 1}</td>
                <td>${escapeHtml(it.supplier || '-')}</td>
                <td><strong>${escapeHtml(it.sku)}</strong></td>
                <td>${escapeHtml(it.productName)}</td>
                <td>${Views.locLabel(it.location)}</td>
                <td>${escapeHtml(batchText)}</td>
                <td class="num">${it.quantity}</td>
                <td class="num">¥${formatMoney(it.unitPrice)}</td>
                <td>${escapeHtml(it.rowRemark || '')}</td>
            </tr>
            <tr class="barcode-row">
                <td colspan="9" style="background:var(--c-bg-soft);padding:6px 12px;">
                    <div style="font-size:12px;color:var(--c-text-3);margin-bottom:4px;">
                        ${t('inbound_order.barcode.count', { n: codes.length })}
                    </div>
                    ${codesText}
                </td>
            </tr>`;
        }).join('');
        return `
        <div class="order-print" id="order-print-area">
            <div class="order-head">
                <h2 style="margin:0 0 6px 0;">${t('inbound_order.title')}</h2>
                <div class="order-meta">
                    <div><label>${t('inbound_order.label.order_no')}:</label><span>${escapeHtml(o.orderNo)}</span></div>
                    <div><label>${t('inbound_order.label.created_at')}:</label><span>${formatDate(o.createdAt)}</span></div>
                    <div><label>${t('inbound_order.label.createdBy')}:</label><span>${escapeHtml(o.createdBy || '')}</span></div>
                    <div><label>${t('inbound_order.label.external_no')}:</label><span>${escapeHtml(o.externalNo || '-')}</span></div>
                    <div><label>${t('inbound_order.label.remark')}:</label><span>${escapeHtml(o.remark || '-')}</span></div>
                </div>
            </div>
            <table class="table" style="margin-top:8px;">
                <thead><tr>
                    <th style="width:50px;">${t('inbound_order.col.idx')}</th>
                    <th style="width:160px;">${t('inbound_order.col.supplier')}</th>
                    <th>${t('inbound_order.col.sku')}</th>
                    <th>${t('inbound_order.col.product')}</th>
                    <th>${t('inbound_order.col.loc')}</th>
                    <th>${t('inbound_order.col.batch')}</th>
                    <th class="num">${t('inbound_order.col.qty')}</th>
                    <th class="num">${t('inbound_order.col.price')}</th>
                    <th>${t('inbound_order.col.row_remark')}</th>
                </tr></thead>
                <tbody>${rows || `<tr><td colspan="9" class="empty">${t('inbound_order.empty')}</td></tr>`}</tbody>
                <tfoot>
                    <tr>
                        <td colspan="6" style="text-align:right;font-weight:600;">${t('inbound_order.total')}</td>
                        <td class="num" style="font-weight:600;">${o.totalQuantity || 0}</td>
                        <td colspan="2"></td>
                    </tr>
                </tfoot>
            </table>
            <div class="order-sign" style="margin-top:24px;display:flex;justify-content:space-between;color:var(--c-text-2);font-size:13px;">
                <div>${t('inbound_order.sign1')}:_______________</div>
                <div>${t('inbound_order.sign2')}:_______________</div>
                <div>${t('inbound_order.print_time')}:<span id="order-print-time"></span></div>
            </div>
        </div>
        <div class="form-row" style="margin-top:16px;justify-content:flex-end;">
            <button class="btn btn-sm" onclick="printInboundOrder()">🖨 ${t('common.print')}</button>
            <button class="btn btn-sm" onclick="printAllInboundBarcodes(${o.id})">${t('inbound_order.btn.print_all')}</button>
            <a class="btn btn-sm" href="/api/inbound/orders/${o.id}/export.csv" style="margin-left:8px;text-decoration:none;">⬇ ${t('common.export')} CSV</a>
            ${o.status === 'DRAFT' ? `
                <button class="btn btn-sm btn-primary" style="margin-left:8px;" onclick="commitInboundOrder(${o.id}, '${escapeHtml(o.orderNo)}')">${t('inbound_order.btn.commit')}</button>
                <button class="btn btn-sm btn-danger" style="margin-left:8px;" onclick="cancelInboundOrder(${o.id}, '${escapeHtml(o.orderNo)}')">${t('inbound_order.btn.cancel')}</button>
            ` : ''}
        </div>`;
    },

    /* ================== 商品详情 Modal 内容 ================== */
    productDetail(p) {
        p = p || {};
        const supp = p.defaultSupplier
            ? escapeHtml(p.defaultSupplier)
            : `<span style="color:#9ca3af;">${t('product.empty_supplier')}</span>`;
        return `
        <div style="display:grid;grid-template-columns:max-content 1fr;gap:8px 16px;font-size:14px;">
            <div style="color:var(--c-text-3);">ID</div><div>${p.id}</div>
            <div style="color:var(--c-text-3);">${t('product.col.sku')}</div><div><strong>${escapeHtml(p.sku)}</strong></div>
            <div style="color:var(--c-text-3);">${t('product.col.name')}</div><div>${escapeHtml(p.name)}</div>
            <div style="color:var(--c-text-3);">${t('product.col.category')}</div><div>${escapeHtml(p.category || '-')}</div>
            <div style="color:var(--c-text-3);">${t('product.col.unit')}</div><div>${escapeHtml(p.unit || '-')}</div>
            <div style="color:var(--c-text-3);">${t('product.col.default_supplier')}</div><div>${supp}</div>
            <div style="color:var(--c-text-3);">${t('product.col.qty')}</div><div>${p.quantity}</div>
            <div style="color:var(--c-text-3);">${t('product.col.price')}</div><div>¥${formatMoney(p.unitPrice)}</div>
            <div style="color:var(--c-text-3);">${t('product.col.value')}</div><div>¥${formatMoney((p.unitPrice||0)*p.quantity)}</div>
            <div style="color:var(--c-text-3);">${t('product.form.low')}</div><div>${p.lowStock ?? '-'}</div>
            <div style="color:var(--c-text-3);">${t('product.form.loc')}</div><div>${Views.locLabel(p.location)}</div>
            <div style="color:var(--c-text-3);">${t('product.form.shelf_life')}</div><div>${p.defaultShelfLifeDays ?? '-'}</div>
            <div style="color:var(--c-text-3);">${t('product.form.warn_days')}</div><div>${p.expiryWarnDays ?? '-'}</div>
        </div>`;
    },

    /* ================== 商品表单 Modal 内容 ================== */
    productForm(p, suppliers, locations) {
        p = p || {};
        suppliers = suppliers || [];
        locations = locations || [];
        const selected = (p.suppliers || []).map(s => s.id);
        const defaultId = (p.suppliers || []).find(s => s.isDefault)?.id
                       || p.defaultSupplierId
                       || (selected[0] || '');
        const supplierItems = suppliers.map(s => ({ value: s.id, label: s.name }));
        const defaultOptions = suppliers.map(s =>
            `<option value="${s.id}" ${s.id===defaultId?'selected':''}>${escapeHtml(s.name)}</option>`
        ).join('');
        const enabledLocs = locations.filter(l => l.enabled !== false);
        const enabledCodes = new Set(enabledLocs.map(l => l.code));
        const currentLocCode = p.location || '';
        const extraLoc = (currentLocCode && !enabledCodes.has(currentLocCode))
            ? [{ code: currentLocCode, name: t('location.disabled_marker'), enabled: true }] : [];
        // 按所属仓库分组(多仓库后平铺列表太长);"不选"置顶、已停用的当前值垫底
        const locLabel = l => {
            const sel = l.code === currentLocCode ? ' selected' : '';
            const nm = (l.name && l.name !== l.code) ? ' / ' + escapeHtml(l.name) : '';
            return `<option value="${escapeHtml(l.code)}"${sel}>${escapeHtml(l.code)}${nm}</option>`;
        };
        // 一个可选的库位都没有时,别只放一条「(不选)」—— 库位是必填,那是个走不通的死胡同;
        // 这时只放一条禁用提示,指路到基础管理。(编辑一个仍指向已停用库位的商品时 extraLoc 非空,
        // 走下面正常分支,当前值会作为「(已停用或已删除)」保留下来。)
        const locSelectOptions =
            (enabledLocs.length === 0 && extraLoc.length === 0)
                ? `<option value="" disabled selected>${t('location.picker.empty')}</option>`
                : `<option value="" ${currentLocCode?'':'selected'}>${t('location.empty_marker')}</option>`
                  + Views.locationOptgroups(enabledLocs, locLabel)
                  + extraLoc.map(locLabel).join('');

        return `
        <div class="form-row">
            <div class="form-field">
                <label>${t('product.form.sku')}<span class="req">*</span></label>
                <input id="pf-sku" class="input" value="${escapeHtml(p.sku||'')}" placeholder="${t('product.form.sku_placeholder')}">
            </div>
            <div class="form-field">
                <label>${t('product.form.name')}<span class="req">*</span></label>
                <input id="pf-name" class="input" value="${escapeHtml(p.name||'')}" placeholder="${t('product.form.name')}">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field">
                <label>${t('product.form.cat')}</label>
                <input id="pf-cat" class="input" value="${escapeHtml(p.category||'')}" placeholder="${t('product.form.cat_placeholder')}">
            </div>
            <div class="form-field">
                <label>${t('product.form.unit')}</label>
                <input id="pf-unit" class="input" value="${escapeHtml(p.unit||'')}" placeholder="${t('product.form.unit_placeholder')}">
            </div>
            <div class="form-field">
                <label>${t('product.form.loc')}<span class="req">*</span></label>
                <select id="pf-loc" class="select">
                    ${locSelectOptions}
                </select>
            </div>
        </div>
        <div class="form-row">
            <div class="form-field">
                <label>${t('product.form.qty')}</label>
                <input id="pf-qty" type="number" min="0" class="input" value="${p.quantity ?? 0}">
            </div>
            <div class="form-field">
                <label>${t('product.form.price')}</label>
                <input id="pf-price" type="number" min="0" step="0.01" class="input" value="${p.unitPrice ?? 0}">
            </div>
            <div class="form-field">
                <label>${t('product.form.low')}</label>
                <input id="pf-low" type="number" min="0" class="input" value="${p.lowStock ?? 10}">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field">
                <label>${t('product.form.shelf_life')}</label>
                <input id="pf-shelf-life" type="number" min="0" class="input" value="${p.defaultShelfLifeDays ?? ''}" placeholder="—">
            </div>
            <div class="form-field">
                <label>${t('product.form.warn_days')}</label>
                <input id="pf-warn-days" type="number" min="0" class="input" value="${p.expiryWarnDays ?? ''}" placeholder="30">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:2;">
                <label>${t('product.form.suppliers')}</label>
                <div class="ms-head">
                    <input id="pf-supplier-filter" class="input" placeholder="${t('product.form.suppliers_filter_placeholder')}">
                    <span class="ms-count" id="pf-supplier-list-count"></span>
                </div>
                <div class="ms-list" id="pf-supplier-list">${Views.multiCheckListItems(supplierItems, {
                    selected,
                    empty: t('product.form.empty_supplier'),
                    nomatch: t('product.form.suppliers_filter_nomatch')
                })}</div>
            </div>
            <div class="form-field">
                <label>${t('product.form.default_supplier')}</label>
                <select id="pf-default-supplier" class="select">
                    <option value="">—</option>
                    ${defaultOptions}
                </select>
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>${t('common.remark')}</label>
                <input id="pf-remark" class="input" value="${escapeHtml(p.remark||'')}" placeholder="${t('new_order.ph.remark')}">
            </div>
        </div>
        <input type="hidden" id="pf-id" value="${p.id || ''}">`;
    },

    /* ================== 供应商管理列表 ================== */
    suppliers(suppliers, statusFilter) {
        const rows = suppliers.length === 0
            ? `<tr><td colspan="8" class="empty">${t('supplier.empty')}</td></tr>`
            : suppliers.map(s => `
                <tr>
                    <td>${s.id}</td>
                    <td><strong>${escapeHtml(s.name)}</strong></td>
                    <td>${escapeHtml(s.contact || '')}</td>
                    <td>${escapeHtml(s.phone || '')}</td>
                    <td>${escapeHtml(s.email || '')}</td>
                    <td>${s.enabled === false ? `<span class="badge low">${t('supplier.status.disabled')}</span>` : `<span class="badge ok">${t('supplier.status.enabled')}</span>`}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="viewSupplier(${s.id})">${t('common.view')}</button>
                        <button class="btn-link" onclick="editSupplier(${s.id})">${t('common.edit')}</button>
                        <button class="btn-link danger" onclick="delSupplier(${s.id})">${t('common.delete')}</button>
                    </td>
                </tr>`).join('');
        return `
        <div class="page-head">
            <div>
                <h2>${t('supplier.title')}</h2>
                <div class="desc">${t('supplier.desc')}</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="newSupplier()">${t('supplier.create')}</button>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <input id="sup-kw" class="input input-sm" placeholder="${t('supplier.kw_placeholder')}" style="width:240px;">
                <select id="sup-status-filter" class="select" style="width:110px;" onchange="searchSuppliers()">
                    <option value="" ${statusFilter===''?'selected':''}>${t('common.all')}</option>
                    <option value="enabled" ${statusFilter==='enabled'?'selected':''}>${t('supplier.status.enabled')}</option>
                    <option value="disabled" ${statusFilter==='disabled'?'selected':''}>${t('supplier.status.disabled')}</option>
                </select>
                <button class="btn btn-sm" onclick="searchSuppliers()">${t('common.search')}</button>
                <button class="btn btn-sm" onclick="document.getElementById('sup-kw').value='';document.getElementById('sup-status-filter').value='';searchSuppliers()">${t('common.clear')}</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:60px;">ID</th>
                        <th>${t('supplier.col.name')}</th>
                        <th>${t('supplier.col.contact')}</th>
                        <th>${t('supplier.col.phone')}</th>
                        <th>${t('supplier.col.email')}</th>
                        <th style="width:90px;">${t('common.status')}</th>
                        <th style="width:160px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    },

    supplierDetail(s) {
        s = s || {};
        return `
        <div style="display:grid;grid-template-columns:120px 1fr;gap:8px 16px;font-size:14px;">
            <div style="color:var(--c-text-3);">ID</div><div>${s.id}</div>
            <div style="color:var(--c-text-3);">${t('supplier.col.name')}</div><div><strong>${escapeHtml(s.name)}</strong></div>
            <div style="color:var(--c-text-3);">${t('supplier.col.contact')}</div><div>${escapeHtml(s.contact || '-')}</div>
            <div style="color:var(--c-text-3);">${t('supplier.col.phone')}</div><div>${escapeHtml(s.phone || '-')}</div>
            <div style="color:var(--c-text-3);">${t('supplier.col.email')}</div><div>${escapeHtml(s.email || '-')}</div>
            <div style="color:var(--c-text-3);">${t('common.status')}</div><div>${s.enabled === false ? `<span class="badge low">${t('supplier.status.disabled')}</span>` : `<span class="badge ok">${t('supplier.status.enabled')}</span>`}</div>
        </div>`;
    },

    supplierForm(s) {
        s = s || {};
        return `
        <div class="form-row">
            <div class="form-field" style="flex:2;">
                <label>${t('supplier.form.name')}<span class="req">*</span></label>
                <input id="sf-name" class="input" value="${escapeHtml(s.name||'')}" placeholder="${t('supplier.form.name_placeholder')}">
            </div>
            <div class="form-field">
                <label>${t('supplier.form.contact')}</label>
                <input id="sf-contact" class="input" value="${escapeHtml(s.contact||'')}" placeholder="${t('supplier.form.contact_placeholder')}">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field">
                <label>${t('supplier.form.phone')}</label>
                <input id="sf-phone" class="input" value="${escapeHtml(s.phone||'')}" placeholder="${t('supplier.form.phone_placeholder')}">
            </div>
            <div class="form-field">
                <label>${t('supplier.form.email')}</label>
                <input id="sf-email" class="input" value="${escapeHtml(s.email||'')}" placeholder="${t('supplier.form.email_placeholder')}">
            </div>
            <div class="form-field">
                <label>${t('supplier.form.enabled')}</label>
                <select id="sf-enabled" class="select">
                    <option value="true"  ${s.enabled===false?'':'selected'}>${t('supplier.status.enabled')}</option>
                    <option value="false" ${s.enabled===false?'selected':''}>${t('supplier.status.disabled')}</option>
                </select>
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>${t('supplier.form.address')}</label>
                <input id="sf-address" class="input" value="${escapeHtml(s.address||'')}" placeholder="${t('new_order.ph.remark')}">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>${t('supplier.form.remark')}</label>
                <input id="sf-remark" class="input" value="${escapeHtml(s.remark||'')}" placeholder="${t('new_order.ph.remark')}">
            </div>
        </div>
        <input type="hidden" id="sf-id" value="${s.id || ''}">`;
    },

    /* ================== 库位管理列表 ================== */
    locations(locations, statusFilter, warehouses, warehouseFilter) {
        const rows = locations.length === 0
            ? `<tr><td colspan="6" class="empty">${t('location.empty')}</td></tr>`
            : locations.map(l => `
                <tr>
                    <td><strong>${escapeHtml(l.code)}</strong></td>
                    <td>${escapeHtml(l.name || '')}</td>
                    <td>${l.warehouseName ? escapeHtml(l.warehouseName) : `<span style="color:var(--c-text-3);">${t('location.warehouse.none')}</span>`}</td>
                    <td>${escapeHtml(l.remark || '')}</td>
                    <td>${l.enabled === false ? `<span class="badge low">${t('location.status.disabled')}</span>` : `<span class="badge ok">${t('location.status.enabled')}</span>`}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="viewLocation(${l.id})">${t('common.view')}</button>
                        <button class="btn-link" onclick="editLocation(${l.id})">${t('common.edit')}</button>
                        <button class="btn-link danger" onclick="delLocation(${l.id}, '${escapeHtml(l.code)}')">${t('common.delete')}</button>
                    </td>
                </tr>`).join('');
        const whOpts = (warehouses || [])
            .map(w => `<option value="${w.id}" ${String(warehouseFilter||'')===String(w.id)?'selected':''}>${escapeHtml(w.name)}</option>`)
            .join('');
        return `
        <div class="page-head">
            <div>
                <h2>${t('location.title')}</h2>
                <div class="desc">${t('location.desc')}</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="newLocation()">${t('location.create')}</button>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <input id="loc-kw" class="input input-sm" placeholder="${t('location.kw_placeholder')}" style="width:240px;">
                <select id="loc-wh-filter" class="select" style="width:150px;" onchange="searchLocations()">
                    <option value="" ${!warehouseFilter?'selected':''}>${t('location.warehouse.all')}</option>
                    ${whOpts}
                </select>
                <select id="loc-status-filter" class="select" style="width:110px;" onchange="searchLocations()">
                    <option value="" ${statusFilter===''?'selected':''}>${t('common.all')}</option>
                    <option value="enabled" ${statusFilter==='enabled'?'selected':''}>${t('location.status.enabled')}</option>
                    <option value="disabled" ${statusFilter==='disabled'?'selected':''}>${t('location.status.disabled')}</option>
                </select>
                <button class="btn btn-sm" onclick="searchLocations()">${t('common.search')}</button>
                <button class="btn btn-sm" onclick="document.getElementById('loc-kw').value='';document.getElementById('loc-wh-filter').value='';document.getElementById('loc-status-filter').value='';searchLocations()">${t('common.clear')}</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:120px;">${t('location.col.code')}</th>
                        <th>${t('location.col.name')}</th>
                        <th style="width:150px;">${t('location.col.warehouse')}</th>
                        <th>${t('location.col.remark')}</th>
                        <th style="width:90px;">${t('common.status')}</th>
                        <th style="width:160px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    },

    locationDetail(l) {
        l = l || {};
        return `
        <div style="display:grid;grid-template-columns:120px 1fr;gap:8px 16px;font-size:14px;">
            <div style="color:var(--c-text-3);">ID</div><div>${l.id}</div>
            <div style="color:var(--c-text-3);">${t('location.col.code')}</div><div><strong>${escapeHtml(l.code)}</strong></div>
            <div style="color:var(--c-text-3);">${t('location.col.name')}</div><div>${escapeHtml(l.name || '-')}</div>
            <div style="color:var(--c-text-3);">${t('location.col.warehouse')}</div><div>${l.warehouseName ? escapeHtml(l.warehouseName) : `<span style="color:var(--c-text-3);">${t('location.warehouse.none')}</span>`}</div>
            <div style="color:var(--c-text-3);">${t('location.col.remark')}</div><div>${escapeHtml(l.remark || '-')}</div>
            <div style="color:var(--c-text-3);">${t('common.status')}</div><div>${l.enabled === false ? `<span class="badge low">${t('location.status.disabled')}</span>` : `<span class="badge ok">${t('location.status.enabled')}</span>`}</div>
        </div>`;
    },

    locationForm(l, warehouses) {
        l = l || {};
        const whOpts = (warehouses || [])
            .map(w => `<option value="${w.id}" ${String(l.warehouseId||'')===String(w.id)?'selected':''}>${escapeHtml(w.name)}</option>`)
            .join('');
        return `
        <div class="form-row">
            <div class="form-field">
                <label>${t('location.form.code')}<span class="req">*</span></label>
                <input id="lf-code" class="input" value="${escapeHtml(l.code||'')}" placeholder="${t('location.form.code_placeholder')}">
            </div>
            <div class="form-field">
                <label>${t('location.form.name')}</label>
                <input id="lf-name" class="input" value="${escapeHtml(l.name||'')}" placeholder="${t('location.form.name_placeholder')}">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field">
                <label>${t('location.form.warehouse')}</label>
                <select id="lf-warehouse" class="select">
                    <option value="" ${l.warehouseId?'':'selected'}>${t('location.warehouse.none')}</option>
                    ${whOpts}
                </select>
            </div>
            <div class="form-field">
                <label>${t('supplier.form.enabled')}</label>
                <select id="lf-enabled" class="select">
                    <option value="true"  ${l.enabled===false?'':'selected'}>${t('location.status.enabled')}</option>
                    <option value="false" ${l.enabled===false?'selected':''}>${t('location.status.disabled')}</option>
                </select>
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>${t('common.remark')}</label>
                <input id="lf-remark" class="input" value="${escapeHtml(l.remark||'')}" placeholder="${t('new_order.ph.remark')}">
            </div>
        </div>
        <input type="hidden" id="lf-id" value="${l.id || ''}">`;
    },

    /* ================== 仓库管理列表 ================== */
    warehouses(warehouses, statusFilter) {
        const rows = (warehouses || []).length === 0
            ? `<tr><td colspan="6" class="empty">${t('warehouse.empty')}</td></tr>`
            : warehouses.map(w => `
                <tr>
                    <td><strong>${escapeHtml(w.code)}</strong></td>
                    <td>${escapeHtml(w.name)}</td>
                    <td>${t('warehouse.locations.count', { n: w.locationCount || 0 })}</td>
                    <td>${escapeHtml(w.remark || '')}</td>
                    <td>${w.enabled === false ? `<span class="badge low">${t('warehouse.status.disabled')}</span>` : `<span class="badge ok">${t('warehouse.status.enabled')}</span>`}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="viewWarehouse(${w.id})">${t('common.view')}</button>
                        <button class="btn-link" onclick="editWarehouse(${w.id})">${t('common.edit')}</button>
                        <button class="btn-link danger" onclick="delWarehouse(${w.id})">${t('common.delete')}</button>
                    </td>
                </tr>`).join('');
        return `
        <div class="page-head">
            <div>
                <h2>${t('warehouse.title')}</h2>
                <div class="desc">${t('warehouse.desc')}</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="newWarehouse()">${t('warehouse.create')}</button>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <input id="wh-kw" class="input input-sm" placeholder="${t('warehouse.kw_placeholder')}" style="width:240px;">
                <select id="wh-status-filter" class="select" style="width:110px;" onchange="searchWarehouses()">
                    <option value="" ${statusFilter===''?'selected':''}>${t('common.all')}</option>
                    <option value="enabled" ${statusFilter==='enabled'?'selected':''}>${t('warehouse.status.enabled')}</option>
                    <option value="disabled" ${statusFilter==='disabled'?'selected':''}>${t('warehouse.status.disabled')}</option>
                </select>
                <button class="btn btn-sm" onclick="searchWarehouses()">${t('common.search')}</button>
                <button class="btn btn-sm" onclick="document.getElementById('wh-kw').value='';document.getElementById('wh-status-filter').value='';searchWarehouses()">${t('common.clear')}</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:120px;">${t('warehouse.col.code')}</th>
                        <th>${t('warehouse.col.name')}</th>
                        <th style="width:110px;">${t('warehouse.col.locations')}</th>
                        <th>${t('warehouse.col.remark')}</th>
                        <th style="width:90px;">${t('common.status')}</th>
                        <th style="width:160px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    },

    warehouseDetail(w) {
        w = w || {};
        return `
        <div style="display:grid;grid-template-columns:120px 1fr;gap:8px 16px;font-size:14px;">
            <div style="color:var(--c-text-3);">ID</div><div>${w.id}</div>
            <div style="color:var(--c-text-3);">${t('warehouse.col.code')}</div><div><strong>${escapeHtml(w.code)}</strong></div>
            <div style="color:var(--c-text-3);">${t('warehouse.col.name')}</div><div>${escapeHtml(w.name || '-')}</div>
            <div style="color:var(--c-text-3);">${t('warehouse.col.locations')}</div><div>${t('warehouse.locations.count', { n: w.locationCount || 0 })}</div>
            <div style="color:var(--c-text-3);">${t('warehouse.col.remark')}</div><div>${escapeHtml(w.remark || '-')}</div>
            <div style="color:var(--c-text-3);">${t('common.status')}</div><div>${w.enabled === false ? `<span class="badge low">${t('warehouse.status.disabled')}</span>` : `<span class="badge ok">${t('warehouse.status.enabled')}</span>`}</div>
        </div>`;
    },

    warehouseForm(w) {
        w = w || {};
        return `
        <div class="form-row">
            <div class="form-field">
                <label>${t('warehouse.form.code')}<span class="req">*</span></label>
                <input id="wf-code" class="input" value="${escapeHtml(w.code||'')}" placeholder="${t('warehouse.form.code_placeholder')}">
            </div>
            <div class="form-field">
                <label>${t('warehouse.form.name')}<span class="req">*</span></label>
                <input id="wf-name" class="input" value="${escapeHtml(w.name||'')}" placeholder="${t('warehouse.form.name_placeholder')}">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>${t('common.remark')}</label>
                <input id="wf-remark" class="input" value="${escapeHtml(w.remark||'')}" placeholder="${t('new_order.ph.remark')}">
            </div>
            <div class="form-field">
                <label>${t('supplier.form.enabled')}</label>
                <select id="wf-enabled" class="select">
                    <option value="true"  ${w.enabled===false?'':'selected'}>${t('warehouse.status.enabled')}</option>
                    <option value="false" ${w.enabled===false?'selected':''}>${t('warehouse.status.disabled')}</option>
                </select>
            </div>
        </div>
        <input type="hidden" id="wf-id" value="${w.id || ''}">`;
    },

    /**
     * 库位下拉选项:按所属仓库分组(optgroup)。多仓库后库位会变多,
     * 平铺一个列表里挑容易选错,分组后一眼能定位到仓库。
     * 未分配仓库的归到最后一组。
     * @param labelFn 每个库位 → 一行 <option> 的 HTML(各调用方的展示文案不同)
     */
    locationOptgroups(locations, labelFn) {
        const groups = new Map();
        for (const l of (locations || [])) {
            const key = l.warehouseName || t('location.warehouse.none');
            if (!groups.has(key)) groups.set(key, []);
            groups.get(key).push(l);
        }
        let html = '';
        for (const [wh, locs] of groups) {
            html += `<optgroup label="${escapeHtml(wh)}">`;
            for (const l of locs) html += labelFn(l);
            html += `</optgroup>`;
        }
        return html;
    },

    /**
     * 多选列表的「行」—— 原生 <select multiple> 的等价物。
     * 原生多选里不加 Ctrl/⌘ 的点击会**覆盖**已选(选第二个必须按修饰键),
     * 与 PDA 的 setMultiChoiceItems(点一下切换一个)也不一致;
     * 换成 <label> 包 checkbox 后点整行即切换。
     * items:[{value, label}];opts.selected 里的值按字符串比对;
     * 只产出行,外壳(.ms-head / .ms-list)由各调用点的模板给出。
     *
     * 另附一行**默认隐藏**的「没匹配」提示:过滤把行全藏起来时由 app.js 的
     * paintFilter 打开。它跟 .ms-empty 是两回事 —— .ms-empty 是列表**本身为空**
     * (如还没维护商品),只有那一行所以这里不再多渲染一行提示。
     */
    multiCheckListItems(items, opts) {
        opts = opts || {};
        const picked = new Set((opts.selected || []).map(String));
        const list = items || [];
        if (list.length === 0) return `<div class="ms-empty">${escapeHtml(opts.empty || '')}</div>`;
        return list.map(it => {
            const v = String(it.value);
            return `<label class="ms-item"><input type="checkbox" value="${escapeHtml(v)}"`
                 + (picked.has(v) ? ' checked' : '')
                 + `><span>${escapeHtml(it.label)}</span></label>`;
        }).join('')
        + `<div class="ms-nomatch" hidden>${escapeHtml(opts.nomatch || '')}</div>`;
    },

    /* ================== 库存盘点 ================== */
    stockChecks(data, filter) {
        const list = (data && data.list) || [];
        const total = (data && data.total) || 0;
        const page  = (data && data.page)  || 1;
        const size  = (data && data.pageSize) || 20;
        const pages = Math.max(1, Math.ceil(total / size));
        const statusMap = {
            'DRAFT':     `<span class="badge" style="background:#e5e7eb;color:var(--c-text-2);">${t('doc.DRAFT')}</span>`,
            'COMMITTED': `<span class="badge ok">${t('doc.COMMITTED')}</span>`,
            'CANCELLED': `<span class="badge low">${t('doc.CANCELLED')}</span>`
        };
        const rows = list.length === 0
            ? `<tr><td colspan="9" class="empty">${t('stock_check.empty')}</td></tr>`
            : list.map(c => `
                <tr>
                    <td><strong>${escapeHtml(c.checkNo)}</strong></td>
                    <td>${escapeHtml(scopeLabel(c.scope))}${c.scopeKeyword ? ' / ' + escapeHtml(c.scopeKeyword) : ''}</td>
                    <td>${escapeHtml(c.createdBy || '')}</td>
                    <td>${statusMap[c.status] || escapeHtml(c.status)}</td>
                    <td class="num">${c.totalCount}</td>
                    <td class="num">${c.countedCount}</td>
                    <td class="num">${c.diffCount}</td>
                    <td>${formatDate(c.createdAt)}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="openStockCheckDetail(${c.id})">${t('common.view')}</button>
                        ${c.status === 'COMMITTED' ? `<button class="btn-link" onclick="api.download('/api/stock-checks/${c.id}/export.csv','stock-check-${c.id}.csv').catch(e=>toast(e.message,'error'))">${t('common.export')}</button>` : ''}
                    </td>
                </tr>`).join('');
        return `
        <div class="page-head">
            <div>
                <h2>${t('stock_check.title')}</h2>
                <div class="desc">${t('stock_check.desc')}</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="openNewStockCheckModal()">${t('stock_check.create')}</button>
            </div>
        </div>
        <div class="card">
            <!-- 日期框必须给固定宽:.toolbar 是 flex+wrap,.input 默认 width:100%,
                 而 flex 换行按"假设主轴尺寸"算(不扣 shrink) -> 每个日期框会独占一整行,
                 label 被顶上去,四个元素占四行。 -->
            <div class="toolbar">
                <label style="font-size:13px;color:var(--c-text-3);">${t('orders.begin_label')}</label>
                <input id="sc-begin" type="date" class="input input-sm" style="width:140px;" value="${escapeHtml(filter.beginDate||'')}">
                <label style="font-size:13px;color:var(--c-text-3);">${t('orders.end_label')}</label>
                <input id="sc-end" type="date" class="input input-sm" style="width:140px;" value="${escapeHtml(filter.endDate||'')}">
                <select id="sc-status" class="select" style="width:120px;">
                    <option value=""          ${filter.status===''?'selected':''}>${t('common.all_status')}</option>
                    <option value="DRAFT"     ${filter.status==='DRAFT'?'selected':''}>${t('doc.DRAFT')}</option>
                    <option value="COMMITTED" ${filter.status==='COMMITTED'?'selected':''}>${t('doc.COMMITTED')}</option>
                    <option value="CANCELLED" ${filter.status==='CANCELLED'?'selected':''}>${t('doc.CANCELLED')}</option>
                </select>
                <button class="btn btn-sm btn-primary" onclick="searchStockChecks()">${t('common.query')}</button>
                <button class="btn btn-sm" onclick="resetStockCheckFilter()">${t('common.reset')}</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th>${t('stock_check.col.check_no')}</th>
                        <th>${t('stock_check.col.scope')}</th>
                        <th>${t('common.createdBy')}</th>
                        <th>${t('common.status')}</th>
                        <th class="num">${t('stock_check.col.total')}</th>
                        <th class="num">${t('stock_check.col.counted')}</th>
                        <th class="num">${t('stock_check.col.diff')}</th>
                        <th>${t('stock_check.col.created_at')}</th>
                        <th>${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div class="toolbar" style="margin-top:14px;justify-content:flex-end;">
                <span style="color:var(--c-text-3);font-size:13px;">${t('common.total')} ${total} ${t('common.items')} · ${t('common.page')} ${page} / ${pages}</span>
                <button class="btn btn-sm" ${page<=1?'disabled':''} onclick="gotoStockCheckPage(${page-1})">${t('common.prev')}</button>
                <button class="btn btn-sm" ${page>=pages?'disabled':''} onclick="gotoStockCheckPage(${page+1})">${t('common.next')}</button>
            </div>
        </div>`;
    },

    newStockCheckForm() {
        // 三种模式:ALL=全部在库 / SKU=按 SKU / WAREHOUSE=按仓库(可选多个库位)。
        // 另有 SCOPE_LOCATION,但只用于**读取历史单** —— 创建入口已移除,见下方注释。
        return `
        <div class="form-row" style="gap:8px;margin-bottom:12px;">
            <label style="font-size:13px;color:var(--c-text-2);min-width:64px;">${t('stock_check.scope_label')}</label>
            <label class="nsc-scope-opt" style="flex:1;display:flex;align-items:center;gap:6px;padding:10px 12px;border:1.5px solid var(--c-primary);border-radius:8px;cursor:pointer;background:#E8F0FF;">
                <input type="radio" name="nsc-scope" value="ALL" checked>
                <span style="font-size:13px;">${t('stock_check.scope.all')}</span>
            </label>
            <label class="nsc-scope-opt" style="flex:1;display:flex;align-items:center;gap:6px;padding:10px 12px;border:1.5px solid var(--divider);border-radius:8px;cursor:pointer;">
                <input type="radio" name="nsc-scope" value="SKU">
                <span style="font-size:13px;">${t('stock_check.scope.sku')}</span>
            </label>
            <label class="nsc-scope-opt" style="flex:1;display:flex;align-items:center;gap:6px;padding:10px 12px;border:1.5px solid var(--divider);border-radius:8px;cursor:pointer;">
                <input type="radio" name="nsc-scope" value="WAREHOUSE">
                <span style="font-size:13px;">${t('stock_check.scope.warehouse')}</span>
            </label>
        </div>
        <!-- 按 SKU:过滤框 + 多选列表(选项由 app.js 拉一次商品填进来)。
             不再有「按库位」—— 它与「按仓库」重叠(按仓库可多选库位,
             只选一个就等价于旧的按库位),见 app.js 的 paintScopeUi。 -->
        <div class="form-field" id="nsc-sku-wrap" style="display:none;">
            <label>${t('stock_check.scope.sku')}</label>
            <div class="ms-head">
                <input id="nsc-sku-filter" class="input" placeholder="${t('stock_check.sku.filter_placeholder')}">
                <span class="ms-count" id="nsc-sku-list-count"></span>
            </div>
            <div class="ms-list" id="nsc-sku-list"></div>
            <div style="font-size:12px;color:var(--c-text-3);margin-top:6px;">${t('stock_check.limit_hint')}</div>
        </div>
        <div class="form-field" id="nsc-warehouse-wrap" style="display:none;">
            <label>${t('stock_check.scope.warehouse')}</label>
            <select id="nsc-warehouse-select" class="select" style="width:100%;"></select>
            <label style="margin-top:10px;display:block;font-size:13px;color:var(--c-text-2);">${t('stock_check.loc.multi_label')}</label>
            <div class="ms-head">
                <input id="nsc-loc-filter" class="input" placeholder="${t('stock_check.loc.filter_placeholder')}">
                <span class="ms-count" id="nsc-loc-list-count"></span>
            </div>
            <div class="ms-list" id="nsc-loc-list"></div>
            <div style="font-size:12px;color:var(--c-text-3);margin-top:6px;">${t('stock_check.loc.multi_hint')} · ${t('stock_check.limit_hint')}</div>
        </div>
        <div class="form-field">
            <label>${t('stock_check.remark_label')}</label>
            <input id="nsc-remark" class="input" placeholder="${t('stock_check.remark_placeholder')}">
        </div>
        <div style="font-size:12px;color:var(--c-text-3);margin-top:8px;">
            ${t('stock_check.tip')}
        </div>`;
    },

    stockCheckDetail(c) {
        const isDraft = c.status === 'DRAFT';
        const scStyle = (sys, counted) => {
            if (counted == null) return '';
            if (counted === sys) return 'background:#ecfdf5;';
            return 'background:#fef2f2;';
        };
        // 盘盈行(账面 0、实盘 >0,即扫到了盘点范围外的单元)不与应盘明细混排:
        // 它不是"应盘的对象",混在一起既看不出哪些是多出来的,也解释不了计数口径
        // (口径见 StockCheckService.isSurplus)。
        const isSurplus = it => (it.systemQty || 0) === 0 && it.countedQty != null && it.countedQty > 0;
        const allItems = c.items || [];
        const detailItems  = allItems.filter(it => !isSurplus(it));
        const surplusItems = allItems.filter(isSurplus);

        const rowHtml = (it, idx) => `
            <tr style="${scStyle(it.systemQty, it.countedQty)}">
                <td class="num">${idx + 1}</td>
                <td><span style="font-family:var(--font-mono);font-size:12px;">${escapeHtml(it.code)}</span></td>
                <td>${escapeHtml(it.sku)}</td>
                <td>${escapeHtml(it.productName)}</td>
                <td>${Views.locLabel(it.locationCode)}</td>
                <td class="num">${it.systemQty || 0}</td>
                <td class="num">
                    ${isDraft
                        ? `<input id="sci-qty-${it.id}" type="number" min="0" class="input input-sm" style="width:90px;" value="${it.countedQty == null ? '' : it.countedQty}">`
                        : (it.countedQty == null ? '-' : it.countedQty)}
                </td>
                <td class="num"><strong>${it.diff == null ? '-' : (it.diff > 0 ? '+' + it.diff : it.diff)}</strong></td>
                <td>
                    ${isDraft
                        ? `<input id="sci-rem-${it.id}" class="input input-sm" style="width:140px;" placeholder="${t('new_order.ph.row_remark')}" value="${escapeHtml(it.rowRemark || '')}">`
                        : escapeHtml(it.rowRemark || '-')}
                </td>
                <td class="actions">
                    ${isDraft ? `<button id="sci-save-${it.id}" class="btn-link">${t('stock_check.btn.save')}</button>` : '-'}
                </td>
            </tr>`;

        const headHtml = `<tr>
                    <th style="width:40px;">#</th>
                    <th style="width:180px;">${t('stock_check.col.code')}</th>
                    <th>${t('stock_check.col.sku')}</th>
                    <th>${t('stock_check.col.product')}</th>
                    <th>${t('stock_check.col.loc')}</th>
                    <th class="num">${t('stock_check.col.system_qty')}</th>
                    <th class="num">${t('stock_check.col.counted_qty')}</th>
                    <th class="num">${t('stock_check.col.diff_qty')}</th>
                    <th>${t('stock_check.col.row_remark')}</th>
                    <th>${t('common.actions')}</th>
                </tr>`;

        const itemsHtml = detailItems.map(rowHtml).join('');

        const summary = `
            <div style="display:flex;gap:18px;margin:8px 0 16px;flex-wrap:wrap;font-size:13px;">
                <span>${t('stock_check.detail.summary')}:<strong>${escapeHtml(c.checkNo)}</strong></span>
                <span>${t('stock_check.detail.scope')}:<strong>${escapeHtml(scopeLabel(c.scope))}${c.scopeKeyword ? ' / ' + escapeHtml(c.scopeKeyword) : ''}</strong></span>
                <span>${t('common.createdBy')}:<strong>${escapeHtml(c.createdBy)}</strong></span>
                <span>${t('common.status')}:<strong>${t('doc.' + c.status)}</strong></span>
                <span>${t('stock_check.detail.total')}:<strong>${c.totalCount} / ${c.countedCount} / ${c.diffCount}</strong></span>
                <span>${t('stock_check.detail.surplus')}:<strong>${surplusItems.length}</strong></span>
                <span>${t('stock_check.detail.created_at')}:<strong>${formatDate(c.createdAt)}</strong></span>
            </div>`;

        return `
        <div>${summary}</div>
        <div style="font-size:12px;color:var(--c-text-3);margin-bottom:8px;">
            ${isDraft ? t('stock_check.detail.tip_draft') : t('stock_check.detail.tip_committed')}
        </div>
        <div class="table-wrap">
            <table class="table">
                <thead>${headHtml}</thead>
                <tbody>${itemsHtml || `<tr><td colspan="10" class="empty">${t('stock_check.detail.empty')}</td></tr>`}</tbody>
            </table>
        </div>
        ${surplusItems.length ? `
        <div style="font-weight:600;font-size:13px;margin:16px 0 8px;">
            ${t('stock_check.detail.surplus')} (${surplusItems.length})
        </div>
        <div class="table-wrap">
            <table class="table">
                <thead>${headHtml}</thead>
                <tbody>${surplusItems.map(rowHtml).join('')}</tbody>
            </table>
        </div>` : ''}
        <div class="form-row" style="position:sticky;bottom:0;margin:16px -20px 0;padding:10px 20px;justify-content:flex-end;background:#fff;box-shadow:0 -4px 10px -8px rgba(0,0,0,0.25);">
            <div class="export-menu-wrap">
                <button class="btn btn-sm" id="sc-export-btn">⬇ ${t('common.export')} CSV ▴</button>
                <div class="export-menu" id="sc-export-menu" hidden>
                    <button type="button" data-filter="all">${t('stock_check.export.all')}</button>
                    <button type="button" data-filter="over">${t('stock_check.export.over')}</button>
                    <button type="button" data-filter="short">${t('stock_check.export.short')}</button>
                </div>
            </div>
            ${isDraft ? `
                <button class="btn btn-sm btn-primary" id="sc-commit-btn" style="margin-left:8px;">${t('stock_check.btn.commit')}</button>
                <button class="btn btn-sm btn-danger" id="sc-cancel-btn" style="margin-left:8px;">${t('stock_check.btn.cancel')}</button>
            ` : ''}
        </div>`;
    },

    /* ================== 库存管理(实物单元) ================== */
    inventory(data, state, locations) {
        locations = locations || [];
        const items = (data && data.list) || [];
        const total = (data && data.total) || 0;
        const page  = (data && data.page)  || 1;
        const size  = (data && data.pageSize) || 20;
        const pages = Math.max(1, Math.ceil(total / size));

        // 库位筛选按仓库分组,多仓库下好找
        const locOpts = Views.locationOptgroups(locations, l => {
            const sel = state.inventoryLoc === l.code ? ' selected' : '';
            const nm = (l.name && l.name !== l.code) ? ' / ' + escapeHtml(l.name) : '';
            return `<option value="${escapeHtml(l.code)}"${sel}>${escapeHtml(l.code)}${nm}</option>`;
        });

        // 仓库筛选:一个商品分散在多个库位之后,总数量是合并值,看不出各仓多少,
        // 这个下拉就是用来按仓看的(后端按 location.warehouse_id 过滤)
        const whOpts = (state.warehouses || []).map(w => {
            const sel = String(state.inventoryWarehouse) === String(w.id) ? ' selected' : '';
            return `<option value="${w.id}"${sel}>${escapeHtml(w.name || w.code)}</option>`;
        }).join('');

        const statusMap = {
            'IN_STOCK'   : `<span class="badge ok">${t('inventory.status.IN_STOCK')}</span>`,
            'PENDING'    : `<span class="badge" style="background:#fef3c7;color:#92400e;">${t('inventory.status.PENDING')}</span>`,
            'RESERVED'   : `<span class="badge" style="background:#e5e7eb;color:var(--c-text-2);">${t('inventory.status.RESERVED')}</span>`,
            'OUT'        : `<span class="badge low">${t('inventory.status.OUT')}</span>`,
            'SCRAPPED'   : `<span class="badge" style="background:#e5e7eb;color:var(--c-text-3);">${t('inventory.status.SCRAPPED')}</span>`,
            'TRANSFERRED': `<span class="badge" style="background:#e5e7eb;color:var(--c-text-3);">${t('inventory.status.TRANSFERRED')}</span>`
        };

        // 效期列:仅填了生产日期+保质期的批次显示;剩天数<0=已过期,<=预警天数=临期
        const expiryCell = (it) => {
            if (!it.expiryDate) return `<span style="color:#9ca3af;">—</span>`;
            const days = Math.round((new Date(it.expiryDate + 'T00:00:00') - new Date(new Date().toISOString().slice(0,10) + 'T00:00:00')) / 86400000);
            if (days < 0)  return `<span class="badge" style="background:#fee2e2;color:#b91c1c;">${t('inventory.expiry.expired')}</span>`;
            if (days <= (it.expiryWarnDays ?? 30)) return `<span class="badge" style="background:#fef3c7;color:#92400e;">${t('inventory.expiry.expiring')} ${days}${t('inventory.expiry.day')}</span>`;
            return `<span style="color:var(--c-text-3);font-size:12px;">${it.expiryDate}</span>`;
        };

        const rows = items.length === 0
            ? `<tr><td colspan="11" class="empty">${t('inventory.empty')}</td></tr>`
            : items.map(it => `
                <tr>
                    <td><span style="font-family:var(--font-mono);font-size:12px;">${escapeHtml(it.code)}</span></td>
                    <td><strong>${escapeHtml(it.sku)}</strong></td>
                    <td>${escapeHtml(it.productName)}</td>
                    <td>${Views.locLabel(it.locationCode)}</td>
                    <td class="num">${it.quantity || 0}</td>
                    <td class="num"><strong>${it.remaining || 0}</strong></td>
                    <td>${statusMap[it.status] || escapeHtml(it.status)}</td>
                    <td>${expiryCell(it)}</td>
                    <td>${escapeHtml(it.createdBy || '-')}</td>
                    <td>${formatDate(it.createdAt)}</td>
                    <td class="actions" style="white-space:nowrap;">
                        <button class="btn-link" onclick="viewBarcode(${it.id}, '${escapeHtml(it.code)}')">${t('inventory.btn.barcode')}</button>
                        ${it.status === 'IN_STOCK' ? `
                            <button class="btn-link" onclick="transferInventory(${it.id}, '${escapeHtml(it.code)}', '${escapeHtml(it.locationCode)}')">${t('inventory.btn.transfer')}</button>
                            <button class="btn-link danger" onclick="scrapInventory(${it.id}, '${escapeHtml(it.code)}')">${t('inventory.btn.scrap')}</button>
                        ` : `<span style="color:#9ca3af;font-size:12px;">—</span>`}
                    </td>
                </tr>`).join('');

        return `
        <div class="page-head">
            <div>
                <h2>${t('inventory.title')}</h2>
                <div class="desc">${t('inventory.desc')}</div>
            </div>
        </div>
        <div class="card">
            <!-- 这一行的宽度预算是硬的:.view 上限 1280 - 两侧 padding 48 - card 44 = 1186px。
                 原来 7 个固定宽控件合计 1223px,gap 再加 110px -> 超 147px,于是在
                 「查询/重置」之间断行、重置被甩到第二行。现在:关键词框改弹性吸收余量,
                 其余收窄、并把这一行的 gap 从 10 收到 8(只影响本行,不动全局 .toolbar)。
                 实测 .view >= 1166 时整行不换行;再窄只会把「导出 CSV」挤下去。 -->
            <div class="toolbar" style="gap:8px;">
                <input id="inv-kw" class="input input-sm" placeholder="${t('inventory.kw_placeholder')}" value="${escapeHtml(state.inventoryKw||'')}" style="flex:1;min-width:90px;max-width:260px;">
                <select id="inv-wh" class="select" style="width:100px;">
                    <option value="">${t('location.warehouse.all')}</option>
                    ${whOpts}
                </select>
                <select id="inv-loc" class="select" style="width:130px;">
                    <option value="">${t('inventory.loc_all')}</option>
                    ${locOpts}
                </select>
                <select id="inv-status" class="select" style="width:100px;">
                    <option value=""            ${state.inventoryStatus===''?'selected':''}>${t('common.all')}</option>
                    <option value="IN_STOCK"    ${state.inventoryStatus==='IN_STOCK'?'selected':''}>${t('inventory.status.IN_STOCK')}</option>
                    <option value="PENDING"     ${state.inventoryStatus==='PENDING'?'selected':''}>${t('inventory.status.PENDING')}</option>
                    <option value="RESERVED"    ${state.inventoryStatus==='RESERVED'?'selected':''}>${t('inventory.status.RESERVED')}</option>
                    <option value="OUT"         ${state.inventoryStatus==='OUT'?'selected':''}>${t('inventory.status.OUT')}</option>
                    <option value="SCRAPPED"    ${state.inventoryStatus==='SCRAPPED'?'selected':''}>${t('inventory.status.SCRAPPED')}</option>
                    <option value="TRANSFERRED" ${state.inventoryStatus==='TRANSFERRED'?'selected':''}>${t('inventory.status.TRANSFERRED')}</option>
                </select>
                <select id="inv-expiry" class="select" style="width:100px;">
                    <option value=""          ${!state.inventoryExpiryStatus?'selected':''}>${t('inventory.expiry.all')}</option>
                    <option value="EXPIRING"  ${state.inventoryExpiryStatus==='EXPIRING'?'selected':''}>${t('inventory.expiry.expiring')}</option>
                    <option value="EXPIRED"   ${state.inventoryExpiryStatus==='EXPIRED'?'selected':''}>${t('inventory.expiry.expired')}</option>
                </select>
                <input type="date" id="inv-expiry-from" class="input input-sm" style="width:130px;" value="${escapeHtml(state.inventoryExpiryFrom||'')}" title="${t('inventory.expiry.from')}">
                <span style="color:var(--c-text-3);">~</span>
                <input type="date" id="inv-expiry-to" class="input input-sm" style="width:130px;" value="${escapeHtml(state.inventoryExpiryTo||'')}" title="${t('inventory.expiry.to')}">
                <button class="btn btn-sm btn-primary" onclick="searchInventory()">${t('common.query')}</button>
                <button class="btn btn-sm" onclick="resetInventoryFilter()">${t('common.reset')}</button>
                <span style="flex:1;"></span>
                <a id="inv-export-btn" class="btn btn-sm" href="/api/inventory/items/export.csv"
                   style="text-decoration:none;">⬇ ${t('common.export')} CSV</a>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:200px;">${t('inventory.col.code')}</th>
                        <th>${t('inventory.col.sku')}</th>
                        <th>${t('inventory.col.product')}</th>
                        <th>${t('inventory.col.loc')}</th>
                        <th class="num">${t('inventory.col.qty')}</th>
                        <th class="num">${t('inventory.col.remaining')}</th>
                        <th>${t('inventory.col.status')}</th>
                        <th>${t('inventory.col.expiry')}</th>
                        <th>${t('inventory.col.created_by')}</th>
                        <th style="width:160px;">${t('inventory.col.created_at')}</th>
                        <th style="width:200px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div class="toolbar" style="margin-top:14px;justify-content:flex-end;">
                <span style="color:var(--c-text-3);font-size:13px;">${t('common.total')} ${total} ${t('common.items')} · ${t('common.page')} ${page} / ${pages}</span>
                <button class="btn btn-sm" ${page<=1?'disabled':''} onclick="gotoInventoryPage(${page-1})">${t('common.prev')}</button>
                <button class="btn btn-sm" ${page>=pages?'disabled':''} onclick="gotoInventoryPage(${page+1})">${t('common.next')}</button>
            </div>
        </div>`;
    }
};

function scopeLabel(s) {
    if (!s) return '';
    switch (s) {
        case 'ALL': return t('stock_check.scope.all');
        case 'SKU': return t('stock_check.scope.sku');
        case 'LOCATION': return t('stock_check.scope.location');
        case 'WAREHOUSE': return t('stock_check.scope.warehouse');
        default: return s;
    }
}

/* 状态/状态类(留给 app.js 用) */
function statusLabel(s) {
    if (!s) return '-';
    return t('inventory.status.' + s, { default: s }) || s;
}
function docStatusLabel(s) {
    if (!s) return '-';
    return t('doc.' + s, { default: s }) || s;
}
function docStatusClass(s) {
    if (s === 'COMMITTED') return 'ok';
    if (s === 'CANCELLED') return 'draft';
    return 'warn';
}
