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

    /* ================== 2. 商品管理 ================== */
    products(products, keyword) {
        const rows = products.length === 0
            ? `<tr><td colspan="10" class="empty">${t('product.empty')}</td></tr>`
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
                        <button class="btn-link" onclick="editProduct(${p.id})">${t('common.edit')}</button>
                        <button class="btn-link danger" onclick="delProduct(${p.id})">${t('common.delete')}</button>
                    </td>
                </tr>`;
            }).join('');
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
                <input class="input input-sm" id="kw-product" placeholder="${t('product.kw_placeholder')}" value="${escapeHtml(keyword||'')}" style="width:280px;">
                <button class="btn btn-sm" onclick="searchProducts()">${t('common.search')}</button>
                <button class="btn btn-sm" onclick="document.getElementById('kw-product').value='';searchProducts()">${t('common.clear')}</button>
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
                        <th style="width:140px;">${t('common.actions')}</th>
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
                        <th>${t('inbound.col.op_time')}</th>
                        <th style="width:200px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody id="inbound-recent-tbody">${Views.inboundRecentRows(recentOrders)}</tbody>
                </table>
            </div>
        </div>`;
    },

    inboundRecentRows(recentOrders) {
        recentOrders = recentOrders || [];
        if (recentOrders.length === 0) {
            return `<tr><td colspan="6" class="empty">${t('inbound.empty')}</td></tr>`;
        }
        return recentOrders.map(o => {
            const st = o.status || 'DRAFT';
            const actionBtns = (st === 'DRAFT')
                ? `<button class="btn-link" onclick="commitInboundOrder(${o.id}, '${escapeHtml(o.orderNo)}')">${t('common.commit')}</button>
                   <button class="btn-link danger" onclick="cancelInboundOrder(${o.id}, '${escapeHtml(o.orderNo)}')">${t('common.cancel')}</button>`
                : '';
            return `
            <tr>
                <td><strong>${escapeHtml(o.orderNo)}</strong></td>
                <td><span class="pill ${docStatusClass(st)}">${t('doc.' + st)}</span></td>
                <td>${escapeHtml(o.externalNo || '-')}</td>
                <td class="num">${o.itemCount || 0} / ${o.totalQuantity || 0}</td>
                <td>${escapeHtml(o.operator || '')} · ${formatDate(o.createdAt)}</td>
                <td class="actions">
                    <button class="btn-link" onclick="viewOrderDetail(${o.id})">${t('common.view')}</button>
                    ${actionBtns}
                </td>
            </tr>`;
        }).join('');
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
                        <th>${t('outbound.col.op_time')}</th>
                        <th style="width:200px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody id="outbound-recent-tbody">${Views.outboundRecentRows(recentOrders)}</tbody>
                </table>
            </div>
        </div>`;
    },

    outboundRecentRows(recentOrders) {
        recentOrders = recentOrders || [];
        if (recentOrders.length === 0) {
            return `<tr><td colspan="7" class="empty">${t('outbound.empty')}</td></tr>`;
        }
        return recentOrders.map(o => {
            const st = o.status || 'DRAFT';
            const actionBtns = (st === 'DRAFT')
                ? `<button class="btn-link" onclick="commitOutboundOrder(${o.id})">${t('common.commit')}</button>
                   <button class="btn-link danger" onclick="cancelOutboundOrder(${o.id})">${t('common.cancel')}</button>`
                : '';
            return `
            <tr>
                <td><strong>${escapeHtml(o.orderNo)}</strong></td>
                <td><span class="pill ${docStatusClass(st)}">${t('doc.' + st)}</span></td>
                <td>${escapeHtml(o.externalNo || '-')}</td>
                <td>${escapeHtml(o.customer || '-')}</td>
                <td class="num">${o.itemCount || 0} / ${o.totalQuantity || 0}</td>
                <td>${escapeHtml(o.operator || '')} · ${formatDate(o.createdAt)}</td>
                <td class="actions">
                    <button class="btn-link" onclick="viewOrderDetail(${o.id})">${t('common.view')}</button>
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
    newOrderModal(type, products, suppliers) {
        products = products || [];
        suppliers = suppliers || [];
        const isIn = type === 'IN';

        // 空占位项必须放在最前:否则商品列表非空时浏览器会自动选中第一项,
        // 预渲染的空白行会被当成"已选商品但数量为空",一提交就报"数量必须大于 0",
        // 整单无法保存(app.js readNewOrderItems 只在商品+数量都为空时才跳过该行)。
        const productOpts = products.length
            ? `<option value="">${t('new_order.placeholder.pick_product')}</option>` + products.map(p => {
                const low = p.quantity <= p.lowStock ? ` [${t('product.low_badge')}]` : '';
                const dflt = p.defaultSupplier ? ` (${t('new_order.label.supplier')}:${escapeHtml(p.defaultSupplier)})` : '';
                return `<option value="${p.id}" data-stock="${p.quantity}" data-default="${escapeHtml(p.defaultSupplier||'')}" data-default-id="${p.defaultSupplierId||''}">${escapeHtml(p.sku)} - ${escapeHtml(p.name)} (${p.quantity}${escapeHtml(p.unit||'')})${low}${dflt}</option>`;
            }).join('')
            : `<option value="">${t('new_order.placeholder.no_product')}</option>`;

        const supplierOpts = suppliers
            .filter(s => s.enabled !== false)
            .map(s => `<option value="${escapeHtml(s.name)}">${escapeHtml(s.name)}</option>`)
            .join('');

        const blankRow = () => {
            const productCell  = `<select class="select nw-product" style="width:240px;">${productOpts}</select>`;
            const supplierCell = isIn
                ? `<select class="select nw-supplier" style="width:150px;"><option value="">${t('new_order.placeholder.not_set')}</option>${supplierOpts || `<option value="" disabled>${t('new_order.placeholder.no_supplier')}</option>`}</select>`
                : '';
            const qtyCell      = `<input type="number" min="1" class="input nw-qty" placeholder="${t('new_order.ph.qty')}" style="width:90px;">`;
            const remarkCell   = `<input class="input nw-row-remark" placeholder="${t('new_order.ph.row_remark')}" style="width:180px;">`;
            return `
            <tr class="nw-row">
                <td class="num row-idx">1</td>
                <td>${productCell}</td>
                ${isIn ? `<td>${supplierCell}</td>` : ''}
                <td>${qtyCell}</td>
                <td>${remarkCell}</td>
                <td class="actions"><button class="btn-link danger" onclick="removeNewOrderRow(this)">${t('common.delete')}</button></td>
            </tr>`;
        };

        const ths = isIn
            ? `<th style="width:36px;">#</th>
               <th style="width:240px;">${t('new_order.label.product')}<span class="req">*</span></th>
               <th style="width:150px;">${t('new_order.label.supplier')}</th>
               <th style="width:90px;">${t('new_order.label.qty')}<span class="req">*</span></th>
               <th>${t('new_order.label.row_remark')}</th>
               <th style="width:66px;">${t('common.actions')}</th>`
            : `<th style="width:36px;">#</th>
               <th style="width:240px;">${t('new_order.label.product')}<span class="req">*</span></th>
               <th style="width:90px;">${t('new_order.label.qty')}<span class="req">*</span></th>
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

    /* ================== 出库单详情 Modal ================== */
    outboundOrderModal(o) {
        const barcodeMap = o.barcodesBySku || {};
        const rows = (o.items || []).map((it, i) => {
            const codes = barcodeMap[it.sku] || [];
            const codesText = codes.length === 0
                ? `<span style="color:#9ca3af;font-size:12px;">${t('outbound_order.no_barcode')}</span>`
                : '<div class="codes-list">' + codes.map(c => `<span class="code-chip">${escapeHtml(c.code)}</span>`).join('') + '</div>';
            return `
            <tr>
                <td class="num">${i + 1}</td>
                <td><strong>${escapeHtml(it.sku)}</strong></td>
                <td>${escapeHtml(it.productName)}</td>
                <td>${escapeHtml(it.location || '-')}</td>
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
                    <div><label>${t('outbound_order.label.operator')}:</label><span>${escapeHtml(o.operator || '')}</span></div>
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
        const size   = (data && data.pageSize) || 20;
        const pages  = Math.max(1, Math.ceil(total / size));

        const beginVal  = (filter && filter.beginDate) || '';
        const endVal    = (filter && filter.endDate)   || '';
        const kwVal     = (filter && filter.keyword)   || '';
        const opVal     = (filter && filter.operator)  || '';
        const statusVal = (filter && filter.status)    || 'COMMITTED';

        const rows = orders.length === 0
            ? `<tr><td colspan="10" class="empty">${t('orders.empty')}</td></tr>`
            : orders.map(o => {
                const tBadge = o.orderNo && o.orderNo.startsWith('IN-')
                    ? `<span class="badge in">${t('orders.badge.in')}</span>`
                    : `<span class="badge out">${t('orders.badge.out')}</span>`;
                const partner = (type === 'IN')
                    ? (o.externalNo ? `[${t('orders.col.external_no')}] ${escapeHtml(o.externalNo)}` : '—')
                    : escapeHtml(o.customer || '-');
                return `
                <tr>
                    <td>${o.id}</td>
                    <td>${tBadge}</td>
                    <td><strong>${escapeHtml(o.orderNo)}</strong></td>
                    <td>${partner}</td>
                    <td>${escapeHtml(o.externalNo || '-')}</td>
                    <td class="num">${o.itemCount || 0}</td>
                    <td class="num">${o.totalQuantity || 0}</td>
                    <td>${escapeHtml(o.operator || '')}</td>
                    <td>${formatDate(o.createdAt)}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="viewOrderDetail(${o.id})">${t('common.view')}</button>
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
                    <option value="IN"  ${type==='IN' ?'selected':''}>${t('orders.type.in')}</option>
                    <option value="OUT" ${type==='OUT'?'selected':''}>${t('orders.type.out')}</option>
                </select>
                <label style="font-size:13px;color:var(--c-text-3);">${t('orders.begin_label')}</label>
                <input id="od-begin" type="date" class="input input-sm" value="${beginVal}">
                <label style="font-size:13px;color:var(--c-text-3);">${t('orders.end_label')}</label>
                <input id="od-end" type="date" class="input input-sm" value="${endVal}">
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
                        <th style="width:60px;">${t('orders.col.id')}</th>
                        <th style="width:80px;">${t('orders.col.type')}</th>
                        <th>${t('orders.col.order_no')}</th>
                        <th>${type==='OUT' ? t('orders.partner_customer') : t('orders.partner_supplier')}</th>
                        <th>${t('orders.col.external_no')}</th>
                        <th class="num">${t('orders.col.item_count')}</th>
                        <th class="num">${t('orders.col.total_qty')}</th>
                        <th>${t('orders.col.operator')}</th>
                        <th style="width:160px;">${t('orders.col.created_at')}</th>
                        <th style="width:100px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div class="toolbar" style="margin-top:14px;justify-content:flex-end;">
                <span style="color:var(--c-text-3);font-size:13px;">${t('common.total')} ${total} ${t('common.items')} · ${t('common.page')} ${page} / ${pages}</span>
                <button class="btn btn-sm" ${page<=1?'disabled':''} onclick="gotoOrderPage(${page-1})">${t('common.prev')}</button>
                <button class="btn btn-sm" ${page>=pages?'disabled':''} onclick="gotoOrderPage(${page+1})">${t('common.next')}</button>
            </div>
        </div>`;
    },

    /* ================== 入库单详情 Modal ================== */
    inboundOrderModal(o) {
        const barcodeMap = o.barcodesBySku || {};
        const rows = (o.items || []).map((it, i) => {
            const codes = barcodeMap[it.sku] || [];
            const codesText = codes.length === 0
                ? `<span style="color:#9ca3af;font-size:12px;">${t('inbound_order.no_barcode')}</span>`
                : '<div class="codes-list">' + codes.map(c => `<span class="code-chip">${escapeHtml(c.code)}</span>`).join('') + '</div>';
            return `
            <tr>
                <td class="num">${i + 1}</td>
                <td>${escapeHtml(it.supplier || '-')}</td>
                <td><strong>${escapeHtml(it.sku)}</strong></td>
                <td>${escapeHtml(it.productName)}</td>
                <td>${escapeHtml(it.location || '-')}</td>
                <td class="num">${it.quantity}</td>
                <td class="num">¥${formatMoney(it.unitPrice)}</td>
                <td>${escapeHtml(it.rowRemark || '')}</td>
            </tr>
            <tr class="barcode-row">
                <td colspan="8" style="background:var(--c-bg-soft);padding:6px 12px;">
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
                    <div><label>${t('inbound_order.label.operator')}:</label><span>${escapeHtml(o.operator || '')}</span></div>
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
                    <th class="num">${t('inbound_order.col.qty')}</th>
                    <th class="num">${t('inbound_order.col.price')}</th>
                    <th>${t('inbound_order.col.row_remark')}</th>
                </tr></thead>
                <tbody>${rows || `<tr><td colspan="8" class="empty">${t('inbound_order.empty')}</td></tr>`}</tbody>
                <tfoot>
                    <tr>
                        <td colspan="5" style="text-align:right;font-weight:600;">${t('inbound_order.total')}</td>
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

    /* ================== 商品表单 Modal 内容 ================== */
    productForm(p, suppliers, locations) {
        p = p || {};
        suppliers = suppliers || [];
        locations = locations || [];
        const selected = (p.suppliers || []).map(s => s.id);
        const defaultId = (p.suppliers || []).find(s => s.isDefault)?.id
                       || p.defaultSupplierId
                       || (selected[0] || '');
        const supplierOptions = suppliers.map(s =>
            `<option value="${s.id}" ${selected.includes(s.id)?'selected':''}>${escapeHtml(s.name)}</option>`
        ).join('');
        const defaultOptions = suppliers.map(s =>
            `<option value="${s.id}" ${s.id===defaultId?'selected':''}>${escapeHtml(s.name)}</option>`
        ).join('');
        const enabledLocs = locations.filter(l => l.enabled !== false);
        const enabledCodes = new Set(enabledLocs.map(l => l.code));
        const currentLocCode = p.location || '';
        const extraLoc = (currentLocCode && !enabledCodes.has(currentLocCode))
            ? [{ code: currentLocCode, name: t('location.disabled_marker'), enabled: true }] : [];
        const locSelectOptions = [{ code: '', name: t('location.empty_marker'), enabled: true }, ...enabledLocs, ...extraLoc]
            .map(l => `<option value="${escapeHtml(l.code)}" ${l.code===currentLocCode?'selected':''}>${escapeHtml(l.code)}${l.name && l.name !== l.code ? ' / ' + escapeHtml(l.name) : ''}</option>`)
            .join('');

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
                    ${locSelectOptions || `<option value="" disabled>${t('product.form.empty_loc')}</option>`}
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
            <div class="form-field" style="flex:2;">
                <label>${t('product.form.suppliers')}</label>
                <select id="pf-suppliers" class="select" multiple size="4" style="min-height:80px;">
                    ${supplierOptions || `<option value="" disabled>${t('product.form.empty_supplier')}</option>`}
                </select>
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
        <input type="hidden" id="pf-id" value="${p.id || ''}">
        <input type="hidden" id="pf-selected-suppliers" value="${escapeHtml(JSON.stringify(selected))}">
        <input type="hidden" id="pf-default-supplier-id" value="${defaultId}">`;
    },

    /* ================== 供应商管理列表 ================== */
    suppliers(suppliers) {
        const rows = suppliers.length === 0
            ? `<tr><td colspan="7" class="empty">${t('supplier.empty')}</td></tr>`
            : suppliers.map(s => `
                <tr>
                    <td>${s.id}</td>
                    <td><strong>${escapeHtml(s.name)}</strong></td>
                    <td>${escapeHtml(s.contact || '')}</td>
                    <td>${escapeHtml(s.phone || '')}</td>
                    <td>${escapeHtml(s.email || '')}</td>
                    <td>${s.enabled === false ? `<span class="badge low">${t('supplier.status.disabled')}</span>` : `<span class="badge ok">${t('supplier.status.enabled')}</span>`}</td>
                    <td class="actions">
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
                <input id="sup-kw" class="input input-sm" placeholder="${t('supplier.kw_placeholder')}" style="width:280px;">
                <button class="btn btn-sm" onclick="searchSuppliers()">${t('common.search')}</button>
                <button class="btn btn-sm" onclick="document.getElementById('sup-kw').value='';searchSuppliers()">${t('common.clear')}</button>
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
                        <th style="width:140px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
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
    locations(locations) {
        const rows = locations.length === 0
            ? `<tr><td colspan="5" class="empty">${t('location.empty')}</td></tr>`
            : locations.map(l => `
                <tr>
                    <td><strong>${escapeHtml(l.code)}</strong></td>
                    <td>${escapeHtml(l.name || '')}</td>
                    <td>${escapeHtml(l.remark || '')}</td>
                    <td>${l.enabled === false ? `<span class="badge low">${t('location.status.disabled')}</span>` : `<span class="badge ok">${t('location.status.enabled')}</span>`}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="editLocation(${l.id})">${t('common.edit')}</button>
                        <button class="btn-link danger" onclick="delLocation(${l.id})">${t('common.delete')}</button>
                    </td>
                </tr>`).join('');
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
                <input id="loc-kw" class="input input-sm" placeholder="${t('location.kw_placeholder')}" style="width:280px;">
                <button class="btn btn-sm" onclick="searchLocations()">${t('common.search')}</button>
                <button class="btn btn-sm" onclick="document.getElementById('loc-kw').value='';searchLocations()">${t('common.clear')}</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:120px;">${t('location.col.code')}</th>
                        <th>${t('location.col.name')}</th>
                        <th>${t('location.col.remark')}</th>
                        <th style="width:90px;">${t('common.status')}</th>
                        <th style="width:140px;">${t('common.actions')}</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    },

    locationForm(l) {
        l = l || {};
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
            <div class="form-field" style="flex:1;">
                <label>${t('common.remark')}</label>
                <input id="lf-remark" class="input" value="${escapeHtml(l.remark||'')}" placeholder="${t('new_order.ph.remark')}">
            </div>
            <div class="form-field">
                <label>${t('supplier.form.enabled')}</label>
                <select id="lf-enabled" class="select">
                    <option value="true"  ${l.enabled===false?'':'selected'}>${t('location.status.enabled')}</option>
                    <option value="false" ${l.enabled===false?'selected':''}>${t('location.status.disabled')}</option>
                </select>
            </div>
        </div>
        <input type="hidden" id="lf-id" value="${l.id || ''}">`;
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
                    <td>${escapeHtml(c.operator || '')}</td>
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
            <div class="toolbar">
                <label style="font-size:13px;color:var(--c-text-3);">${t('orders.begin_label')}</label>
                <input id="sc-begin" type="date" class="input input-sm" value="${escapeHtml(filter.beginDate||'')}">
                <label style="font-size:13px;color:var(--c-text-3);">${t('orders.end_label')}</label>
                <input id="sc-end" type="date" class="input input-sm" value="${escapeHtml(filter.endDate||'')}">
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
                        <th>${t('common.operator')}</th>
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
        // 三种模式:ALL=全部在库 / SKU=按 SKU 模糊 / LOCATION=按库位(下拉选)
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
                <input type="radio" name="nsc-scope" value="LOCATION">
                <span style="font-size:13px;">${t('stock_check.scope.location')}</span>
            </label>
        </div>
        <div class="form-field" id="nsc-keyword-wrap" style="display:none;">
            <label id="nsc-keyword-label">${t('stock_check.scope.keyword')}</label>
            <input id="nsc-keyword" class="input" placeholder="">
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
        const itemsHtml = (c.items || []).map((it, idx) => `
            <tr style="${scStyle(it.systemQty, it.countedQty)}">
                <td class="num">${idx + 1}</td>
                <td><span style="font-family:var(--font-mono);font-size:12px;">${escapeHtml(it.code)}</span></td>
                <td>${escapeHtml(it.sku)}</td>
                <td>${escapeHtml(it.productName)}</td>
                <td>${escapeHtml(it.locationCode)}</td>
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
            </tr>`).join('');

        const summary = `
            <div style="display:flex;gap:18px;margin:8px 0 16px;flex-wrap:wrap;font-size:13px;">
                <span>${t('stock_check.detail.summary')}:<strong>${escapeHtml(c.checkNo)}</strong></span>
                <span>${t('stock_check.detail.scope')}:<strong>${escapeHtml(scopeLabel(c.scope))}${c.scopeKeyword ? ' / ' + escapeHtml(c.scopeKeyword) : ''}</strong></span>
                <span>${t('common.operator')}:<strong>${escapeHtml(c.operator)}</strong></span>
                <span>${t('common.status')}:<strong>${t('doc.' + c.status)}</strong></span>
                <span>${t('stock_check.detail.total')}:<strong>${c.totalCount} / ${c.countedCount} / ${c.diffCount}</strong></span>
                <span>${t('stock_check.detail.created_at')}:<strong>${formatDate(c.createdAt)}</strong></span>
            </div>`;

        const headBtns = isDraft
            ? `<button id="sc-cancel-btn" class="btn btn-sm" style="background:#e5e7eb;color:var(--c-text-2);">${t('stock_check.btn.cancel')}</button>`
            : `<button id="sc-export-btn" class="btn btn-sm">⬇ ${t('common.export')} CSV</button>`;

        return `
        <div>${summary}</div>
        ${headBtns ? `<div style="margin-bottom:8px;">${headBtns}</div>` : ''}
        <div style="font-size:12px;color:var(--c-text-3);margin-bottom:8px;">
            ${isDraft ? t('stock_check.detail.tip_draft') : t('stock_check.detail.tip_committed')}
        </div>
        <div class="table-wrap">
            <table class="table">
                <thead><tr>
                    <th style="width:40px;">#</th>
                    <th style="width:180px;">${t('stock_check.col.code')}</th>
                    <th>${t('stock_check.col.sku')}</th>
                    <th>${t('stock_check.col.product')}</th>
                    <th>${t('stock_check.col.loc')}</th>
                    <th class="num">${t('stock_check.col.system_qty')}</th>
                    <th class="num">${t('stock_check.col.counted_qty')}</th>
                    <th class="num">${t('stock_check.col.diff')}</th>
                    <th>${t('stock_check.col.row_remark')}</th>
                    <th>${t('common.actions')}</th>
                </tr></thead>
                <tbody>${itemsHtml || `<tr><td colspan="10" class="empty">${t('stock_check.detail.empty')}</td></tr>`}</tbody>
            </table>
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

        const locOpts = locations.map(l =>
            `<option value="${escapeHtml(l.code)}" ${state.inventoryLoc===l.code?'selected':''}>${escapeHtml(l.code)}${l.name && l.name !== l.code ? ' / ' + escapeHtml(l.name) : ''}</option>`
        ).join('');

        const statusMap = {
            'IN_STOCK'   : `<span class="badge ok">${t('inventory.status.IN_STOCK')}</span>`,
            'OUT'        : `<span class="badge low">${t('inventory.status.OUT')}</span>`,
            'SCRAPPED'   : `<span class="badge" style="background:#e5e7eb;color:var(--c-text-3);">${t('inventory.status.SCRAPPED')}</span>`,
            'TRANSFERRED': `<span class="badge" style="background:#e5e7eb;color:var(--c-text-3);">${t('inventory.status.TRANSFERRED')}</span>`
        };

        const rows = items.length === 0
            ? `<tr><td colspan="10" class="empty">${t('inventory.empty')}</td></tr>`
            : items.map(it => `
                <tr>
                    <td><span style="font-family:var(--font-mono);font-size:12px;">${escapeHtml(it.code)}</span></td>
                    <td><strong>${escapeHtml(it.sku)}</strong></td>
                    <td>${escapeHtml(it.productName)}</td>
                    <td>${escapeHtml(it.locationCode || '-')}</td>
                    <td class="num">${it.quantity || 0}</td>
                    <td class="num"><strong>${it.remaining || 0}</strong></td>
                    <td>${statusMap[it.status] || escapeHtml(it.status)}</td>
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
            <div class="toolbar">
                <input id="inv-kw" class="input input-sm" placeholder="${t('inventory.kw_placeholder')}" value="${escapeHtml(state.inventoryKw||'')}" style="width:200px;">
                <select id="inv-loc" class="select" style="width:160px;">
                    <option value="">${t('inventory.loc_all')}</option>
                    ${locOpts}
                </select>
                <select id="inv-status" class="select" style="width:120px;">
                    <option value="IN_STOCK"    ${state.inventoryStatus==='IN_STOCK'?'selected':''}>${t('inventory.status.IN_STOCK')}</option>
                    <option value="OUT"         ${state.inventoryStatus==='OUT'?'selected':''}>${t('inventory.status.OUT')}</option>
                    <option value="SCRAPPED"    ${state.inventoryStatus==='SCRAPPED'?'selected':''}>${t('inventory.status.SCRAPPED')}</option>
                    <option value="TRANSFERRED" ${state.inventoryStatus==='TRANSFERRED'?'selected':''}>${t('inventory.status.TRANSFERRED')}</option>
                </select>
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
