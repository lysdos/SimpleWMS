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
                return `
                <tr>
                    <td>${p.id}</td>
                    <td><strong>${escapeHtml(p.sku)}</strong></td>
                    <td>${escapeHtml(p.name)} ${lowBadge}</td>
                    <td>${escapeHtml(p.category || '')}</td>
                    <td>${escapeHtml(p.unit || '')}</td>
                    <td>${escapeHtml(p.location || '')}</td>
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
                <div class="desc">维护商品基础信息(SKU、名称、分类、单位、库位、数量、单价)</div>
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
                        <th>库位</th>
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

    /* ================== 3. 入库登记(多货,行级供应商) ================== */
    inbound(products) {
        const opts = products.map(p =>
            `<option value="${p.id}" data-stock="${p.quantity}" data-unit="${escapeHtml(p.unit||'')}">${escapeHtml(p.sku)} - ${escapeHtml(p.name)} (现库存 ${p.quantity}${escapeHtml(p.unit||'')})</option>`
        ).join('');
        const blankRow = () => `
            <tr class="ib-row">
                <td class="num row-idx">1</td>
                <td>
                    <select class="select ib-product" style="width:100%;">${opts || '<option value="">暂无商品</option>'}</select>
                </td>
                <td><input type="number" min="1" class="input ib-qty" placeholder="数量"></td>
                <td><input class="input ib-supplier" placeholder="供应商(可选)"></td>
                <td><input class="input ib-row-remark" placeholder="行备注(可选)"></td>
                <td class="actions"><button class="btn-link danger" onclick="removeInboundRow(this)">删除</button></td>
            </tr>`;
        return `
        <div class="page-head">
            <div>
                <h2>入库登记</h2>
                <div class="desc">一次开一张入库单 → 多个商品可来自不同供应商 → 确认入库 → 弹出入库单(可打印/导出 CSV)</div>
            </div>
            <div class="right">
                <button class="btn btn-sm" onclick="addInboundRow()">+ 添加一行</button>
            </div>
        </div>
        <div class="card">
            <div class="form-row">
                <div class="form-field" style="flex:1;">
                    <label>外部单号</label>
                    <input id="ib-external-no" class="input" placeholder="采购单号 / 快递单号(可选)">
                </div>
                <div class="form-field" style="flex:2;">
                    <label>整单备注</label>
                    <input id="ib-remark" class="input" placeholder="例如:2026-09 集中到货">
                </div>
            </div>
        </div>
        <div class="card" style="margin-top:16px;">
            <div class="card-title">入库明细(同商品+同供应商会自动合并)</div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:50px;">#</th>
                        <th>商品<span class="req">*</span></th>
                        <th style="width:120px;">数量<span class="req">*</span></th>
                        <th style="width:180px;">供应商</th>
                        <th>行备注</th>
                        <th style="width:80px;">操作</th>
                    </tr></thead>
                    <tbody id="ib-rows">${blankRow() + blankRow() + blankRow()}</tbody>
                </table>
            </div>
            <div style="margin-top:12px;text-align:right;">
                <button class="btn btn-sm" onclick="addInboundRow()">+ 添加一行</button>
                <button class="btn btn-success" onclick="submitInbound()" style="margin-left:8px;">确认入库</button>
            </div>
        </div>`;
    },

    /* ================== 4. 出库登记(多货) ================== */
    outbound(products) {
        const opts = products.map(p => {
            const low = p.quantity <= p.lowStock ? ' [低库存]' : '';
            return `<option value="${p.id}" data-stock="${p.quantity}">${escapeHtml(p.sku)} - ${escapeHtml(p.name)} (库存 ${p.quantity}${escapeHtml(p.unit||'')})${low}</option>`;
        }).join('');
        const blankRow = () => `
            <tr class="ob-row">
                <td class="num row-idx">1</td>
                <td>
                    <select class="select ob-product" style="width:100%;">${opts || '<option value="">暂无商品</option>'}</select>
                </td>
                <td><input type="number" min="1" class="input ob-qty" placeholder="数量"></td>
                <td><input class="input ob-row-remark" placeholder="行备注(可选)"></td>
                <td class="actions"><button class="btn-link danger" onclick="removeOutboundRow(this)">删除</button></td>
            </tr>`;
        return `
        <div class="page-head">
            <div>
                <h2>出库登记</h2>
                <div class="desc">一次性添加多个商品 → 库存预校验 → 确认出库 → 弹出出库单(可打印/导出 CSV)</div>
            </div>
            <div class="right">
                <button class="btn btn-sm" onclick="addOutboundRow()">+ 添加一行</button>
            </div>
        </div>
        <div class="card">
            <div class="form-row">
                <div class="form-field" style="flex:1;">
                    <label>领用方 / 客户</label>
                    <input id="ob-customer" class="input" placeholder="例如:行政部 / XX 销售单">
                </div>
                <div class="form-field" style="flex:1;">
                    <label>外部单号</label>
                    <input id="ob-external-no" class="input" placeholder="销售单号 / 领用单号(可选)">
                </div>
                <div class="form-field" style="flex:2;">
                    <label>整单备注</label>
                    <input id="ob-remark" class="input" placeholder="例如:9月各部门领用汇总">
                </div>
            </div>
        </div>
        <div class="card" style="margin-top:16px;">
            <div class="card-title">出库明细(同商品会自动合并数量,库存不足会整体拒绝)</div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th style="width:50px;">#</th>
                        <th>商品<span class="req">*</span></th>
                        <th style="width:140px;">数量<span class="req">*</span></th>
                        <th>行备注</th>
                        <th style="width:80px;">操作</th>
                    </tr></thead>
                    <tbody id="ob-rows">${blankRow() + blankRow() + blankRow()}</tbody>
                </table>
            </div>
            <div style="margin-top:12px;text-align:right;">
                <button class="btn btn-sm" onclick="addOutboundRow()">+ 添加一行</button>
                <button class="btn btn-warn" onclick="submitOutbound()" style="margin-left:8px;">确认出库</button>
            </div>
        </div>
        <div class="card" style="margin-top:16px;">
            <div class="card-title">当前库存 (低于预警值的已标红)</div>
            <div class="table-wrap">
                <table class="table">
                    <thead><tr>
                        <th>SKU</th><th>名称</th><th>分类</th>
                        <th class="num">库存</th><th class="num">预警</th>
                        <th class="num">库存金额</th>
                    </tr></thead>
                    <tbody>
                    ${products.length === 0
                        ? `<tr><td colspan="6" class="empty">暂无商品</td></tr>`
                        : products.map(p => {
                            const cls = p.quantity <= p.lowStock ? 'style="color:#e74c3c;font-weight:600;"' : '';
                            return `<tr>
                                <td><strong>${escapeHtml(p.sku)}</strong></td>
                                <td>${escapeHtml(p.name)}</td>
                                <td>${escapeHtml(p.category || '')}</td>
                                <td class="num" ${cls}>${p.quantity} ${escapeHtml(p.unit||'')}</td>
                                <td class="num">${p.lowStock}</td>
                                <td class="num">¥${formatMoney((p.unitPrice||0)*p.quantity)}</td>
                            </tr>`;
                        }).join('')}
                    </tbody>
                </table>
            </div>
        </div>`;
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
    productForm(p) {
        p = p || {};
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
            <div class="form-field" style="flex:1;">
                <label>备注</label>
                <input id="pf-remark" class="input" value="${escapeHtml(p.remark||'')}" placeholder="可选">
            </div>
        </div>
        <input type="hidden" id="pf-id" value="${p.id || ''}">`;
    }
};