/* ============================================
 *  i18n.js  -  纯前端国际化(中/英)
 *  使用:t('key') 读取当前语言的文案
 *       setLang('en') 切换语言并触发 applyI18n()
 *       applyI18n()   扫描 DOM 中所有 [data-i18n] 并替换
 *  策略:localStorage 记住用户选择(默认 zh-CN)
 * ============================================ */

const I18N = (() => {
    const STORAGE_KEY = 'wms.lang';
    const SUPPORTED = ['zh-CN', 'en-US'];

    /* 字典 —— 键 = 语义 key,值 = { 'zh-CN': '...', 'en-US': '...' }
       缺失任何一侧,会 fallback 到另一侧,再 fallback 到 key 本身 */
    const dict = {
        /* ---------- 通用 ---------- */
        'app.title':           { 'zh-CN': 'SimpleWMS - 简单仓库管理系统',  'en-US': 'SimpleWMS - Simple Warehouse Management' },
        'app.brand.subtitle':  { 'zh-CN': '简单仓库管理系统',              'en-US': 'Warehouse Management System' },
        'app.lang.zh':         { 'zh-CN': '中文',                          'en-US': '中文' },
        'app.lang.en':         { 'zh-CN': 'English',                       'en-US': 'English' },
        'app.lang.toggle':     { 'zh-CN': '语言',                          'en-US': 'Language' },
        'common.confirm':      { 'zh-CN': '确认',                          'en-US': 'Confirm' },
        'common.cancel':       { 'zh-CN': '取消',                          'en-US': 'Cancel' },
        'common.ok':           { 'zh-CN': '确定',                          'en-US': 'OK' },
        'common.close':        { 'zh-CN': '关闭',                          'en-US': 'Close' },
        'common.submit':       { 'zh-CN': '提交',                          'en-US': 'Submit' },
        'common.save':         { 'zh-CN': '保存',                          'en-US': 'Save' },
        'common.delete':       { 'zh-CN': '删除',                          'en-US': 'Delete' },
        'common.edit':         { 'zh-CN': '编辑',                          'en-US': 'Edit' },
        'common.create':       { 'zh-CN': '新增',                          'en-US': 'Create' },
        'common.create_new':   { 'zh-CN': '新增',                          'en-US': 'New' },
        'common.search':       { 'zh-CN': '搜索',                          'en-US': 'Search' },
        'common.query':        { 'zh-CN': '查询',                          'en-US': 'Search' },
        'common.reset':        { 'zh-CN': '重置',                          'en-US': 'Reset' },
        'common.clear':        { 'zh-CN': '清空',                          'en-US': 'Clear' },
        'common.export':       { 'zh-CN': '导出',                          'en-US': 'Export' },
        'common.print':        { 'zh-CN': '打印',                          'en-US': 'Print' },
        'common.back':         { 'zh-CN': '返回',                          'en-US': 'Back' },
        'common.all':          { 'zh-CN': '全部',                          'en-US': 'All' },
        'common.all_status':   { 'zh-CN': '全部状态',                      'en-US': 'All Status' },
        'common.required':     { 'zh-CN': '必填',                          'en-US': 'required' },
        'common.optional':     { 'zh-CN': '可选',                          'en-US': 'optional' },
        'common.optional_paren':{ 'zh-CN': '(可选)',                        'en-US': '(optional)' },
        'common.remark':       { 'zh-CN': '备注',                          'en-US': 'Remark' },
        'common.status':       { 'zh-CN': '状态',                          'en-US': 'Status' },
        'common.operator':     { 'zh-CN': '操作员',                        'en-US': 'Operator' },
        'common.actions':      { 'zh-CN': '操作',                          'en-US': 'Actions' },
        'common.view':         { 'zh-CN': '查看',                          'en-US': 'View' },
        'common.confirm_delete':{ 'zh-CN': '确认删除',                       'en-US': 'Confirm Delete' },
        'common.delete_irreversible':{ 'zh-CN': '该操作不可恢复。',           'en-US': 'This action cannot be undone.' },
        'common.total':        { 'zh-CN': '共',                            'en-US': 'Total' },
        'common.page':         { 'zh-CN': '第',                            'en-US': 'Page' },
        'common.prev':         { 'zh-CN': '上一页',                        'en-US': 'Previous' },
        'common.next':         { 'zh-CN': '下一页',                        'en-US': 'Next' },
        'common.items':        { 'zh-CN': '条',                            'en-US': '' },
        'common.empty_data':   { 'zh-CN': '暂无数据',                      'en-US': 'No data' },
        'common.loading':      { 'zh-CN': '加载中...',                     'en-US': 'Loading...' },
        'common.success.create':{ 'zh-CN': '新增成功',                      'en-US': 'Created successfully' },
        'common.success.save': { 'zh-CN': '保存成功',                      'en-US': 'Saved successfully' },
        'common.success.delete':{ 'zh-CN': '已删除',                        'en-US': 'Deleted' },
        'common.success.commit':{ 'zh-CN': '已提交',                        'en-US': 'Committed' },
        'common.success.cancel':{ 'zh-CN': '已取消',                        'en-US': 'Cancelled' },

        /* ---------- 登录 / 改密 ---------- */
        'login.title':         { 'zh-CN': '登录 - SimpleWMS',             'en-US': 'Login - SimpleWMS' },
        'login.username':      { 'zh-CN': '用户名',                        'en-US': 'Username' },
        'login.password':      { 'zh-CN': '密码',                          'en-US': 'Password' },
        'login.submit':        { 'zh-CN': '登录',                          'en-US': 'Sign In' },
        'login.error.bad_creds':{ 'zh-CN': '用户名或密码错误',              'en-US': 'Invalid username or password' },
        'login.error.disabled':{ 'zh-CN': '账号已禁用,请联系管理员',        'en-US': 'Account disabled, contact admin' },
        'login.error.generic': { 'zh-CN': '登录失败',                      'en-US': 'Login failed' },
        'login.lang.toggle':   { 'zh-CN': '切换语言',                      'en-US': 'Switch language' },
        'cp.title':            { 'zh-CN': '修改密码 - SimpleWMS',         'en-US': 'Change Password - SimpleWMS' },
        'cp.subtitle':         { 'zh-CN': '首次登录请修改密码',            'en-US': 'Please change your password on first login' },
        'cp.old':              { 'zh-CN': '原密码',                        'en-US': 'Old Password' },
        'cp.new':              { 'zh-CN': '新密码(至少 6 位)',             'en-US': 'New Password (min 6 chars)' },
        'cp.new2':             { 'zh-CN': '确认新密码',                    'en-US': 'Confirm New Password' },
        'cp.submit':           { 'zh-CN': '保存',                          'en-US': 'Save' },
        'cp.error.mismatch':   { 'zh-CN': '两次输入不一致',                'en-US': 'Passwords do not match' },
        'cp.error.generic':    { 'zh-CN': '修改失败',                      'en-US': 'Failed to change' },

        /* ---------- 顶部导航 ---------- */
        'nav.dashboard':       { 'zh-CN': '仪表盘',                        'en-US': 'Dashboard' },
        'nav.inbound':         { 'zh-CN': '入库登记',                      'en-US': 'Inbound' },
        'nav.outbound':        { 'zh-CN': '出库登记',                      'en-US': 'Outbound' },
        'nav.orders':          { 'zh-CN': '出入库单',                      'en-US': 'Orders' },
        'nav.inventory':       { 'zh-CN': '库存管理',                      'en-US': 'Inventory' },
        'nav.stock_checks':    { 'zh-CN': '库存盘点',                      'en-US': 'Stock Checks' },
        'nav.basic':           { 'zh-CN': '基础管理',                      'en-US': 'Basic Data' },
        'nav.products':        { 'zh-CN': '商品管理',                      'en-US': 'Products' },
        'nav.suppliers':       { 'zh-CN': '供应商管理',                    'en-US': 'Suppliers' },
        'nav.locations':       { 'zh-CN': '库位管理',                      'en-US': 'Locations' },
        'nav.users':           { 'zh-CN': '用户管理',                      'en-US': 'Users' },
        'nav.logout':          { 'zh-CN': '登出',                          'en-US': 'Logout' },

        /* ---------- 仪表盘 ---------- */
        'dashboard.title':     { 'zh-CN': '仪表盘',                        'en-US': 'Dashboard' },
        'dashboard.desc':      { 'zh-CN': '实时展示仓库概览信息',          'en-US': 'Real-time warehouse overview' },
        'dashboard.product_count':{ 'zh-CN': '商品种类',                    'en-US': 'Products' },
        'dashboard.total_qty': { 'zh-CN': '库存总量',                      'en-US': 'Total Quantity' },
        'dashboard.total_value':{ 'zh-CN': '库存总值 (元)',                 'en-US': 'Total Value (CNY)' },
        'dashboard.low_stock': { 'zh-CN': '低库存预警',                    'en-US': 'Low Stock Alert' },
        'dashboard.inv_trend.title':{ 'zh-CN': '库存趋势(累计在库单元 / 最近 30 天)',  'en-US': 'Inventory Trend (cumulative units / last 30 days)' },
        'dashboard.out_trend.title':{ 'zh-CN': '出库趋势(每日出库件数 / 最近 30 天)',  'en-US': 'Outbound Trend (daily qty / last 30 days)' },
        'chart.peak':          { 'zh-CN': '峰值',                          'en-US': 'Peak' },
        'chart.total':         { 'zh-CN': '累计',                          'en-US': 'Total' },
        'chart.days':          { 'zh-CN': '天',                            'en-US': 'days' },

        /* ---------- 商品管理 ---------- */
        'product.title':       { 'zh-CN': '商品管理',                      'en-US': 'Products' },
        'product.desc':        { 'zh-CN': '维护商品基础信息(SKU、名称、分类、单位、默认供应商、库位、数量、单价)', 'en-US': 'Manage product master data (SKU, name, category, unit, default supplier, location, qty, price)' },
        'product.create':      { 'zh-CN': '+ 新增商品',                    'en-US': '+ New Product' },
        'product.kw_placeholder':{ 'zh-CN': '搜索 SKU / 名称 / 分类',       'en-US': 'Search SKU / Name / Category' },
        'product.col.sku':     { 'zh-CN': 'SKU',                           'en-US': 'SKU' },
        'product.col.name':    { 'zh-CN': '名称',                          'en-US': 'Name' },
        'product.col.category':{ 'zh-CN': '分类',                          'en-US': 'Category' },
        'product.col.unit':    { 'zh-CN': '单位',                          'en-US': 'Unit' },
        'product.col.default_supplier':{ 'zh-CN': '默认供应商',              'en-US': 'Default Supplier' },
        'product.col.qty':     { 'zh-CN': '数量',                          'en-US': 'Qty' },
        'product.col.price':   { 'zh-CN': '单价',                          'en-US': 'Unit Price' },
        'product.col.value':   { 'zh-CN': '库存金额',                      'en-US': 'Stock Value' },
        'product.empty':       { 'zh-CN': '暂无商品,点击右上角"新增商品"开始', 'en-US': 'No products yet. Click "+ New Product" to start' },
        'product.low_badge':   { 'zh-CN': '低库存',                        'en-US': 'Low' },
        'product.empty_supplier':{ 'zh-CN': '未设',                         'en-US': '—' },
        'product.form.title.new':{ 'zh-CN': '新增商品',                     'en-US': 'New Product' },
        'product.form.title.edit':{ 'zh-CN': '编辑商品',                    'en-US': 'Edit Product' },
        'product.form.sku':    { 'zh-CN': 'SKU',                           'en-US': 'SKU' },
        'product.form.sku_placeholder':{ 'zh-CN': '例如 SP-001',             'en-US': 'e.g. SP-001' },
        'product.form.name':   { 'zh-CN': '名称',                          'en-US': 'Name' },
        'product.form.cat':    { 'zh-CN': '分类',                          'en-US': 'Category' },
        'product.form.cat_placeholder':{ 'zh-CN': '例如 办公耗材',            'en-US': 'e.g. Office Supplies' },
        'product.form.unit':   { 'zh-CN': '单位',                          'en-US': 'Unit' },
        'product.form.unit_placeholder':{ 'zh-CN': '例如 个/箱/包',          'en-US': 'e.g. pcs/box/pkg' },
        'product.form.loc':    { 'zh-CN': '库位',                          'en-US': 'Location' },
        'product.form.qty':    { 'zh-CN': '初始库存',                      'en-US': 'Initial Qty' },
        'product.form.price':  { 'zh-CN': '单价 (元)',                     'en-US': 'Unit Price (CNY)' },
        'product.form.low':    { 'zh-CN': '低库存预警',                    'en-US': 'Low Stock Threshold' },
        'product.form.suppliers':{ 'zh-CN': '供应商(可多选)',              'en-US': 'Suppliers (multi-select)' },
        'product.form.default_supplier':{ 'zh-CN': '默认供应商',            'en-US': 'Default Supplier' },
        'product.form.empty_loc':{ 'zh-CN': '暂无库位,请先到「基础管理 → 库位管理」添加', 'en-US': 'No locations. Go to Basic Data → Locations first' },
        'product.form.empty_supplier':{ 'zh-CN': '暂无可选供应商,请先到「基础管理 → 供应商管理」添加', 'en-US': 'No suppliers. Go to Basic Data → Suppliers first' },
        'product.error.sku_required':{ 'zh-CN': 'SKU 不能为空',            'en-US': 'SKU is required' },
        'product.error.name_required':{ 'zh-CN': '名称不能为空',            'en-US': 'Name is required' },
        'product.error.loc_required':{ 'zh-CN': '请选择库位',               'en-US': 'Please select a location' },

        /* ---------- 入库登记 ---------- */
        'inbound.title':       { 'zh-CN': '入库登记',                      'en-US': 'Inbound' },
        'inbound.desc':        { 'zh-CN': '点「新增入库单」开一张单 → 选商品(自动预填默认供应商)→ 确认入库', 'en-US': 'Click "New Inbound" to create → pick product (default supplier auto-filled) → commit' },
        'inbound.create':      { 'zh-CN': '+ 新增入库单',                  'en-US': '+ New Inbound' },
        'inbound.recent_title':{ 'zh-CN': '最近入库单(最近 10 条)',        'en-US': 'Recent Inbound (last 10)' },
        'inbound.empty':       { 'zh-CN': '暂无入库单,点上方「新增入库单」开始', 'en-US': 'No inbound orders yet. Click "New Inbound" above to start' },
        'inbound.col.order_no':{ 'zh-CN': '单号',                          'en-US': 'Order No.' },
        'inbound.col.status':  { 'zh-CN': '状态',                          'en-US': 'Status' },
        'inbound.col.external_no':{ 'zh-CN': '外部单号',                   'en-US': 'External No.' },
        'inbound.col.items':   { 'zh-CN': '明细 / 数量',                   'en-US': 'Items / Qty' },
        'inbound.col.op_time': { 'zh-CN': '操作员 · 时间',                 'en-US': 'Operator · Time' },
        'inbound.modal.title.new':{ 'zh-CN': '新增入库单',                  'en-US': 'New Inbound' },
        'inbound.modal.title.detail':{ 'zh-CN': '入库单',                    'en-US': 'Inbound Order' },
        'inbound.items':       { 'zh-CN': '入库明细',                      'en-US': 'Inbound Items' },
        'inbound.tip':         { 'zh-CN': '提示:每个"数量"=将生成等量的独立 24 位编码 / 条形码', 'en-US': 'Tip: each "Qty" generates independent 24-digit codes / barcodes' },
        'inbound.confirm_commit':{ 'zh-CN': '确认提交入库单 {n} ?\n提交后将生成所有 24 位编码,无法再修改。', 'en-US': 'Commit inbound order {n}?\nAll 24-digit codes will be generated and cannot be modified.' },
        'inbound.confirm_cancel':{ 'zh-CN': '确认取消入库单 {n} ?\n将回滚库存单元与商品数量,该操作不可撤销。', 'en-US': 'Cancel inbound order {n}?\nInventory units and product quantities will be rolled back. This cannot be undone.' },
        'inbound.toast.committed':{ 'zh-CN': '入库单 {n} 已提交',          'en-US': 'Inbound order {n} committed' },
        'inbound.toast.cancelled':{ 'zh-CN': '入库单 {n} 已取消',          'en-US': 'Inbound order {n} cancelled' },

        /* ---------- 出库登记 ---------- */
        'outbound.title':      { 'zh-CN': '出库登记',                      'en-US': 'Outbound' },
        'outbound.desc':       { 'zh-CN': '点「新增出库单」开一张单 → 选商品(库存预校验)→ 确认出库', 'en-US': 'Click "New Outbound" to create → pick product (stock pre-check) → commit' },
        'outbound.create':     { 'zh-CN': '+ 新增出库单',                  'en-US': '+ New Outbound' },
        'outbound.recent_title':{ 'zh-CN': '最近出库单(最近 10 条)',       'en-US': 'Recent Outbound (last 10)' },
        'outbound.empty':      { 'zh-CN': '暂无出库单,点上方「新增出库单」开始', 'en-US': 'No outbound orders yet. Click "New Outbound" above to start' },
        'outbound.col.order_no':{ 'zh-CN': '单号',                          'en-US': 'Order No.' },
        'outbound.col.status': { 'zh-CN': '状态',                          'en-US': 'Status' },
        'outbound.col.customer':{ 'zh-CN': '领用方',                        'en-US': 'Customer' },
        'outbound.col.external_no':{ 'zh-CN': '外部单号',                   'en-US': 'External No.' },
        'outbound.col.items':  { 'zh-CN': '明细 / 数量',                   'en-US': 'Items / Qty' },
        'outbound.col.op_time':{ 'zh-CN': '操作员 · 时间',                 'en-US': 'Operator · Time' },
        'outbound.modal.title.new':{ 'zh-CN': '新增出库单',                 'en-US': 'New Outbound' },
        'outbound.modal.title.detail':{ 'zh-CN': '出库单',                  'en-US': 'Outbound Order' },
        'outbound.items':      { 'zh-CN': '出库明细',                      'en-US': 'Outbound Items' },
        'outbound.tip':        { 'zh-CN': '提示:每个"数量"=将整件挑选库存单元扣减', 'en-US': 'Tip: each "Qty" picks whole inventory units to deduct' },
        'outbound.confirm_commit':{ 'zh-CN': '确认提交出库单 {n} ?\n提交后将扣减库存,无法再修改。', 'en-US': 'Commit outbound order {n}?\nStock will be deducted and cannot be modified.' },
        'outbound.confirm_cancel':{ 'zh-CN': '确认取消出库单 {n} ?\n该操作不可撤销。', 'en-US': 'Cancel outbound order {n}?\nThis cannot be undone.' },
        'outbound.toast.committed':{ 'zh-CN': '出库单 {n} 已提交',          'en-US': 'Outbound order {n} committed' },
        'outbound.toast.cancelled':{ 'zh-CN': '出库单 {n} 已取消',          'en-US': 'Outbound order {n} cancelled' },
        'common.commit':       { 'zh-CN': '提交',                            'en-US': 'Commit' },
        'common.cancel':       { 'zh-CN': '取消',                            'en-US': 'Cancel' },

        /* ---------- 出入库单(列表) ---------- */
        'orders.title':        { 'zh-CN': '出入库单',                      'en-US': 'Orders' },
        'orders.desc':         { 'zh-CN': '历史入库单/出库单查询,可按日期/单号/客户/供应商/操作员筛选', 'en-US': 'Historical inbound/outbound orders, filter by date / order no / customer / supplier / operator' },
        'orders.type.in':      { 'zh-CN': '入库单',                        'en-US': 'Inbound' },
        'orders.type.out':     { 'zh-CN': '出库单',                        'en-US': 'Outbound' },
        'orders.badge.in':     { 'zh-CN': '入库',                          'en-US': 'IN' },
        'orders.badge.out':    { 'zh-CN': '出库',                          'en-US': 'OUT' },
        'orders.col.id':       { 'zh-CN': 'ID',                            'en-US': 'ID' },
        'orders.col.type':     { 'zh-CN': '类型',                          'en-US': 'Type' },
        'orders.col.order_no': { 'zh-CN': '单号',                          'en-US': 'Order No.' },
        'orders.col.partner':  { 'zh-CN': '供应商/客户',                   'en-US': 'Supplier/Customer' },
        'orders.col.external_no':{ 'zh-CN': '外部单号',                     'en-US': 'External No.' },
        'orders.col.item_count':{ 'zh-CN': '明细数',                        'en-US': 'Items' },
        'orders.col.total_qty':{ 'zh-CN': '总数量',                        'en-US': 'Total Qty' },
        'orders.col.operator': { 'zh-CN': '操作员',                        'en-US': 'Operator' },
        'orders.col.created_at':{ 'zh-CN': '创建时间',                      'en-US': 'Created At' },
        'orders.kw_placeholder':{ 'zh-CN': '单号 / 供应商 / 客户 / 外部单号 / 备注', 'en-US': 'Order No. / Supplier / Customer / External No. / Remark' },
        'orders.op_placeholder':{ 'zh-CN': '操作员(精确)',                  'en-US': 'Operator (exact)' },
        'orders.begin_label':  { 'zh-CN': '起',                            'en-US': 'From' },
        'orders.end_label':    { 'zh-CN': '止',                            'en-US': 'To' },
        'orders.partner_supplier':{ 'zh-CN': '供应商',                      'en-US': 'Supplier' },
        'orders.partner_customer':{ 'zh-CN': '领用方',                      'en-US': 'Customer' },
        'orders.empty':        { 'zh-CN': '暂无单据',                      'en-US': 'No orders' },

        /* ---------- 入库单详情 ---------- */
        'inbound_order.title': { 'zh-CN': '入 库 单',                      'en-US': 'INBOUND ORDER' },
        'inbound_order.label.order_no':{ 'zh-CN': '单号',                    'en-US': 'Order No.' },
        'inbound_order.label.created_at':{ 'zh-CN': '创建时间',              'en-US': 'Created At' },
        'inbound_order.label.operator':{ 'zh-CN': '操作员',                  'en-US': 'Operator' },
        'inbound_order.label.external_no':{ 'zh-CN': '外部单号',             'en-US': 'External No.' },
        'inbound_order.label.remark':{ 'zh-CN': '整单备注',                  'en-US': 'Remark' },
        'inbound_order.label.qty':{ 'zh-CN': '入库数量',                    'en-US': 'Inbound Qty' },
        'inbound_order.label.unit_price':{ 'zh-CN': '单价',                  'en-US': 'Unit Price' },
        'inbound_order.col.idx':{ 'zh-CN': '#',                            'en-US': '#' },
        'inbound_order.col.supplier':{ 'zh-CN': '供应商',                   'en-US': 'Supplier' },
        'inbound_order.col.sku':{ 'zh-CN': 'SKU',                          'en-US': 'SKU' },
        'inbound_order.col.product':{ 'zh-CN': '商品',                      'en-US': 'Product' },
        'inbound_order.col.loc':{ 'zh-CN': '库位',                         'en-US': 'Location' },
        'inbound_order.col.qty':{ 'zh-CN': '入库数量',                      'en-US': 'Inbound Qty' },
        'inbound_order.col.price':{ 'zh-CN': '单价',                        'en-US': 'Unit Price' },
        'inbound_order.col.row_remark':{ 'zh-CN': '行备注',                  'en-US': 'Row Remark' },
        'inbound_order.total': { 'zh-CN': '合计',                          'en-US': 'Total' },
        'inbound_order.empty': { 'zh-CN': '无明细',                        'en-US': 'No items' },
        'inbound_order.sign1': { 'zh-CN': '仓管签字',                      'en-US': 'Warehouse Keeper' },
        'inbound_order.sign2': { 'zh-CN': '验收人',                        'en-US': 'Inspector' },
        'inbound_order.print_time':{ 'zh-CN': '打印时间',                   'en-US': 'Print Time' },
        'inbound_order.btn.print_all':{ 'zh-CN': '📦 打印全部条码',         'en-US': '📦 Print All Barcodes' },
        'inbound_order.btn.commit':{ 'zh-CN': '✓ 提交入库',                 'en-US': '✓ Commit Inbound' },
        'inbound_order.btn.cancel':{ 'zh-CN': '✗ 取消',                     'en-US': '✗ Cancel' },
        'inbound_order.barcode.count':{ 'zh-CN': '📦 本行 {n} 个 24 位编码', 'en-US': '📦 {n} 24-digit codes in this row' },
        'inbound_order.no_barcode':{ 'zh-CN': '(无条码记录)',               'en-US': '(no barcode record)' },
        'inbound_order.barcode.sku':{ 'zh-CN': 'SKU',                       'en-US': 'SKU' },
        'inbound_order.barcode.count_total':{ 'zh-CN': '共',                 'en-US': 'Total' },
        'inbound_order.barcode.unit_codes':{ 'zh-CN': '个独立 24 位编码',    'en-US': 'individual 24-digit codes' },
        'inbound_order.print_title':{ 'zh-CN': '入库单 #{id} 条码标签',     'en-US': 'Inbound Order #{id} - Barcode Labels' },
        'inbound_order.print_subtitle':{ 'zh-CN': '条码标签',                'en-US': 'Barcode Labels' },
        'inbound_order.print_printed_at':{ 'zh-CN': '打印时间',              'en-US': 'Printed At' },

        /* ---------- 出库单详情 ---------- */
        'outbound_order.title':{ 'zh-CN': '出 库 单',                      'en-US': 'OUTBOUND ORDER' },
        'outbound_order.label.order_no':{ 'zh-CN': '单号',                   'en-US': 'Order No.' },
        'outbound_order.label.created_at':{ 'zh-CN': '创建时间',             'en-US': 'Created At' },
        'outbound_order.label.operator':{ 'zh-CN': '操作员',                 'en-US': 'Operator' },
        'outbound_order.label.customer':{ 'zh-CN': '领用方',                 'en-US': 'Customer' },
        'outbound_order.label.external_no':{ 'zh-CN': '外部单号',            'en-US': 'External No.' },
        'outbound_order.label.remark':{ 'zh-CN': '整单备注',                'en-US': 'Remark' },
        'outbound_order.label.qty':{ 'zh-CN': '出库数量',                   'en-US': 'Outbound Qty' },
        'outbound_order.col.idx':{ 'zh-CN': '#',                           'en-US': '#' },
        'outbound_order.col.sku':{ 'zh-CN': 'SKU',                         'en-US': 'SKU' },
        'outbound_order.col.product':{ 'zh-CN': '商品',                     'en-US': 'Product' },
        'outbound_order.col.loc':{ 'zh-CN': '库位',                        'en-US': 'Location' },
        'outbound_order.col.qty':{ 'zh-CN': '出库数量',                    'en-US': 'Outbound Qty' },
        'outbound_order.col.price':{ 'zh-CN': '单价',                       'en-US': 'Unit Price' },
        'outbound_order.col.row_remark':{ 'zh-CN': '行备注',                 'en-US': 'Row Remark' },
        'outbound_order.total':{ 'zh-CN': '合计',                          'en-US': 'Total' },
        'outbound_order.empty':{ 'zh-CN': '无明细',                        'en-US': 'No items' },
        'outbound_order.sign1':{ 'zh-CN': '发货人',                        'en-US': 'Shipper' },
        'outbound_order.sign2':{ 'zh-CN': '领用人',                        'en-US': 'Receiver' },
        'outbound_order.print_time':{ 'zh-CN': '打印时间',                  'en-US': 'Print Time' },
        'outbound_order.barcode.count':{ 'zh-CN': '📦 本行 {n} 个 24 位编码','en-US': '📦 {n} 24-digit codes in this row' },
        'outbound_order.no_barcode':{ 'zh-CN': '(无条码记录)',              'en-US': '(no barcode record)' },
        'outbound_order.print_title':{ 'zh-CN': '出库单 #{id} 条码标签(已消耗)','en-US': 'Outbound Order #{id} - Barcode Labels (consumed)' },
        'outbound_order.barcode_count':{ 'zh-CN': '共 {n} 个 24 位编码',     'en-US': 'Total {n} 24-digit codes' },
        'outbound_order.btn.commit':{ 'zh-CN': '✓ 提交出库',                 'en-US': '✓ Commit Outbound' },
        'outbound_order.btn.cancel':{ 'zh-CN': '✗ 取消',                     'en-US': '✗ Cancel' },
        'outbound_order.barcode_label':{ 'zh-CN': '24 位编码',               'en-US': '24-digit Code' },
        'outbound_order.strike':{ 'zh-CN': '[已出库]',                     'en-US': '[Consumed]' },

        /* ---------- 新增单据(共用) ---------- */
        'new_order.label.product':{ 'zh-CN': '商品',                       'en-US': 'Product' },
        'new_order.label.supplier':{ 'zh-CN': '供应商',                     'en-US': 'Supplier' },
        'new_order.label.qty':  { 'zh-CN': '数量',                          'en-US': 'Qty' },
        'new_order.label.row_remark':{ 'zh-CN': '行备注',                   'en-US': 'Row Remark' },
        'new_order.label.external_no':{ 'zh-CN': '外部单号',                'en-US': 'External No.' },
        'new_order.label.customer':{ 'zh-CN': '领用方 / 客户',              'en-US': 'Customer' },
        'new_order.label.remark':{ 'zh-CN': '整单备注',                     'en-US': 'Remark' },
        'new_order.ph.external_no_in':{ 'zh-CN': '采购单号 / 快递单号(可选)','en-US': 'PO / Tracking No. (optional)' },
        'new_order.ph.external_no_out':{ 'zh-CN': '销售单号 / 领用单号(可选)','en-US': 'SO / Issue No. (optional)' },
        'new_order.ph.customer':{ 'zh-CN': '例如:行政部 / XX 销售单',       'en-US': 'e.g. Admin Dept. / SO-001' },
        'new_order.ph.qty':     { 'zh-CN': '数量',                          'en-US': 'Qty' },
        'new_order.ph.row_remark':{ 'zh-CN': '行备注(可选)',                 'en-US': 'Row remark (optional)' },
        'new_order.ph.remark':  { 'zh-CN': '可选',                          'en-US': 'optional' },
        'new_order.placeholder.no_product':{ 'zh-CN': '暂无商品',           'en-US': 'No products' },
        'new_order.placeholder.no_supplier':{ 'zh-CN': '暂无供应商',         'en-US': 'No suppliers' },
        'new_order.placeholder.not_set':{ 'zh-CN': '(不填)',                 'en-US': '(empty)' },
        'new_order.btn.add_row':{ 'zh-CN': '+ 添加条目',                    'en-US': '+ Add Row' },
        'new_order.merge_tip_in':{ 'zh-CN': '(同 商品+供应商 会自动合并 · 每件商品独立 24 位编码)', 'en-US': '(same product+supplier auto-merge · each item gets a 24-digit code)' },
        'new_order.merge_tip_out':{ 'zh-CN': '(同 商品 会自动合并 · 整件出库)', 'en-US': '(same product auto-merge · whole-unit outbound)' },
        'new_order.err.no_product':{ 'zh-CN': '存在未选择商品的行',         'en-US': 'A row is missing a product' },
        'new_order.err.qty_invalid':{ 'zh-CN': '数量必须大于 0',            'en-US': 'Qty must be > 0' },
        'new_order.err.no_items':{ 'zh-CN': '请至少填写一条明细',           'en-US': 'At least one row is required' },
        'new_order.err.product_missing':{ 'zh-CN': '商品不存在',            'en-US': 'Product not found' },
        'new_order.err.stock_low':{ 'zh-CN': '库存不足',                    'en-US': 'Insufficient stock' },
        'new_order.toast.in_done':{ 'zh-CN': '入库成功',                    'en-US': 'Inbound saved' },
        'new_order.toast.out_done':{ 'zh-CN': '出库成功',                   'en-US': 'Outbound saved' },
        'new_order.toast.count_suffix':{ 'zh-CN': '共 {a} 行 / {b} 件',     'en-US': '{a} rows / {b} units' },

        /* ---------- 供应商管理 ---------- */
        'supplier.title':      { 'zh-CN': '供应商管理',                    'en-US': 'Suppliers' },
        'supplier.desc':       { 'zh-CN': '维护供应商主数据,商品管理中可绑定多个供应商并指定默认', 'en-US': 'Manage supplier master data; bind multiple suppliers to a product and pick a default' },
        'supplier.create':     { 'zh-CN': '+ 新增供应商',                  'en-US': '+ New Supplier' },
        'supplier.kw_placeholder':{ 'zh-CN': '搜索 名称 / 联系人 / 电话',   'en-US': 'Search Name / Contact / Phone' },
        'supplier.col.name':   { 'zh-CN': '名称',                          'en-US': 'Name' },
        'supplier.col.contact':{ 'zh-CN': '联系人',                        'en-US': 'Contact' },
        'supplier.col.phone':  { 'zh-CN': '电话',                          'en-US': 'Phone' },
        'supplier.col.email':  { 'zh-CN': '邮箱',                          'en-US': 'Email' },
        'supplier.col.status': { 'zh-CN': '状态',                          'en-US': 'Status' },
        'supplier.empty':      { 'zh-CN': '暂无供应商,点击右上角「新增」开始', 'en-US': 'No suppliers yet. Click "+ New" to start' },
        'supplier.form.new':   { 'zh-CN': '新增供应商',                    'en-US': 'New Supplier' },
        'supplier.form.edit':  { 'zh-CN': '编辑供应商',                    'en-US': 'Edit Supplier' },
        'supplier.form.name':  { 'zh-CN': '名称',                          'en-US': 'Name' },
        'supplier.form.name_placeholder':{ 'zh-CN': '供应商名称',           'en-US': 'Supplier name' },
        'supplier.form.contact':{ 'zh-CN': '联系人',                        'en-US': 'Contact' },
        'supplier.form.contact_placeholder':{ 'zh-CN': '王经理',            'en-US': 'Mr. Wang' },
        'supplier.form.phone': { 'zh-CN': '电话',                          'en-US': 'Phone' },
        'supplier.form.phone_placeholder':{ 'zh-CN': '021-xxxxxxxx',        'en-US': '021-xxxxxxxx' },
        'supplier.form.email': { 'zh-CN': '邮箱',                          'en-US': 'Email' },
        'supplier.form.email_placeholder':{ 'zh-CN': 'sales@example.com',   'en-US': 'sales@example.com' },
        'supplier.form.address':{ 'zh-CN': '地址',                         'en-US': 'Address' },
        'supplier.form.enabled':{ 'zh-CN': '状态',                         'en-US': 'Status' },
        'supplier.form.remark':{ 'zh-CN': '备注',                          'en-US': 'Remark' },
        'supplier.status.enabled':{ 'zh-CN': '启用',                        'en-US': 'Enabled' },
        'supplier.status.disabled':{ 'zh-CN': '停用',                       'en-US': 'Disabled' },
        'supplier.error.name_required':{ 'zh-CN': '名称不能为空',           'en-US': 'Name is required' },
        'supplier.toast.created':{ 'zh-CN': '已新增',                      'en-US': 'Created' },

        /* ---------- 库位管理 ---------- */
        'location.title':      { 'zh-CN': '库位管理',                      'en-US': 'Locations' },
        'location.desc':       { 'zh-CN': '维护仓库库位字典,商品管理中库位字段从此处选择', 'en-US': 'Manage warehouse locations; product locations are picked from this list' },
        'location.create':     { 'zh-CN': '+ 新增库位',                    'en-US': '+ New Location' },
        'location.kw_placeholder':{ 'zh-CN': '搜索 编码 / 名称',           'en-US': 'Search Code / Name' },
        'location.col.code':   { 'zh-CN': '编码',                          'en-US': 'Code' },
        'location.col.name':   { 'zh-CN': '名称',                          'en-US': 'Name' },
        'location.col.remark': { 'zh-CN': '备注',                          'en-US': 'Remark' },
        'location.col.status': { 'zh-CN': '状态',                          'en-US': 'Status' },
        'location.empty':      { 'zh-CN': '暂无库位,点击右上角「新增」开始', 'en-US': 'No locations yet. Click "+ New" to start' },
        'location.form.new':   { 'zh-CN': '新增库位',                      'en-US': 'New Location' },
        'location.form.edit':  { 'zh-CN': '编辑库位',                      'en-US': 'Edit Location' },
        'location.form.code':  { 'zh-CN': '编码',                          'en-US': 'Code' },
        'location.form.code_placeholder':{ 'zh-CN': '例如 A-01-03',         'en-US': 'e.g. A-01-03' },
        'location.form.name':  { 'zh-CN': '名称',                          'en-US': 'Name' },
        'location.form.name_placeholder':{ 'zh-CN': '例如 A 区 01 排 03 位', 'en-US': 'e.g. Zone A, Row 01, Slot 03' },
        'location.status.enabled':{ 'zh-CN': '启用',                        'en-US': 'Enabled' },
        'location.status.disabled':{ 'zh-CN': '停用',                       'en-US': 'Disabled' },
        'location.del_tip':    { 'zh-CN': '注意:商品的库位字段仍会保留文本,但下拉里不再出现。', 'en-US': 'Note: existing products keep the text, but the dropdown will no longer show it.' },
        'location.disabled_marker':{ 'zh-CN': '(已停用或已删除)',           'en-US': '(disabled or deleted)' },
        'location.empty_marker':{ 'zh-CN': '(不选)',                        'en-US': '(none)' },
        'location.error.code_required':{ 'zh-CN': '库位编码不能为空',       'en-US': 'Code is required' },

        /* ---------- 库存管理 ---------- */
        'inventory.title':     { 'zh-CN': '库存管理',                      'en-US': 'Inventory' },
        'inventory.desc':      { 'zh-CN': '每个入库批次生成独立 24 位编码,支持条码打印、报废、调拨', 'en-US': 'Each inbound batch gets independent 24-digit codes. Print barcode, scrap, or transfer.' },
        'inventory.kw_placeholder':{ 'zh-CN': '搜索 SKU(模糊)',             'en-US': 'Search SKU (fuzzy)' },
        'inventory.loc_all':   { 'zh-CN': '全部库位',                      'en-US': 'All Locations' },
        'inventory.col.code':  { 'zh-CN': '24 位编码',                     'en-US': '24-digit Code' },
        'inventory.col.sku':   { 'zh-CN': 'SKU',                           'en-US': 'SKU' },
        'inventory.col.product':{ 'zh-CN': '商品',                         'en-US': 'Product' },
        'inventory.col.loc':   { 'zh-CN': '库位',                          'en-US': 'Location' },
        'inventory.col.qty':   { 'zh-CN': '原始数量',                      'en-US': 'Original Qty' },
        'inventory.col.remaining':{ 'zh-CN': '剩余',                       'en-US': 'Remaining' },
        'inventory.col.status':{ 'zh-CN': '状态',                          'en-US': 'Status' },
        'inventory.col.created_by':{ 'zh-CN': '创建人',                    'en-US': 'Created By' },
        'inventory.col.created_at':{ 'zh-CN': '入库时间',                  'en-US': 'Inbound Time' },
        'inventory.col.actions':{ 'zh-CN': '操作',                         'en-US': 'Actions' },
        'inventory.empty':     { 'zh-CN': '暂无库存单元,完成一次入库后自动生成 24 位编码', 'en-US': 'No inventory units. Generate codes by completing an inbound order.' },
        'inventory.btn.barcode':{ 'zh-CN': '条码',                          'en-US': 'Barcode' },
        'inventory.btn.transfer':{ 'zh-CN': '调拨',                        'en-US': 'Transfer' },
        'inventory.btn.scrap': { 'zh-CN': '报废',                          'en-US': 'Scrap' },
        'inventory.modal.barcode_title':{ 'zh-CN': '条码 / 24 位编码',      'en-US': 'Barcode / 24-digit Code' },
        'inventory.modal.barcode_tip':{ 'zh-CN': '长按图片可保存 / 分享到打印机', 'en-US': 'Long-press the image to save or send to printer' },
        'inventory.modal.scrap_title':{ 'zh-CN': '报废库存单元',            'en-US': 'Scrap Inventory Unit' },
        'inventory.modal.scrap_tip':{ 'zh-CN': '将报废以下库存单元,该操作不可恢复。', 'en-US': 'The following unit will be scrapped. This cannot be undone.' },
        'inventory.modal.scrap_reason':{ 'zh-CN': '报废原因(可选)',         'en-US': 'Reason (optional)' },
        'inventory.modal.scrap_placeholder':{ 'zh-CN': '例如:过期 / 损坏 / 测试报废 ...', 'en-US': 'e.g. expired / damaged / test scrap...' },
        'inventory.modal.scrap_done':{ 'zh-CN': '已报废',                  'en-US': 'Scrapped' },
        'inventory.modal.transfer_title':{ 'zh-CN': '调拨库存单元',         'en-US': 'Transfer Inventory Unit' },
        'inventory.modal.transfer_tip':{ 'zh-CN': '将以下库存单元从「{from}」调拨到新库位。', 'en-US': 'Transfer the following unit from "{from}" to a new location.' },
        'inventory.modal.transfer_target':{ 'zh-CN': '目标库位',            'en-US': 'Target Location' },
        'inventory.modal.transfer_empty':{ 'zh-CN': '暂无可用库位,请先到「基础管理 → 库位管理」添加', 'en-US': 'No available locations. Go to Basic Data → Locations first' },
        'inventory.modal.transfer_pick':{ 'zh-CN': '请选择目标库位',         'en-US': 'Please select a target location' },
        'inventory.modal.transfer_done':{ 'zh-CN': '已调拨',                'en-US': 'Transferred' },
        'inventory.status.IN_STOCK':{ 'zh-CN': '在库',                     'en-US': 'In Stock' },
        'inventory.status.OUT':     { 'zh-CN': '已出库',                   'en-US': 'Out' },
        'inventory.status.SCRAPPED':{ 'zh-CN': '已报废',                   'en-US': 'Scrapped' },
        'inventory.status.TRANSFERRED':{ 'zh-CN': '已调拨',                'en-US': 'Transferred' },

        /* ---------- 库存盘点 ---------- */
        'stock_check.title':   { 'zh-CN': '库存盘点',                      'en-US': 'Stock Checks' },
        'stock_check.desc':    { 'zh-CN': '按范围(全部/SKU/库位)创建盘点单,填入实盘数后提交生成差异报告', 'en-US': 'Create stock checks by scope (all/SKU/location), enter counted qty, commit for variance report' },
        'stock_check.create':  { 'zh-CN': '+ 新建盘点',                    'en-US': '+ New Check' },
        'stock_check.modal.title.new':{ 'zh-CN': '新建盘点单',              'en-US': 'New Stock Check' },
        'stock_check.modal.title.detail':{ 'zh-CN': '盘点单',               'en-US': 'Stock Check' },
        'stock_check.kw_placeholder':{ 'zh-CN': 'ALL 模式留空',             'en-US': 'Leave empty for ALL' },
        'stock_check.scope.all':{ 'zh-CN': '全部在库单元',                 'en-US': 'All in-stock units' },
        'stock_check.scope.sku':{ 'zh-CN': '按 SKU',                       'en-US': 'By SKU' },
        'stock_check.scope.location':{ 'zh-CN': '按库位',                  'en-US': 'By Location' },
        'stock_check.scope.keyword':{ 'zh-CN': '关键字',                   'en-US': 'Keyword' },
        'stock_check.scope.sku_label':{ 'zh-CN': 'SKU',                    'en-US': 'SKU' },
        'stock_check.scope.location_label':{ 'zh-CN': '库位编码',            'en-US': 'Location Code' },
        'stock_check.remark_label':{ 'zh-CN': '备注',                      'en-US': 'Remark' },
        'stock_check.remark_placeholder':{ 'zh-CN': '例如:月末大盘点',       'en-US': 'e.g. month-end count' },
        'stock_check.tip':     { 'zh-CN': '创建后将立即快照当前在库单元 → 进入详情填入实盘数 → 提交生成差异报告', 'en-US': 'After creating, the current in-stock units are snapshotted → enter counted qty in detail → commit for variance report' },
        'stock_check.err.no_keyword':{ 'zh-CN': '非「全部」模式必须输入关键字', 'en-US': 'Keyword is required when scope is not ALL' },
        'stock_check.toast.created':{ 'zh-CN': '盘点单 {n} 已创建,共 {m} 条明细','en-US': 'Stock check {n} created, {m} items' },
        'stock_check.col.check_no':{ 'zh-CN': '盘点单号',                   'en-US': 'Check No.' },
        'stock_check.col.scope':{ 'zh-CN': '范围',                         'en-US': 'Scope' },
        'stock_check.col.status':{ 'zh-CN': '状态',                        'en-US': 'Status' },
        'stock_check.col.total':{ 'zh-CN': '明细数',                       'en-US': 'Items' },
        'stock_check.col.counted':{ 'zh-CN': '已盘数',                      'en-US': 'Counted' },
        'stock_check.col.diff':{ 'zh-CN': '差异数',                        'en-US': 'Diff' },
        'stock_check.col.created_at':{ 'zh-CN': '创建时间',                 'en-US': 'Created At' },
        'stock_check.empty':   { 'zh-CN': '暂无盘点单,点击「新建盘点」开始', 'en-US': 'No stock checks yet. Click "+ New Check" to start' },
        'stock_check.detail.summary':{ 'zh-CN': '盘点单号',                 'en-US': 'Check No.' },
        'stock_check.detail.scope':{ 'zh-CN': '范围',                       'en-US': 'Scope' },
        'stock_check.detail.total':{ 'zh-CN': '明细/已盘/差异',              'en-US': 'Items/Counted/Diff' },
        'stock_check.detail.created_at':{ 'zh-CN': '创建时间',               'en-US': 'Created At' },
        'stock_check.detail.tip_draft':{ 'zh-CN': '草稿:填入实盘数后点每行的「保存」,全部填完后回到此弹窗点底部「提交盘点」','en-US': 'Draft: enter counted qty and click Save on each row. When all rows are filled, click "Commit" at the bottom of this dialog.' },
        'stock_check.detail.tip_committed':{ 'zh-CN': '已提交:报告只读,可导出 CSV', 'en-US': 'Committed: report is read-only, exportable as CSV' },
        'stock_check.col.code':{ 'zh-CN': '24 位编码',                      'en-US': '24-digit Code' },
        'stock_check.col.sku':{ 'zh-CN': 'SKU',                            'en-US': 'SKU' },
        'stock_check.col.product':{ 'zh-CN': '商品',                        'en-US': 'Product' },
        'stock_check.col.loc':{ 'zh-CN': '库位',                           'en-US': 'Location' },
        'stock_check.col.system_qty':{ 'zh-CN': '系统数',                   'en-US': 'System Qty' },
        'stock_check.col.counted_qty':{ 'zh-CN': '实盘数',                  'en-US': 'Counted Qty' },
        'stock_check.col.diff':{ 'zh-CN': '差异',                          'en-US': 'Diff' },
        'stock_check.col.row_remark':{ 'zh-CN': '行备注',                   'en-US': 'Row Remark' },
        'stock_check.col.actions':{ 'zh-CN': '操作',                        'en-US': 'Actions' },
        'stock_check.detail.empty':{ 'zh-CN': '无明细',                     'en-US': 'No items' },
        'stock_check.btn.save':{ 'zh-CN': '保存',                          'en-US': 'Save' },
        'stock_check.btn.commit':{ 'zh-CN': '提交盘点',                    'en-US': 'Commit Check' },
        'stock_check.btn.cancel':{ 'zh-CN': '取消盘点',                    'en-US': 'Cancel Check' },
        'stock_check.btn.saved':{ 'zh-CN': '已保存',                        'en-US': 'Saved' },
        'stock_check.btn.committed_diff':{ 'zh-CN': '已提交,差异 {n} 条',   'en-US': 'Committed, {n} variances' },
        'stock_check.err.uncounted':{ 'zh-CN': '还有 {n} 条未填实盘数',     'en-US': '{n} rows still need counted qty' },
        'stock_check.err.qty_invalid':{ 'zh-CN': '实盘数必须 ≥ 0',          'en-US': 'Counted qty must be ≥ 0' },

        /* ---------- 单据状态 ---------- */
        'doc.DRAFT':           { 'zh-CN': '草稿',                          'en-US': 'Draft' },
        'doc.COMMITTED':       { 'zh-CN': '已提交',                        'en-US': 'Committed' },
        'doc.CANCELLED':       { 'zh-CN': '已取消',                        'en-US': 'Cancelled' },

        /* ---------- 用户管理 ---------- */
        'user.title':          { 'zh-CN': '用户管理',                      'en-US': 'Users' },
        'user.desc':           { 'zh-CN': '维护系统账号与角色。删除用户不会删除其业务记录(库存流水 / 出入库单保留原操作员字段)', 'en-US': 'Manage accounts and roles. Deleting a user keeps their business records (operator field preserved on stock logs / orders).' },
        'user.create':         { 'zh-CN': '+ 新建用户',                    'en-US': '+ New User' },
        'user.col.username':   { 'zh-CN': '用户名',                        'en-US': 'Username' },
        'user.col.role':       { 'zh-CN': '角色',                          'en-US': 'Role' },
        'user.col.enabled':    { 'zh-CN': '启用',                          'en-US': 'Enabled' },
        'user.col.must_change':{ 'zh-CN': '待改密',                        'en-US': 'Must Change PW' },
        'user.col.actions':    { 'zh-CN': '操作',                          'en-US': 'Actions' },
        'user.badge.me':       { 'zh-CN': '我',                            'en-US': 'Me' },
        'user.btn.reset_pw':   { 'zh-CN': '重置密码',                      'en-US': 'Reset Password' },
        'user.btn.disable':    { 'zh-CN': '禁用',                          'en-US': 'Disable' },
        'user.btn.enable':     { 'zh-CN': '启用',                          'en-US': 'Enable' },
        'user.btn.delete':     { 'zh-CN': '删除',                          'en-US': 'Delete' },
        'user.cant_delete_self':{ 'zh-CN': '不能删除当前登录的用户',        'en-US': 'You cannot delete yourself' },
        'user.modal.new':      { 'zh-CN': '新建用户',                      'en-US': 'New User' },
        'user.form.username':  { 'zh-CN': '用户名',                        'en-US': 'Username' },
        'user.form.password':  { 'zh-CN': '初始密码',                      'en-US': 'Initial Password' },
        'user.form.role':      { 'zh-CN': '角色',                          'en-US': 'Role' },
        'user.role.OPERATOR':  { 'zh-CN': '操作员',                        'en-US': 'Operator' },
        'user.role.ADMIN':     { 'zh-CN': '管理员',                        'en-US': 'Admin' },
        'user.toast.created':  { 'zh-CN': '创建成功',                      'en-US': 'Created' },
        'user.toast.reset_done':{ 'zh-CN': '已重置',                       'en-US': 'Reset' },
        'user.prompt.reset_pw':{ 'zh-CN': '为「{n}」设置新密码(至少 6 位):', 'en-US': 'Set new password for "{n}" (min 6 chars):' },
        'user.confirm.del':   { 'zh-CN': '确认删除用户「{n}」?\n\n该用户的库存流水 / 出入库单等业务记录将保留(operator 字段保留原用户名)。', 'en-US': 'Delete user "{n}"?\n\nTheir stock logs and orders are kept (operator field preserves the original username).' },
        'user.toast.deleted': { 'zh-CN': '已删除',                        'en-US': 'Deleted' },
        'user.toast.enabled':  { 'zh-CN': '已启用',                        'en-US': 'Enabled' },
        'user.toast.disabled': { 'zh-CN': '已禁用',                        'en-US': 'Disabled' },

        /* ---------- 单据 提交/取消 confirm ---------- */
        'confirm.ok':          { 'zh-CN': '确认',                          'en-US': 'Confirm' },

        /* ---------- Common error/info ---------- */
        'err.network':         { 'zh-CN': '请求失败',                      'en-US': 'Request failed' },
        'err.not_found':       { 'zh-CN': '未找到单据',                    'en-US': 'Order not found' },
        'err.empty':           { 'zh-CN': '单据数据为空',                  'en-US': 'Empty order data' },
        'err.no_inventory_for_line':{ 'zh-CN': '该行商品未找到对应的库存单元','en-US': 'No inventory unit found for this line' },
        'err.no_inventory_for_out_line':{ 'zh-CN': '该行商品未找到对应的库存单元(可能为老数据)', 'en-US': 'No inventory unit found for this line (legacy data?)' },
        'err.no_inventory_inbound':{ 'zh-CN': '该入库单暂无库存单元',      'en-US': 'No inventory units for this inbound order' },
        'err.no_inventory_outbound':{ 'zh-CN': '该出库单暂无消耗的库存单元','en-US': 'No consumed inventory units for this outbound order' },

        /* ---------- 图表 ---------- */
        'barcode.status':      { 'zh-CN': '状态',                          'en-US': 'Status' }
    };

    /* ---------- 状态 ---------- */
    let currentLang = 'zh-CN';

    /* ---------- 工具 ---------- */
    function getStored() {
        try {
            const v = localStorage.getItem(STORAGE_KEY);
            if (v && SUPPORTED.includes(v)) return v;
        } catch (_) {}
        return null;
    }
    function detectBrowser() {
        const navLang = (navigator.language || 'zh-CN').toLowerCase();
        if (navLang.startsWith('en')) return 'en-US';
        return 'zh-CN';
    }

    /* ---------- 公共 API ---------- */
    function t(key, params) {
        const entry = dict[key];
        let str;
        if (entry) {
            str = entry[currentLang] || entry['zh-CN'] || key;
        } else {
            str = key;  // fallback:开发期方便发现漏翻
            if (typeof console !== 'undefined' && !t._warned) {
                console.warn('[i18n] missing key:', key);
            }
        }
        if (params && typeof str === 'string') {
            return str.replace(/\{(\w+)\}/g, (m, k) => (params[k] != null ? params[k] : m));
        }
        return str;
    }

    function setLang(lang, opts) {
        if (!SUPPORTED.includes(lang)) lang = 'zh-CN';
        currentLang = lang;
        try { localStorage.setItem(STORAGE_KEY, lang); } catch (_) {}
        document.documentElement.setAttribute('lang', lang);
        // 1. 替换静态 [data-i18n] 元素
        applyI18n();
        // 2. 重新渲染当前 view(JS 动态部分)
        if (!opts || !opts.skipReload) {
            const r = (location.hash || '#/dashboard').replace('#/', '');
            if (typeof routes === 'object' && routes && routes[r]) {
                try { routes[r](); } catch (e) { console.warn('[i18n] reload route failed', e); }
            }
        }
        // 3. 触发自定义事件,登录页等可监听
        window.dispatchEvent(new CustomEvent('i18n:change', { detail: { lang } }));
    }

    function getLang() { return currentLang; }

    function applyI18n(root) {
        root = root || document;
        root.querySelectorAll('[data-i18n]').forEach(el => {
            const k = el.getAttribute('data-i18n');
            el.textContent = t(k);
        });
        root.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
            const k = el.getAttribute('data-i18n-placeholder');
            el.setAttribute('placeholder', t(k));
        });
        root.querySelectorAll('[data-i18n-title]').forEach(el => {
            const k = el.getAttribute('data-i18n-title');
            el.setAttribute('title', t(k));
        });
        root.querySelectorAll('[data-i18n-html]').forEach(el => {
            const k = el.getAttribute('data-i18n-html');
            el.innerHTML = t(k);
        });
    }

    /* ---------- 启动 ---------- */
    function init() {
        currentLang = getStored() || detectBrowser();
        document.documentElement.setAttribute('lang', currentLang);
    }

    init();

    return { t, setLang, getLang, applyI18n, SUPPORTED };
})();

/* 兼容旧代码里可能直接引用 t() 的情况(views.js/app.js 大量调用 t(...)) */
function t(key, params) { return I18N.t(key, params); }
