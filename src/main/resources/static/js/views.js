/* ============================================
 *  views.js  -  5 个模块的 HTML 模板
 *  每个函数返回 HTML 字符串,由 app.js 注入到 #view
 * ============================================ */

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
        <div class="card" style="margin-top:24px;">
            <div class="card-title">使用说明</div>
            <ol style="line-height:2;color:#5d6d7e;">
                <li>点击顶部菜单 <strong>商品管理</strong> 新增、修改或删除商品(必填 SKU、名称)</li>
                <li>点击 <strong>入库登记</strong> 选择商品并填写数量,库存自动增加</li>
                <li>点击 <strong>出库登记</strong> 选择商品并填写数量,库存不足时会拒绝并提示</li>
                <li>所有出入库记录都会自动写入 <strong>库存流水</strong>,可按类型/关键字检索</li>
                <li>默认使用 H2 内嵌数据库,数据保存在 <code>./data/</code> 目录;切到 MySQL 见 README</li>
            </ol>
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
            ? `<tr><td colspan="5" class="empty">暂无入库单,点上方「新增入库单」开始</td></tr>`
            : recentOrders.map(o => `
                <tr>
                    <td><strong>${escapeHtml(o.orderNo)}</strong></td>
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
    newOrderModal(type, products) {
        products = products || [];
        const opts = products.map(p => {
            const low = p.quantity <= p.lowStock ? ' [低库存]' : '';
            const dflt = p.defaultSupplier ? ` (默认:${escapeHtml(p.defaultSupplier)})` : '';
            return `<option value="${p.id}" data-stock="${p.quantity}" data-default="${escapeHtml(p.defaultSupplier||'')}" data-default-id="${p.defaultSupplierId||''}">${escapeHtml(p.sku)} - ${escapeHtml(p.name)} (现库存 ${p.quantity}${escapeHtml(p.unit||'')})${low}${dflt}</option>`;
        }).join('');
        const blankRow = () => `
            <tr class="nw-row">
                <td class="num row-idx">1</td>
                <td>
                    <select class="select nw-product" style="width:100%;">${opts || '<option value="">暂无商品</option>'}</select>
                </td>
                <td><input type="number" min="1" class="input nw-qty" placeholder="数量"></td>
                <td><input class="input nw-supplier" placeholder="供应商(预填默认,可改)"></td>
                <td><input class="input nw-row-remark" placeholder="行备注(可选)"></td>
                <td class="actions"><button class="btn-link danger" onclick="removeNewOrderRow(this)">删除</button></td>
            </tr>`;
        const isIn = type === 'IN';
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
        <div class="card-title" style="margin-top:8px;">${isIn ? '入库明细' : '出库明细'}(同 ${isIn ? '商品+供应商' : '商品'}会自动合并)</div>
        <div class="table-wrap">
            <table class="table">
                <thead><tr>
                    <th style="width:50px;">#</th>
                    <th>商品<span class="req">*</span></th>
                    <th style="width:120px;">数量<span class="req">*</span></th>
                    <th style="width:200px;">${isIn ? '供应商' : '备注'}</th>
                    <th>行备注</th>
                    <th style="width:80px;">操作</th>
                </tr></thead>
                <tbody id="nw-rows">${blankRow() + blankRow() + blankRow()}</tbody>
            </table>
        </div>
        <input type="hidden" id="nw-type" value="${type}">`;
    },

    /* ================== 出库单详情 Modal(可打印/导出) ================== */
    outboundOrderModal(o) {
        const rows = (o.items || []).map((it, i) => `
            <tr>
                <td class="num">${i + 1}</td>
                <td><strong>${escapeHtml(it.sku)}</strong></td>
                <td>${escapeHtml(it.productName)}</td>
                <td class="num">${it.quantity}</td>
                <td class="num">¥${formatMoney(it.unitPrice)}</td>
                <td class="num">${it.beforeQty} → ${it.afterQty}</td>
                <td>${escapeHtml(it.rowRemark || '')}</td>
            </tr>
        `).join('');
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
                    <th class="num">出库数量</th>
                    <th class="num">单价</th>
                    <th class="num">库存(前→后)</th>
                    <th>行备注</th>
                </tr></thead>
                <tbody>${rows || `<tr><td colspan="7" class="empty">无明细</td></tr>`}</tbody>
                <tfoot>
                    <tr>
                        <td colspan="3" style="text-align:right;font-weight:600;">合计</td>
                        <td class="num" style="font-weight:600;">${o.totalQuantity || 0}</td>
                        <td colspan="3"></td>
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

        const beginVal = (filter && filter.beginDate) || '';
        const endVal   = (filter && filter.endDate)   || '';
        const kwVal    = (filter && filter.keyword)   || '';
        const opVal    = (filter && filter.operator)  || '';

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
    ledger(logs, type, keyword) {
        const rows = logs.length === 0
            ? `<tr><td colspan="9" class="empty">暂无流水记录</td></tr>`
            : logs.map(l => {
                const tBadge = l.type === 'IN'
                    ? '<span class="badge in">入库</span>'
                    : '<span class="badge out">出库</span>';
                return `
                <tr>
                    <td>${l.id}</td>
                    <td>${tBadge}</td>
                    <td><strong>${escapeHtml(l.sku)}</strong></td>
                    <td>${escapeHtml(l.productName)}</td>
                    <td class="num">${l.quantity}</td>
                    <td class="num">${l.beforeQty}</td>
                    <td class="num">${l.afterQty}</td>
                    <td>${escapeHtml(l.operator || '')}</td>
                    <td>${escapeHtml(l.remark || '')}</td>
                    <td>${formatDate(l.createdAt)}</td>
                </tr>`;
            }).join('');
        return `
        <div class="page-head">
            <div>
                <h2>库存流水</h2>
                <div class="desc">所有出入库操作自动记录,共 ${logs.length} 条</div>
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <select id="lg-type" class="select" style="width:140px;">
                    <option value="ALL" ${type==='ALL'?'selected':''}>全部类型</option>
                    <option value="IN"  ${type==='IN' ?'selected':''}>入库</option>
                    <option value="OUT" ${type==='OUT'?'selected':''}>出库</option>
                </select>
                <input id="lg-kw" class="input input-sm" placeholder="搜索 SKU / 名称 / 备注 / 操作员" value="${escapeHtml(keyword||'')}" style="width:280px;">
                <button class="btn btn-sm" onclick="searchLedger()">查询</button>
                <button class="btn btn-sm" onclick="document.getElementById('lg-kw').value='';document.getElementById('lg-type').value='ALL';searchLedger()">重置</button>
            </div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:60px;">ID</th>
                        <th style="width:80px;">类型</th>
                        <th>SKU</th>
                        <th>商品</th>
                        <th class="num">数量</th>
                        <th class="num">操作前</th>
                        <th class="num">操作后</th>
                        <th>操作员</th>
                        <th>备注</th>
                        <th style="width:160px;">时间</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    },

    /* ================== 入库单详情 Modal(可打印/导出) ================== */
    inboundOrderModal(o) {
        const rows = (o.items || []).map((it, i) => `
            <tr>
                <td class="num">${i + 1}</td>
                <td>${escapeHtml(it.supplier || '-')}</td>
                <td><strong>${escapeHtml(it.sku)}</strong></td>
                <td>${escapeHtml(it.productName)}</td>
                <td class="num">${it.quantity}</td>
                <td class="num">¥${formatMoney(it.unitPrice)}</td>
                <td class="num">${it.beforeQty} → ${it.afterQty}</td>
                <td>${escapeHtml(it.rowRemark || '')}</td>
            </tr>
        `).join('');
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
                    <th class="num">入库数量</th>
                    <th class="num">单价</th>
                    <th class="num">库存(前→后)</th>
                    <th>行备注</th>
                </tr></thead>
                <tbody>${rows || `<tr><td colspan="8" class="empty">无明细</td></tr>`}</tbody>
                <tfoot>
                    <tr>
                        <td colspan="4" style="text-align:right;font-weight:600;">合计</td>
                        <td class="num" style="font-weight:600;">${o.totalQuantity || 0}</td>
                        <td colspan="3"></td>
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
            <a class="btn btn-sm" href="/api/inbound/orders/${o.id}/export.csv" style="margin-left:8px;text-decoration:none;">⬇ 导出 CSV</a>
        </div>`;
    }

    /* ================== 商品表单 Modal 内容 ================== */
    productForm(p, suppliers) {
        p = p || {};
        suppliers = suppliers || [];
        const selected = (p.suppliers || []).map(s => s.id);
        const defaultId = (p.suppliers || []).find(s => s.isDefault)?.id
                       || p.defaultSupplierId
                       || (selected[0] || '');
        const supplierOptions = suppliers.map(s =>
            `<option value="${s.id}">${escapeHtml(s.name)}</option>`
        ).join('');
        const defaultOptions = suppliers.map(s =>
            `<option value="${s.id}" ${s.id===defaultId?'selected':''}>${escapeHtml(s.name)}</option>`
        ).join('');

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
                <label>库位</label>
                <input id="pf-loc" class="input" value="${escapeHtml(p.location||'')}" placeholder="例如 A-01-03">
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
                    ${supplierOptions || '<option value="" disabled>暂无可选供应商,请先到「供应商」菜单添加</option>'}
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
    }
    }
};