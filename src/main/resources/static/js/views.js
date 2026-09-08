/* ============================================
 *  views.js  -  5 个模块的 HTML 模板
 *  每个函数返回 HTML 字符串,由 app.js 注入到 #view
 * ============================================ */

/* ---------- 轻量 SVG 折线图(无外部依赖) ----------
 * points: [{date:'2026-09-01', value: 120}, ...]
 * options: {color, fillColor, suffix}
 */
function renderLineChart(containerId, points, options) {
    options = options || {};
    const color = options.color || '#3498db';
    const fillColor = options.fillColor || 'rgba(52,152,219,0.12)';
    const suffix = options.suffix || '';

    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = '';

    if (!points || points.length === 0) {
        el.innerHTML = '<div style="color:#aab2bd;font-size:13px;text-align:center;line-height:240px;">暂无数据</div>';
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
    // 顶部留 10% 余量
    const vRange = vMax - vMin;
    vMax = vMax + vRange * 0.10;

    const xStep = points.length > 1 ? cw / (points.length - 1) : 0;

    // Y 轴 5 等分
    const yTicks = [];
    for (let i = 0; i <= 4; i++) {
        const v = vMin + (vMax - vMin) * i / 4;
        yTicks.push({ y: padT + ch - (ch * i / 4), label: Math.round(v) });
    }
    // X 轴标签(只显示首/中/末三个,避免拥挤)
    const xLabelIdx = points.length <= 1
        ? [0]
        : [0, Math.floor(points.length / 2), points.length - 1];

    // 折线坐标
    const linePts = points.map((p, i) => {
        const x = padL + i * xStep;
        const ratio = (Number(p.value) - vMin) / (vMax - vMin);
        const y = padT + ch - ratio * ch;
        return { x, y, p };
    });
    // 多边形(填充用)
    const polyPts = linePts.map(pt => `${pt.x.toFixed(1)},${pt.y.toFixed(1)}`).join(' ');
    const linePath = linePts.map((pt, i) =>
        `${i === 0 ? 'M' : 'L'} ${pt.x.toFixed(1)} ${pt.y.toFixed(1)}`
    ).join(' ');
    const closePath = ` L ${linePts[linePts.length-1].x.toFixed(1)} ${(padT+ch).toFixed(1)} L ${padL.toFixed(1)} ${(padT+ch).toFixed(1)} Z`;

    let svg = `<svg viewBox="0 0 ${W} ${H}" width="100%" height="${H}" preserveAspectRatio="none" style="display:block;">`;
    // Y 网格 + 刻度
    yTicks.forEach(t => {
        svg += `<line x1="${padL}" y1="${t.y.toFixed(1)}" x2="${W-padR}" y2="${t.y.toFixed(1)}" stroke="#eef2f6" stroke-width="1"/>`;
        svg += `<text x="${padL-6}" y="${(t.y+3).toFixed(1)}" font-size="10" fill="#8492a6" text-anchor="end">${t.label}${suffix}</text>`;
    });
    // X 标签
    xLabelIdx.forEach(i => {
        const pt = linePts[i];
        const d = points[i].date || '';
        svg += `<text x="${pt.x.toFixed(1)}" y="${(padT+ch+14).toFixed(1)}" font-size="10" fill="#8492a6" text-anchor="middle">${d.slice(5)}</text>`;
    });
    // 填充
    svg += `<path d="${linePath}${closePath}" fill="${fillColor}" stroke="none"/>`;
    // 折线
    svg += `<path d="${linePath}" fill="none" stroke="${color}" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round"/>`;
    // 节点
    linePts.forEach((pt, i) => {
        svg += `<circle cx="${pt.x.toFixed(1)}" cy="${pt.y.toFixed(1)}" r="2" fill="#fff" stroke="${color}" stroke-width="1.5"/>`;
    });
    svg += `</svg>`;
    el.innerHTML = svg;

    // 图例/汇总
    const legendEl = document.getElementById(containerId + '-legend');
    if (legendEl) {
        const total = values.reduce((a, b) => a + b, 0);
        const peak = values.length ? Math.max.apply(null, values) : 0;
        const peakDate = points.find(p => Number(p.value) === peak)?.date || '-';
        legendEl.innerHTML = `峰值: <strong style="color:${color}">${peak}${suffix}</strong> @ ${peakDate} · 累计: <strong>${total}${suffix}</strong> · 共 ${points.length} 天`;
    }
}

const Views = {

    /* ================== 1. 仪表盘 ================== */
    dashboard(stats) {
        const vc = stats.lowStockCount > 0 ? 'stat-card warn' : 'stat-card ok';
        return `
        <div class="page-head">
            <div>
                <h2>仪表盘</h2>
                <div class="desc">实时展示仓库概览信息</div>
            </div>
        </div>
        <div class="stat-grid">
            <div class="stat-card">
                <div class="label">商品种类</div>
                <div class="value">${stats.productCount}</div>
            </div>
            <div class="stat-card gold">
                <div class="label">库存总量</div>
                <div class="value">${stats.totalQuantity}</div>
            </div>
            <div class="stat-card ok">
                <div class="label">库存总值 (元)</div>
                <div class="value">¥ ${formatMoney(stats.totalValue)}</div>
            </div>
            <div class="${vc}">
                <div class="label">低库存预警</div>
                <div class="value">${stats.lowStockCount}</div>
            </div>
        </div>
        <div class="split" style="margin-top:24px;">
            <div class="card">
                <div class="card-title">📈 库存趋势(累计在库单元 / 最近 30 天)</div>
                <div id="chart-inventory-trend" style="width:100%;height:240px;position:relative;">
                    <div style="color:#aab2bd;font-size:13px;text-align:center;line-height:240px;">加载中...</div>
                </div>
                <div id="chart-inventory-trend-legend" style="font-size:12px;color:#8492a6;margin-top:6px;"></div>
            </div>
            <div class="card">
                <div class="card-title">📤 出库趋势(每日出库件数 / 最近 30 天)</div>
                <div id="chart-outbound-trend" style="width:100%;height:240px;position:relative;">
                    <div style="color:#aab2bd;font-size:13px;text-align:center;line-height:240px;">加载中...</div>
                </div>
                <div id="chart-outbound-trend-legend" style="font-size:12px;color:#8492a6;margin-top:6px;"></div>
            </div>
        </div>`;
    },

    /* ================== 2. 商品管理 ================== */
    products(products, keyword) {
        const rows = products.length === 0
            ? `<tr><td colspan="10" class="empty">暂无商品,点击右上角"新增商品"开始</td></tr>`
            : products.map(p => {
                const lowBadge = p.quantity <= p.lowStock
                    ? `<span class="badge low">低库存</span>` : '';
                const supp = p.defaultSupplier
                    ? escapeHtml(p.defaultSupplier)
                    : '<span style="color:#aab2bd;">未设</span>';
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
                        <button class="btn-link" onclick="editProduct(${p.id})">编辑</button>
                        <button class="btn-link danger" onclick="delProduct(${p.id}, '${escapeHtml(p.name)}')">删除</button>
                    </td>
                </tr>`;
            }).join('');
        return `
        <div class="page-head">
            <div>
                <h2>商品管理</h2>
                <div class="desc">维护商品基础信息(SKU、名称、分类、单位、默认供应商、库位、数量、单价)</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="newProduct()">+ 新增商品</button>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <input class="input input-sm" id="kw-product" placeholder="搜索 SKU / 名称 / 分类" value="${escapeHtml(keyword||'')}" style="width:280px;">
                <button class="btn btn-sm" onclick="searchProducts()">搜索</button>
                <button class="btn btn-sm" onclick="document.getElementById('kw-product').value='';searchProducts()">清空</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:60px;">ID</th>
                        <th>SKU</th>
                        <th>名称</th>
                        <th>分类</th>
                        <th>单位</th>
                        <th>默认供应商</th>
                        <th class="num">数量</th>
                        <th class="num">单价</th>
                        <th class="num">库存金额</th>
                        <th style="width:140px;">操作</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    },

    /* ================== 3. 入库登记(列表 + 新增按钮) ================== */
    inbound(products, recentOrders) {
        recentOrders = recentOrders || [];
        const recentRows = recentOrders.length === 0
            ? `<tr><td colspan="6" class="empty">暂无入库单,点上方「新增入库单」开始</td></tr>`
            : recentOrders.map(o => `
                <tr>
                    <td><strong>${escapeHtml(o.orderNo)}</strong></td>
                    <td><span class="pill ${docStatusClass(o.status)}">${docStatusLabel(o.status)}</span></td>
                    <td>${escapeHtml(o.externalNo || '-')}</td>
                    <td class="num">${o.itemCount || 0} 行 / ${o.totalQuantity || 0} 件</td>
                    <td>${escapeHtml(o.operator || '')} · ${formatDate(o.createdAt)}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="viewOrderDetail('${o.orderNo}')">查看</button>
                    </td>
                </tr>`).join('');
        return `
        <div class="page-head">
            <div>
                <h2>入库登记</h2>
                <div class="desc">点「新增入库单」开一张单 → 选商品(自动预填默认供应商)→ 确认入库</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="openNewOrderModal('IN')">+ 新增入库单</button>
            </div>
        </div>
        <div class="card">
            <div class="card-title">最近入库单(最近 10 条)</div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th>单号</th>
                        <th style="width:90px;">状态</th>
                        <th>外部单号</th>
                        <th class="num">明细 / 数量</th>
                        <th>操作员 · 时间</th>
                        <th style="width:100px;">操作</th>
                    </tr></thead>
                    <tbody>${recentRows}</tbody>
                </table>
            </div>
        </div>`;
    },

    /* ================== 4. 出库登记(列表 + 新增按钮) ================== */
    outbound(products, recentOrders) {
        recentOrders = recentOrders || [];
        const recentRows = recentOrders.length === 0
            ? `<tr><td colspan="5" class="empty">暂无出库单,点上方「新增出库单」开始</td></tr>`
            : recentOrders.map(o => `
                <tr>
                    <td><strong>${escapeHtml(o.orderNo)}</strong></td>
                    <td>${escapeHtml(o.customer || '-')}</td>
                    <td class="num">${o.itemCount || 0} 行 / ${o.totalQuantity || 0} 件</td>
                    <td>${escapeHtml(o.operator || '')} · ${formatDate(o.createdAt)}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="viewOrderDetail('${o.orderNo}')">查看</button>
                    </td>
                </tr>`).join('');
        return `
        <div class="page-head">
            <div>
                <h2>出库登记</h2>
                <div class="desc">点「新增出库单」开一张单 → 选商品(库存预校验)→ 确认出库</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="openNewOrderModal('OUT')">+ 新增出库单</button>
            </div>
        </div>
        <div class="card">
            <div class="card-title">最近出库单(最近 10 条)</div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th>单号</th>
                        <th>领用方</th>
                        <th class="num">明细 / 数量</th>
                        <th>操作员 · 时间</th>
                        <th style="width:100px;">操作</th>
                    </tr></thead>
                    <tbody>${recentRows}</tbody>
                </table>
            </div>
        </div>`;
    },

    /* ================== 新增入库/出库单 弹窗 ================== */
    newOrderModal(type, products, suppliers) {
        products = products || [];
        suppliers = suppliers || [];
        const isIn = type === 'IN';

        // 商品下拉框(加大宽度,带默认供应商 data-attr)
        const productOpts = products.map(p => {
            const low = p.quantity <= p.lowStock ? ' [低库存]' : '';
            const dflt = p.defaultSupplier ? ` (默认:${escapeHtml(p.defaultSupplier)})` : '';
            return `<option value="${p.id}" data-stock="${p.quantity}" data-default="${escapeHtml(p.defaultSupplier||'')}" data-default-id="${p.defaultSupplierId||''}">${escapeHtml(p.sku)} - ${escapeHtml(p.name)} (现库存 ${p.quantity}${escapeHtml(p.unit||'')})${low}${dflt}</option>`;
        }).join('');

        // 供应商下拉框(从供应商表抽取,过滤停用)
        const supplierOpts = suppliers
            .filter(s => s.enabled !== false)
            .map(s => `<option value="${escapeHtml(s.name)}">${escapeHtml(s.name)}</option>`)
            .join('');

        // 单行模板 — 入库 / 出库 列结构不同
        const blankRow = () => {
            const productCell  = `<select class="select nw-product" style="width:360px;">${productOpts || '<option value="">暂无商品</option>'}</select>`;
            const supplierCell = isIn
                ? `<select class="select nw-supplier" style="width:200px;"><option value="">(不填)</option>${supplierOpts || '<option value="" disabled>暂无供应商</option>'}</select>`
                : '';
            const qtyCell      = `<input type="number" min="1" class="input nw-qty" placeholder="数量" style="width:100px;">`;
            const remarkCell   = `<input class="input nw-row-remark" placeholder="行备注(可选)">`;
            return `
            <tr class="nw-row">
                <td class="num row-idx">1</td>
                <td>${productCell}</td>
                ${isIn ? `<td>${supplierCell}</td>` : ''}
                <td>${qtyCell}</td>
                <td>${remarkCell}</td>
                <td class="actions"><button class="btn-link danger" onclick="removeNewOrderRow(this)">删除</button></td>
            </tr>`;
        };

        // 表头
        const ths = isIn
            ? `<th style="width:50px;">#</th>
               <th style="width:360px;">商品<span class="req">*</span></th>
               <th style="width:200px;">供应商</th>
               <th style="width:120px;">数量<span class="req">*</span></th>
               <th>行备注</th>
               <th style="width:80px;">操作</th>`
            : `<th style="width:50px;">#</th>
               <th style="width:360px;">商品<span class="req">*</span></th>
               <th style="width:120px;">数量<span class="req">*</span></th>
               <th>行备注</th>
               <th style="width:80px;">操作</th>`;

        const headFields = isIn ? `
            <div class="form-field" style="flex:1;">
                <label>外部单号</label>
                <input id="nw-external-no" class="input" placeholder="采购单号 / 快递单号(可选)">
            </div>
        ` : `
            <div class="form-field" style="flex:1;">
                <label>领用方 / 客户</label>
                <input id="nw-customer" class="input" placeholder="例如:行政部 / XX 销售单">
            </div>
            <div class="form-field" style="flex:1;">
                <label>外部单号</label>
                <input id="nw-external-no" class="input" placeholder="销售单号 / 领用单号(可选)">
            </div>
        `;
        return `
        <div class="form-row">
            ${headFields}
            <div class="form-field" style="flex:2;">
                <label>整单备注</label>
                <input id="nw-remark" class="input" placeholder="可选">
            </div>
        </div>
        <div class="card-title" style="margin-top:8px;">${isIn ? '入库明细' : '出库明细'}(同 ${isIn ? '商品+供应商' : '商品'}会自动合并 · ${isIn ? '每件商品独立 24 位编码' : '整件出库'})</div>
        <div class="table-wrap">
            <table class="table">
                <thead><tr>${ths}</tr></thead>
                <tbody id="nw-rows">${blankRow() + blankRow() + blankRow()}</tbody>
            </table>
        </div>
        <div class="form-row" style="margin-top:10px;justify-content:space-between;align-items:center;">
            <button type="button" class="btn btn-sm" onclick="addNewOrderRow()" style="background:#27ae60;color:#fff;">
                <span style="font-size:14px;font-weight:600;margin-right:4px;">+</span> 添加条目
            </button>
            <span style="font-size:12px;color:#8492a6;">提示:每个"数量"=${isIn ? '将生成等量的独立 24 位编码 / 条形码' : '将整件挑选库存单元扣减'}</span>
        </div>
        <input type="hidden" id="nw-type" value="${type}">`;
    },

    /* ================== 出库单详情 Modal(可打印/导出) ================== */
    outboundOrderModal(o) {
        const barcodeMap = o.barcodesBySku || {};
        const rows = (o.items || []).map((it, i) => {
            // 该行 SKU 对应的库存单元(出库单 = 消耗件)
            const codes = barcodeMap[it.sku] || [];
            const codesText = codes.length === 0
                ? '<span style="color:#aab2bd;font-size:12px;">(无条码记录)</span>'
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
                <td colspan="7" style="background:#fafbfc;padding:6px 12px;">
                    <div style="font-size:12px;color:#8492a6;margin-bottom:4px;">
                        📦 本行 ${codes.length} 个 24 位编码:
                    </div>
                    ${codesText}
                </td>
            </tr>`;
        }).join('');
        return `
        <div class="order-print" id="order-print-area">
            <div class="order-head">
                <h2 style="margin:0 0 6px 0;">出 库 单</h2>
                <div class="order-meta">
                    <div><label>单号:</label><span>${escapeHtml(o.orderNo)}</span></div>
                    <div><label>创建时间:</label><span>${formatDate(o.createdAt)}</span></div>
                    <div><label>操作员:</label><span>${escapeHtml(o.operator || '')}</span></div>
                    <div><label>领用方:</label><span>${escapeHtml(o.customer || '-')}</span></div>
                    <div><label>外部单号:</label><span>${escapeHtml(o.externalNo || '-')}</span></div>
                    <div><label>整单备注:</label><span>${escapeHtml(o.remark || '-')}</span></div>
                </div>
            </div>
            <table class="table" style="margin-top:8px;">
                <thead><tr>
                    <th style="width:50px;">#</th>
                    <th>SKU</th>
                    <th>商品</th>
                    <th>库位</th>
                    <th class="num">出库数量</th>
                    <th class="num">单价</th>
                    <th>行备注</th>
                </tr></thead>
                <tbody>${rows || `<tr><td colspan="7" class="empty">无明细</td></tr>`}</tbody>
                <tfoot>
                    <tr>
                        <td colspan="4" style="text-align:right;font-weight:600;">合计</td>
                        <td class="num" style="font-weight:600;">${o.totalQuantity || 0}</td>
                        <td colspan="2"></td>
                    </tr>
                </tfoot>
            </table>
            <div class="order-sign" style="margin-top:24px;display:flex;justify-content:space-between;color:#5d6d7e;font-size:13px;">
                <div>发货人:_______________</div>
                <div>领用人:_______________</div>
                <div>打印时间:<span id="order-print-time"></span></div>
            </div>
        </div>
        <div class="form-row" style="margin-top:16px;justify-content:flex-end;">
            <button class="btn btn-sm" onclick="printOutboundOrder()">🖨 打印</button>
            <a class="btn btn-sm" href="/api/outbound/orders/${o.id}/export.csv" style="margin-left:8px;text-decoration:none;">⬇ 导出 CSV</a>
        </div>`;
    },

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
            ? `<tr><td colspan="8" class="empty">暂无单据</td></tr>`
            : orders.map(o => {
                const tBadge = o.orderNo && o.orderNo.startsWith('IN-')
                    ? '<span class="badge in">入库</span>'
                    : '<span class="badge out">出库</span>';
                // 入库单:列表里只显示外部单号(主表 supplier 字段已废弃)
                const partner = (type === 'IN')
                    ? (o.externalNo ? `[外单] ${escapeHtml(o.externalNo)}` : '[多供应商]')
                    : (o.customer || '-');
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
                        <button class="btn-link" onclick="viewOrderDetail('${o.orderNo}')">查看</button>
                    </td>
                </tr>`;
            }).join('');

        return `
        <div class="page-head">
            <div>
                <h2>出入库单</h2>
                <div class="desc">历史入库单/出库单查询,可按日期/单号/客户/供应商/操作员筛选</div>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <select id="od-type" class="select" style="width:120px;">
                    <option value="IN"  ${type==='IN' ?'selected':''}>入库单</option>
                    <option value="OUT" ${type==='OUT'?'selected':''}>出库单</option>
                </select>
                <label style="font-size:13px;color:#5d6d7e;">起</label>
                <input id="od-begin" type="date" class="input input-sm" value="${beginVal}">
                <label style="font-size:13px;color:#5d6d7e;">止</label>
                <input id="od-end" type="date" class="input input-sm" value="${endVal}">
                <input id="od-kw" class="input input-sm" placeholder="单号 / 供应商 / 客户 / 外部单号 / 备注" value="${escapeHtml(kwVal)}" style="width:280px;">
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
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:60px;">ID</th>
                        <th style="width:80px;">类型</th>
                        <th>单号</th>
                        <th>${type==='OUT' ? '领用方' : '供应商'}</th>
                        <th>外部单号</th>
                        <th class="num">明细数</th>
                        <th class="num">总数量</th>
                        <th>操作员</th>
                        <th style="width:160px;">创建时间</th>
                        <th style="width:100px;">操作</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div class="toolbar" style="margin-top:14px;justify-content:flex-end;">
                <span style="color:#8492a6;font-size:13px;">共 ${total} 条 · 第 ${page} / ${pages} 页</span>
                <button class="btn btn-sm" ${page<=1?'disabled':''} onclick="gotoOrderPage(${page-1})">上一页</button>
                <button class="btn btn-sm" ${page>=pages?'disabled':''} onclick="gotoOrderPage(${page+1})">下一页</button>
            </div>
        </div>`;
    },
    /* ================== 入库单详情 Modal(可打印/导出) ================== */
    inboundOrderModal(o) {
        const barcodeMap = o.barcodesBySku || {};
        const rows = (o.items || []).map((it, i) => {
            // 该行 SKU 对应的库存单元(入库单 = 生成件)
            const codes = barcodeMap[it.sku] || [];
            const codesText = codes.length === 0
                ? '<span style="color:#aab2bd;font-size:12px;">(无条码记录)</span>'
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
                <td colspan="8" style="background:#fafbfc;padding:6px 12px;">
                    <div style="font-size:12px;color:#8492a6;margin-bottom:4px;">
                        📦 本行 ${codes.length} 个 24 位编码:
                    </div>
                    ${codesText}
                </td>
            </tr>`;
        }).join('');
        return `
        <div class="order-print" id="order-print-area">
            <div class="order-head">
                <h2 style="margin:0 0 6px 0;">入 库 单</h2>
                <div class="order-meta">
                    <div><label>单号:</label><span>${escapeHtml(o.orderNo)}</span></div>
                    <div><label>创建时间:</label><span>${formatDate(o.createdAt)}</span></div>
                    <div><label>操作员:</label><span>${escapeHtml(o.operator || '')}</span></div>
                    <div><label>外部单号:</label><span>${escapeHtml(o.externalNo || '-')}</span></div>
                    <div><label>整单备注:</label><span>${escapeHtml(o.remark || '-')}</span></div>
                </div>
            </div>
            <table class="table" style="margin-top:8px;">
                <thead><tr>
                    <th style="width:50px;">#</th>
                    <th style="width:160px;">供应商</th>
                    <th>SKU</th>
                    <th>商品</th>
                    <th>库位</th>
                    <th class="num">入库数量</th>
                    <th class="num">单价</th>
                    <th>行备注</th>
                </tr></thead>
                <tbody>${rows || `<tr><td colspan="8" class="empty">无明细</td></tr>`}</tbody>
                <tfoot>
                    <tr>
                        <td colspan="5" style="text-align:right;font-weight:600;">合计</td>
                        <td class="num" style="font-weight:600;">${o.totalQuantity || 0}</td>
                        <td colspan="2"></td>
                    </tr>
                </tfoot>
            </table>
            <div class="order-sign" style="margin-top:24px;display:flex;justify-content:space-between;color:#5d6d7e;font-size:13px;">
                <div>仓管签字:_______________</div>
                <div>验收人:_______________</div>
                <div>打印时间:<span id="order-print-time"></span></div>
            </div>
        </div>
        <div class="form-row" style="margin-top:16px;justify-content:flex-end;">
            <button class="btn btn-sm" onclick="printInboundOrder()">🖨 打印</button>
            <button class="btn btn-sm" onclick="printAllInboundBarcodes(${o.id})">📦 打印全部条码</button>
            <a class="btn btn-sm" href="/api/inbound/orders/${o.id}/export.csv" style="margin-left:8px;text-decoration:none;">⬇ 导出 CSV</a>
            ${o.status === 'DRAFT' ? `
                <button class="btn btn-sm btn-primary" style="margin-left:8px;" onclick="commitInboundOrder(${o.id}, '${escapeHtml(o.orderNo)}')">✓ 提交入库</button>
                <button class="btn btn-sm" style="margin-left:8px;color:#e74c3c;border-color:#e74c3c;" onclick="cancelInboundOrder(${o.id}, '${escapeHtml(o.orderNo)}')">✗ 取消</button>
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
        // 库位下拉:含「(不选)」+ 启用中的库位;商品当前库位若不在启用列表里,临时插入以便编辑
        const enabledLocs = locations.filter(l => l.enabled !== false);
        const enabledCodes = new Set(enabledLocs.map(l => l.code));
        const currentLocCode = p.location || '';
        const extraLoc = (currentLocCode && !enabledCodes.has(currentLocCode))
            ? [{ code: currentLocCode, name: '(已停用或已删除)', enabled: true }] : [];
        const locSelectOptions = [{ code: '', name: '(不选)', enabled: true }, ...enabledLocs, ...extraLoc]
            .map(l => `<option value="${escapeHtml(l.code)}" ${l.code===currentLocCode?'selected':''}>${escapeHtml(l.code)}${l.name && l.name !== l.code ? ' / ' + escapeHtml(l.name) : ''}</option>`)
            .join('');

        return `
        <div class="form-row">
            <div class="form-field">
                <label>SKU<span class="req">*</span></label>
                <input id="pf-sku" class="input" value="${escapeHtml(p.sku||'')}" placeholder="例如 SP-001">
            </div>
            <div class="form-field">
                <label>名称<span class="req">*</span></label>
                <input id="pf-name" class="input" value="${escapeHtml(p.name||'')}" placeholder="商品名称">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field">
                <label>分类</label>
                <input id="pf-cat" class="input" value="${escapeHtml(p.category||'')}" placeholder="例如 办公耗材">
            </div>
            <div class="form-field">
                <label>单位</label>
                <input id="pf-unit" class="input" value="${escapeHtml(p.unit||'')}" placeholder="例如 个/箱/包">
            </div>
            <div class="form-field">
                <label>库位<span class="req">*</span></label>
                <select id="pf-loc" class="select">
                    ${locSelectOptions || '<option value="" disabled>暂无库位,请先到「基础管理 → 库位管理」添加</option>'}
                </select>
            </div>
        </div>
        <div class="form-row">
            <div class="form-field">
                <label>初始库存</label>
                <input id="pf-qty" type="number" min="0" class="input" value="${p.quantity ?? 0}">
            </div>
            <div class="form-field">
                <label>单价 (元)</label>
                <input id="pf-price" type="number" min="0" step="0.01" class="input" value="${p.unitPrice ?? 0}">
            </div>
            <div class="form-field">
                <label>低库存预警</label>
                <input id="pf-low" type="number" min="0" class="input" value="${p.lowStock ?? 10}">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:2;">
                <label>供应商(可多选)</label>
                <select id="pf-suppliers" class="select" multiple size="4" style="min-height:80px;">
                    ${supplierOptions || '<option value="" disabled>暂无可选供应商,请先到「基础管理 → 供应商管理」添加</option>'}
                </select>
            </div>
            <div class="form-field">
                <label>默认供应商</label>
                <select id="pf-default-supplier" class="select">
                    <option value="">—</option>
                    ${defaultOptions}
                </select>
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>备注</label>
                <input id="pf-remark" class="input" value="${escapeHtml(p.remark||'')}" placeholder="可选">
            </div>
        </div>
        <input type="hidden" id="pf-id" value="${p.id || ''}">
        <input type="hidden" id="pf-selected-suppliers" value="${escapeHtml(JSON.stringify(selected))}">
        <input type="hidden" id="pf-default-supplier-id" value="${defaultId}">`;
    },

    /* ================== 供应商管理列表 ================== */
    suppliers(suppliers) {
        const rows = suppliers.length === 0
            ? `<tr><td colspan="6" class="empty">暂无供应商,点击右上角「新增」开始</td></tr>`
            : suppliers.map(s => `
                <tr>
                    <td>${s.id}</td>
                    <td><strong>${escapeHtml(s.name)}</strong></td>
                    <td>${escapeHtml(s.contact || '')}</td>
                    <td>${escapeHtml(s.phone || '')}</td>
                    <td>${escapeHtml(s.email || '')}</td>
                    <td>${s.enabled === false ? '<span class="badge low">已停用</span>' : '<span class="badge ok">启用</span>'}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="editSupplier(${s.id})">编辑</button>
                        <button class="btn-link danger" onclick="delSupplier(${s.id}, '${escapeHtml(s.name)}')">删除</button>
                    </td>
                </tr>`).join('');
        return `
        <div class="page-head">
            <div>
                <h2>供应商管理</h2>
                <div class="desc">维护供应商主数据,商品管理中可绑定多个供应商并指定默认</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="newSupplier()">+ 新增供应商</button>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <input id="sup-kw" class="input input-sm" placeholder="搜索 名称 / 联系人 / 电话" style="width:280px;">
                <button class="btn btn-sm" onclick="searchSuppliers()">搜索</button>
                <button class="btn btn-sm" onclick="document.getElementById('sup-kw').value='';searchSuppliers()">清空</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:60px;">ID</th>
                        <th>名称</th>
                        <th>联系人</th>
                        <th>电话</th>
                        <th>邮箱</th>
                        <th style="width:90px;">状态</th>
                        <th style="width:140px;">操作</th>
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
                <label>名称<span class="req">*</span></label>
                <input id="sf-name" class="input" value="${escapeHtml(s.name||'')}" placeholder="供应商名称">
            </div>
            <div class="form-field">
                <label>联系人</label>
                <input id="sf-contact" class="input" value="${escapeHtml(s.contact||'')}" placeholder="王经理">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field">
                <label>电话</label>
                <input id="sf-phone" class="input" value="${escapeHtml(s.phone||'')}" placeholder="021-xxxxxxxx">
            </div>
            <div class="form-field">
                <label>邮箱</label>
                <input id="sf-email" class="input" value="${escapeHtml(s.email||'')}" placeholder="sales@example.com">
            </div>
            <div class="form-field">
                <label>状态</label>
                <select id="sf-enabled" class="select">
                    <option value="true"  ${s.enabled===false?'':'selected'}>启用</option>
                    <option value="false" ${s.enabled===false?'selected':''}>停用</option>
                </select>
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>地址</label>
                <input id="sf-address" class="input" value="${escapeHtml(s.address||'')}" placeholder="可选">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>备注</label>
                <input id="sf-remark" class="input" value="${escapeHtml(s.remark||'')}" placeholder="可选">
            </div>
        </div>
        <input type="hidden" id="sf-id" value="${s.id || ''}">`;
    },

    /* ================== 库位管理列表 ================== */
    locations(locations) {
        const rows = locations.length === 0
            ? `<tr><td colspan="5" class="empty">暂无库位,点击右上角「新增」开始</td></tr>`
            : locations.map(l => `
                <tr>
                    <td><strong>${escapeHtml(l.code)}</strong></td>
                    <td>${escapeHtml(l.name || '')}</td>
                    <td>${escapeHtml(l.remark || '')}</td>
                    <td>${l.enabled === false ? '<span class="badge low">已停用</span>' : '<span class="badge ok">启用</span>'}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="editLocation(${l.id})">编辑</button>
                        <button class="btn-link danger" onclick="delLocation(${l.id}, '${escapeHtml(l.code)}')">删除</button>
                    </td>
                </tr>`).join('');
        return `
        <div class="page-head">
            <div>
                <h2>库位管理</h2>
                <div class="desc">维护仓库库位字典,商品管理中库位字段从此处选择</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="newLocation()">+ 新增库位</button>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <input id="loc-kw" class="input input-sm" placeholder="搜索 编码 / 名称" style="width:280px;">
                <button class="btn btn-sm" onclick="searchLocations()">搜索</button>
                <button class="btn btn-sm" onclick="document.getElementById('loc-kw').value='';searchLocations()">清空</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:120px;">编码</th>
                        <th>名称</th>
                        <th>备注</th>
                        <th style="width:90px;">状态</th>
                        <th style="width:140px;">操作</th>
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
                <label>编码<span class="req">*</span></label>
                <input id="lf-code" class="input" value="${escapeHtml(l.code||'')}" placeholder="例如 A-01-03">
            </div>
            <div class="form-field">
                <label>名称</label>
                <input id="lf-name" class="input" value="${escapeHtml(l.name||'')}" placeholder="例如 A 区 01 排 03 位">
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>备注</label>
                <input id="lf-remark" class="input" value="${escapeHtml(l.remark||'')}" placeholder="可选">
            </div>
            <div class="form-field">
                <label>状态</label>
                <select id="lf-enabled" class="select">
                    <option value="true"  ${l.enabled===false?'':'selected'}>启用</option>
                    <option value="false" ${l.enabled===false?'selected':''}>停用</option>
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
            'DRAFT':     '<span class="badge" style="background:#dde2e8;color:#5d6d7e;">草稿</span>',
            'COMMITTED': '<span class="badge ok">已提交</span>',
            'CANCELLED': '<span class="badge low">已取消</span>'
        };
        const rows = list.length === 0
            ? `<tr><td colspan="7" class="empty">暂无盘点单,点击「新建盘点」开始</td></tr>`
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
                        <button class="btn-link" onclick="openStockCheckDetail(${c.id})">查看</button>
                        ${c.status === 'COMMITTED' ? `<a class="btn-link" href="/api/stock-checks/${c.id}/export.csv">导出</a>` : ''}
                    </td>
                </tr>`).join('');
        return `
        <div class="page-head">
            <div>
                <h2>库存盘点</h2>
                <div class="desc">按范围(全部/SKU/库位)创建盘点单,填入实盘数后提交生成差异报告</div>
            </div>
            <div class="right">
                <button class="btn btn-primary" onclick="openNewStockCheckModal()">+ 新建盘点</button>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <label style="font-size:13px;color:#5d6d7e;">起</label>
                <input id="sc-begin" type="date" class="input input-sm" value="${escapeHtml(filter.beginDate||'')}">
                <label style="font-size:13px;color:#5d6d7e;">止</label>
                <input id="sc-end" type="date" class="input input-sm" value="${escapeHtml(filter.endDate||'')}">
                <select id="sc-status" class="select" style="width:120px;">
                    <option value=""          ${filter.status===''?'selected':''}>全部状态</option>
                    <option value="DRAFT"     ${filter.status==='DRAFT'?'selected':''}>草稿</option>
                    <option value="COMMITTED" ${filter.status==='COMMITTED'?'selected':''}>已提交</option>
                    <option value="CANCELLED" ${filter.status==='CANCELLED'?'selected':''}>已取消</option>
                </select>
                <button class="btn btn-sm btn-primary" onclick="searchStockChecks()">查询</button>
                <button class="btn btn-sm" onclick="resetStockCheckFilter()">重置</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th>盘点单号</th>
                        <th>范围</th>
                        <th>操作员</th>
                        <th>状态</th>
                        <th class="num">明细数</th>
                        <th class="num">已盘数</th>
                        <th class="num">差异数</th>
                        <th>创建时间</th>
                        <th>操作</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div class="toolbar" style="margin-top:14px;justify-content:flex-end;">
                <span style="color:#8492a6;font-size:13px;">共 ${total} 条 · 第 ${page} / ${pages} 页</span>
                <button class="btn btn-sm" ${page<=1?'disabled':''} onclick="gotoStockCheckPage(${page-1})">上一页</button>
                <button class="btn btn-sm" ${page>=pages?'disabled':''} onclick="gotoStockCheckPage(${page+1})">下一页</button>
            </div>
        </div>`;
    },

    newStockCheckForm() {
        return `
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>盘点范围<span class="req">*</span></label>
                <select id="nsc-scope" class="select" style="width:100%;">
                    <option value="ALL">全部在库单元</option>
                    <option value="SKU">按 SKU</option>
                    <option value="LOCATION">按库位</option>
                </select>
            </div>
            <div class="form-field" style="flex:2;">
                <label id="nsc-keyword-label">关键字</label>
                <input id="nsc-keyword" class="input" placeholder="ALL 模式留空" disabled>
            </div>
        </div>
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>备注</label>
                <input id="nsc-remark" class="input" placeholder="例如:月末大盘点">
            </div>
        </div>
        <div style="font-size:12px;color:#8492a6;margin-top:8px;">
            创建后将立即快照当前在库单元 → 进入详情填入实盘数 → 提交生成差异报告
        </div>`;
    },

    stockCheckDetail(c) {
        const isDraft = c.status === 'DRAFT';
        const scStyle = (sys, counted) => {
            if (counted == null) return '';
            if (counted === sys) return 'background:#eafaf1;';
            return 'background:#fdecea;';
        };
        const itemsHtml = (c.items || []).map((it, idx) => `
            <tr style="${scStyle(it.systemQty, it.countedQty)}">
                <td class="num">${idx + 1}</td>
                <td><span style="font-family:monospace;font-size:12px;">${escapeHtml(it.code)}</span></td>
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
                        ? `<input id="sci-rem-${it.id}" class="input input-sm" style="width:140px;" placeholder="行备注(可选)" value="${escapeHtml(it.rowRemark || '')}">`
                        : escapeHtml(it.rowRemark || '-')}
                </td>
                <td class="actions">
                    ${isDraft ? `<button id="sci-save-${it.id}" class="btn-link">保存</button>` : '-'}
                </td>
            </tr>`).join('');

        const summary = `
            <div style="display:flex;gap:18px;margin:8px 0 16px;flex-wrap:wrap;">
                <span>盘点单号:<strong>${escapeHtml(c.checkNo)}</strong></span>
                <span>范围:<strong>${escapeHtml(scopeLabel(c.scope))}${c.scopeKeyword ? ' / ' + escapeHtml(c.scopeKeyword) : ''}</strong></span>
                <span>操作员:<strong>${escapeHtml(c.operator)}</strong></span>
                <span>状态:<strong>${escapeHtml(c.status)}</strong></span>
                <span>明细/已盘/差异:<strong>${c.totalCount} / ${c.countedCount} / ${c.diffCount}</strong></span>
                <span>创建时间:<strong>${formatDate(c.createdAt)}</strong></span>
            </div>`;

        const headBtns = isDraft
            ? `<button id="sc-cancel-btn" class="btn btn-sm" style="background:#dde2e8;color:#5d6d7e;">取消盘点</button>`
            : `<button id="sc-export-btn" class="btn btn-sm">⬇ 导出 CSV</button>`;

        return `
        <div>${summary}</div>
        ${headBtns ? `<div style="margin-bottom:8px;">${headBtns}</div>` : ''}
        <div style="font-size:12px;color:#8492a6;margin-bottom:8px;">
            ${isDraft ? '草稿:填入实盘数后点每行的「保存」,全部填完后回到此弹窗点底部「提交盘点」' : '已提交:报告只读,可导出 CSV'}
        </div>
        <div class="table-wrap">
            <table class="table">
                <thead><tr>
                    <th style="width:40px;">#</th>
                    <th style="width:180px;">24 位编码</th>
                    <th>SKU</th>
                    <th>商品</th>
                    <th>库位</th>
                    <th class="num">系统数</th>
                    <th class="num">实盘数</th>
                    <th class="num">差异</th>
                    <th>行备注</th>
                    <th>操作</th>
                </tr></thead>
                <tbody>${itemsHtml || '<tr><td colspan="10" class="empty">无明细</td></tr>'}</tbody>
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
            'IN_STOCK'   : '<span class="badge ok">在库</span>',
            'OUT'        : '<span class="badge low">已出库</span>',
            'SCRAPPED'   : '<span class="badge" style="background:#dde2e8;color:#7f8c8d;">已报废</span>',
            'TRANSFERRED': '<span class="badge" style="background:#dde2e8;color:#7f8c8d;">已调拨</span>'
        };

        const rows = items.length === 0
            ? `<tr><td colspan="10" class="empty">暂无库存单元,完成一次入库后自动生成 24 位编码</td></tr>`
            : items.map(it => `
                <tr>
                    <td><span style="font-family:monospace;font-size:12px;">${escapeHtml(it.code)}</span></td>
                    <td><strong>${escapeHtml(it.sku)}</strong></td>
                    <td>${escapeHtml(it.productName)}</td>
                    <td>${escapeHtml(it.locationCode || '-')}</td>
                    <td class="num">${it.quantity || 0}</td>
                    <td class="num"><strong>${it.remaining || 0}</strong></td>
                    <td>${statusMap[it.status] || escapeHtml(it.status)}</td>
                    <td>${escapeHtml(it.createdBy || '-')}</td>
                    <td>${formatDate(it.createdAt)}</td>
                    <td class="actions" style="white-space:nowrap;">
                        <button class="btn-link" onclick="viewBarcode(${it.id}, '${escapeHtml(it.code)}')">条码</button>
                        ${it.status === 'IN_STOCK' ? `
                            <button class="btn-link" onclick="transferInventory(${it.id}, '${escapeHtml(it.code)}', '${escapeHtml(it.locationCode)}')">调拨</button>
                            <button class="btn-link danger" onclick="scrapInventory(${it.id}, '${escapeHtml(it.code)}')">报废</button>
                        ` : '<span style="color:#aab2bd;font-size:12px;">—</span>'}
                    </td>
                </tr>`).join('');

        return `
        <div class="page-head">
            <div>
                <h2>库存管理</h2>
                <div class="desc">每个入库批次生成独立 24 位编码,支持条码打印、报废、调拨</div>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <input id="inv-kw" class="input input-sm" placeholder="搜索 SKU(模糊)" value="${escapeHtml(state.inventoryKw||'')}" style="width:200px;">
                <select id="inv-loc" class="select" style="width:160px;">
                    <option value="">全部库位</option>
                    ${locOpts}
                </select>
                <select id="inv-status" class="select" style="width:120px;">
                    <option value="IN_STOCK"    ${state.inventoryStatus==='IN_STOCK'?'selected':''}>在库</option>
                    <option value="OUT"         ${state.inventoryStatus==='OUT'?'selected':''}>已出库</option>
                    <option value="SCRAPPED"    ${state.inventoryStatus==='SCRAPPED'?'selected':''}>已报废</option>
                    <option value="TRANSFERRED" ${state.inventoryStatus==='TRANSFERRED'?'selected':''}>已调拨</option>
                </select>
                <button class="btn btn-sm btn-primary" onclick="searchInventory()">查询</button>
                <button class="btn btn-sm" onclick="resetInventoryFilter()">重置</button>
                <span style="flex:1;"></span>
                <a id="inv-export-btn" class="btn btn-sm" href="/api/inventory/items/export.csv"
                   style="text-decoration:none;">⬇ 导出 CSV</a>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:200px;">24 位编码</th>
                        <th>SKU</th>
                        <th>商品</th>
                        <th>库位</th>
                        <th class="num">原始数量</th>
                        <th class="num">剩余</th>
                        <th>状态</th>
                        <th>创建人</th>
                        <th style="width:160px;">入库时间</th>
                        <th style="width:200px;">操作</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div class="toolbar" style="margin-top:14px;justify-content:flex-end;">
                <span style="color:#8492a6;font-size:13px;">共 ${total} 条 · 第 ${page} / ${pages} 页</span>
                <button class="btn btn-sm" ${page<=1?'disabled':''} onclick="gotoInventoryPage(${page-1})">上一页</button>
                <button class="btn btn-sm" ${page>=pages?'disabled':''} onclick="gotoInventoryPage(${page+1})">下一页</button>
            </div>
        </div>`;
    }
};

function scopeLabel(s) {
    if (!s) return '';
    switch (s) {
        case 'ALL': return '全部';
        case 'SKU': return '按 SKU';
        case 'LOCATION': return '按库位';
        default: return s;
    }
}