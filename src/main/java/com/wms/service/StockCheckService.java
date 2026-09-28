package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.controller.UnprocessableException;
import com.wms.dao.InventoryItemDao;
import com.wms.dao.StockCheckDao;
import com.wms.model.InventoryItem;
import com.wms.model.StockCheck;
import com.wms.model.StockCheckItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockCheckService {

    private final StockCheckDao dao;
    private final InventoryItemDao itemDao;
    private final JdbcTemplate jdbc;

    @Autowired
    public StockCheckService(StockCheckDao dao, InventoryItemDao itemDao, JdbcTemplate jdbc) {
        this.dao = dao;
        this.itemDao = itemDao;
        this.jdbc = jdbc;
    }

    /** 盘点快照只取这六列(其余字段用不上);几个 scope 分支共用一个映射。 */
    private static final RowMapper<InventoryItem> SNAPSHOT_ROW = (rs, n) -> {
        InventoryItem i = new InventoryItem();
        i.setId(rs.getInt("id"));
        i.setCode(rs.getString("code"));
        i.setSku(rs.getString("sku"));
        i.setProductName(rs.getString("product_name"));
        i.setLocationCode(rs.getString("location_code"));
        i.setRemaining(rs.getInt("remaining"));
        return i;
    };

    /** 仓库名(scope_keyword 存人读的范围描述,要能读出仓库名)。查不到就退回 "仓库#id"。 */
    private String warehouseName(Integer warehouseId) {
        List<String> names = jdbc.query("SELECT name FROM warehouse WHERE id = ?",
                (rs, n) -> rs.getString(1), warehouseId);
        return (names.isEmpty() || names.get(0) == null) ? ("仓库#" + warehouseId) : names.get(0);
    }

    /**
     * 创建盘点单 — 立即把当前在库单元(IN_STOCK)按 scope 快照到 stock_check_item。
     *
     * @param keyword     SKU 模式=多个 SKU;LOCATION 模式=单个库位码;
     *                    WAREHOUSE 模式=可选的库位码列表(逗号分隔,空=整个仓库)
     * @param warehouseId WAREHOUSE 模式必填
     */
    @Transactional
    public StockCheck create(String scope, String keyword, Integer warehouseId, String remark, String createdBy) {
        if (scope == null || scope.isBlank()) scope = StockCheck.SCOPE_ALL;
        if (!StockCheck.SCOPE_ALL.equals(scope) &&
            !StockCheck.SCOPE_SKU.equals(scope) &&
            !StockCheck.SCOPE_LOCATION.equals(scope) &&
            !StockCheck.SCOPE_WAREHOUSE.equals(scope)) {
            throw new IllegalArgumentException("scope 必须是 ALL / SKU / LOCATION / WAREHOUSE");
        }
        if (createdBy == null || createdBy.isBlank()) createdBy = "操作员";

        StockCheck c = new StockCheck();
        c.setCheckNo(genCheckNo());
        c.setScope(scope);
        c.setRemark(remark);
        c.setCreatedBy(createdBy);
        c.setStatus(StockCheck.STATUS_DRAFT);
        c.setTotalCount(0);
        c.setCountedCount(0);
        c.setDiffCount(0);

        // 查范围
        List<InventoryItem> items;
        if (StockCheck.SCOPE_ALL.equals(scope)) {
            c.setScopeKeyword(keyword);
            items = jdbc.query(
                    "SELECT * FROM inventory_item WHERE status = 'IN_STOCK' ORDER BY id",
                    SNAPSHOT_ROW);
        } else if (StockCheck.SCOPE_SKU.equals(scope)) {
            // 支持一次盘点多个 SKU:逗号/顿号/分号/空白 分隔
            List<String> skus = parseList(keyword);
            if (skus.isEmpty()) {
                throw new IllegalArgumentException("SKU 模式必须提供 SKU 关键字");
            }
            c.setScopeKeyword(keyword);
            String placeholders = String.join(",", java.util.Collections.nCopies(skus.size(), "?"));
            items = jdbc.query(
                    "SELECT * FROM inventory_item WHERE status = 'IN_STOCK' AND sku IN (" + placeholders + ") ORDER BY id",
                    SNAPSHOT_ROW, skus.toArray());
        } else if (StockCheck.SCOPE_LOCATION.equals(scope)) {
            if (keyword == null || keyword.isBlank()) {
                throw new IllegalArgumentException("LOCATION 模式必须提供库位编码");
            }
            c.setScopeKeyword(keyword);
            items = jdbc.query(
                    "SELECT * FROM inventory_item WHERE status = 'IN_STOCK' AND location_code = ? ORDER BY id",
                    SNAPSHOT_ROW, keyword.trim());
        } else {
            // WAREHOUSE:盘该仓库下的库位。keyword 里给了库位码就只盘这几个,空 = 整个仓库。
            // 库位集从 location 表取(仓库归属只记在 location 上),所以已删/未挂仓库的库位里的货
            // 自然落在范围外 —— 与 LOCATION 模式"只看该库位"的语义一致。
            if (warehouseId == null) {
                throw new IllegalArgumentException("WAREHOUSE 模式必须提供仓库");
            }
            // 仓库必须真实存在且启用 —— 以前只判了非空,于是 warehouseId=999 会
            // 建出一张名为「仓库#999」、件数为 0 的"看起来正常"的空盘点单
            List<Integer> whEnabled = jdbc.queryForList(
                    "SELECT enabled FROM warehouse WHERE id = ?", Integer.class, warehouseId);
            if (whEnabled.isEmpty()) {
                throw new NotFoundException("仓库不存在, warehouseId=" + warehouseId);
            }
            if (Integer.valueOf(0).equals(whEnabled.get(0))) {
                throw new IllegalArgumentException("仓库已停用,不能发起盘点: " + warehouseName(warehouseId));
            }
            // 记下范围所属仓库:扫码时据此挡住来自其他仓库的码
            c.setWarehouseId(warehouseId);
            List<String> locs = parseList(keyword);
            StringBuilder sql = new StringBuilder(
                    "SELECT * FROM inventory_item WHERE status = 'IN_STOCK' AND location_code IN ("
                    + "SELECT l.code FROM location l WHERE l.warehouse_id = ?");
            List<Object> args = new ArrayList<>();
            args.add(warehouseId);
            if (!locs.isEmpty()) {
                sql.append(" AND l.code IN (")
                   .append(String.join(",", java.util.Collections.nCopies(locs.size(), "?")))
                   .append(")");
                args.addAll(locs);
            }
            sql.append(") ORDER BY id");
            items = jdbc.query(sql.toString(), SNAPSHOT_ROW, args.toArray());
            // scope_keyword 存**人读的范围描述** —— 详情/列表直接把这一格打印出来,不解析。
            // 整仓就只写仓库名;勾了库位就把库位码带上。仓库名在后端查,省得 Web 和 PDA 各拼一套。
            String whName = warehouseName(warehouseId);
            c.setScopeKeyword(locs.isEmpty() ? whName : whName + " / " + String.join(" / ", locs));
        }

        c.setTotalCount(items.size());
        // 四个分支都会写 scope_keyword,统一在这里卡列宽 ——
        // WAREHOUSE 存的是重新拼出来的「人读范围描述」,比输入串长
        //(分隔符 1 字符变 3 字符,前面还要加仓库名),输入侧的 500 字符闸门拦不住,
        // 超列宽会以数据库异常的形式炸在 insertCheck 上
        ensureScopeKeywordFits(c);
        int checkId;
        try {
            checkId = dao.insertCheck(c);
        } catch (DuplicateKeyException dup) {
            // 同一秒内两处同时建单,随机后缀撞上了 uk_stock_check_no:换一个号再来一次。
            // 不重试的话用户看到的是一句原始驱动报错(Duplicate entry ...)
            c.setCheckNo(genCheckNo());
            checkId = dao.insertCheck(c);
        }
        c.setId(checkId);

        // 写入明细
        List<StockCheckItem> itemEntities = new ArrayList<>();
        for (InventoryItem ii : items) {
            StockCheckItem it = new StockCheckItem();
            it.setCheckId(checkId);
            it.setInventoryItemId(ii.getId());
            it.setCode(ii.getCode());
            it.setSku(ii.getSku());
            it.setProductName(ii.getProductName());
            it.setLocationCode(ii.getLocationCode());
            it.setSystemQty(ii.getRemaining() == null ? 0 : ii.getRemaining());
            dao.insertItem(it);
            itemEntities.add(it);
        }
        c.setItems(itemEntities);
        return c;
    }

    @Transactional(readOnly = true)
    public StockCheck get(int id) {
        StockCheck c = dao.findCheckById(id);
        if (c != null) c.setItems(dao.findItemsByCheckId(id));
        return c;
    }

    @Transactional(readOnly = true)
    public List<StockCheck> list(String beginDate, String endDate, String status, int page, int pageSize) {
        return dao.findChecks(beginDate, endDate, status, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long count(String beginDate, String endDate, String status) {
        return dao.countChecks(beginDate, endDate, status);
    }

    /**
     * 填入实盘数(line item)。草稿状态可改。
     */
    @Transactional
    public void fillCount(int checkId, int itemId, int countedQty, String remark) {
        if (countedQty < 0) throw new IllegalArgumentException("实盘数不能小于 0");
        StockCheck c = dao.findCheckByIdForUpdate(checkId);
        if (c == null) throw new NotFoundException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 状态可填实盘数,当前: " + c.getStatus());
        }
        // 找明细并校验属于该盘点单
        StockCheckItem target = dao.findItemsByCheckId(checkId).stream()
                .filter(it -> it.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new NotFoundException("明细不存在, id=" + itemId));
        dao.updateItemCount(itemId, countedQty, remark);
        dao.setItemDiff(itemId, countedQty - target.getSystemQty());
        // 重算盘点单统计(自己事务内写的行,后续 SELECT 一定可见)
        recalcStats(checkId);
    }

    /**
     * 提交盘点 → 定格为只读(status=COMMITTED),不再可改。
     * (差异数据是**边盘边写**的,提交不做"生成":只是把未盘行补 0 并重算汇总。)
     * 允许带未盘行提交:未盘(实盘数为空)按 0 落库,即计入盘亏。
     * 注意:提交只固化差异记录,**不调整库存**(库存校正另行处理)。
     */
    @Transactional
    public StockCheck commit(int checkId) {
        StockCheck c = dao.findCheckByIdForUpdate(checkId);
        if (c == null) throw new NotFoundException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 可提交,当前: " + c.getStatus());
        }
        for (StockCheckItem it : dao.findItemsByCheckId(checkId)) {
            if (it.getCountedQty() == null) dao.updateCountedQty(it.getId(), 0);
        }
        int rows = dao.commit(checkId);
        if (rows == 0) throw new IllegalStateException("提交失败,状态已变更");
        recalcStats(checkId);
        return get(checkId);
    }

    /** 删除盘点明细行(仅 DRAFT);用于清理盘盈时自动追加的多余行 */
    @Transactional
    public void deleteItem(int checkId, int itemId) {
        StockCheck c = dao.findCheckByIdForUpdate(checkId);
        if (c == null) throw new NotFoundException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 可删除明细,当前: " + c.getStatus());
        }
        int rows = dao.deleteItem(checkId, itemId);
        if (rows == 0) throw new NotFoundException("明细不存在, id=" + itemId);
        recalcStats(checkId);
    }

    /**
     * 统一重算盘点单的统计:应盘 / 已盘 / 差异。
     *
     * 口径(未盘行不计入已盘、也不计入差异;盘盈行三样都不计)全部落在
     * {@link com.wms.dao.StockCheckDao#updateStats(int)} 那条原子 SQL 里 —— 这里**故意不把明细
     * 读进 Java 算**:并发扫码时"读-算-写回"会丢更新,详见那个方法的注释。
     */
    private void recalcStats(int checkId) {
        dao.updateStats(checkId);
    }

    /**
     * 取消盘点(DRAFT → CANCELLED),不删除明细,保留追溯。
     *
     * 加锁读 + 检查影响行数,与 commit 对齐:以前这里既不取行锁、也**丢掉** dao.cancel 的
     * 返回值 —— 恰好与一个并发提交错开时,UPDATE 的 WHERE status='DRAFT' 匹配 0 行,
     * 方法却正常返回,接口报"已取消"而单子实际是 COMMITTED。
     */
    @Transactional
    public void cancel(int checkId) {
        StockCheck c = dao.findCheckByIdForUpdate(checkId);
        if (c == null) throw new NotFoundException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 可取消");
        }
        if (dao.cancel(checkId) == 0) {
            throw new IllegalStateException("取消失败,状态已变更");
        }
    }

    /**
     * PDA 扫一个码 → 定位本盘点单对应明细并记为已盘。
     * 一库一码:每行对应一个实物单元(system_qty 恒为 1),因此**重复扫同一码是幂等的**,
     * 第二次扫直接提示"已盘过",不再累加(旧实现 +1 会让实盘数无限增长)。
     *
     * 盘盈处理(只记账,不调整库存/单元状态):
     *   - 在库但不在本单快照范围内的码 → 自动追加一行(应盘 0 / 实盘 1)
     *   - 非在库(OUT/SCRAPPED/TRANSFERRED)的码 → 同样追加一行记盘盈
     *
     * 错误码(由 GlobalExceptionHandler 映射):
     *   - 盘点单不存在 → 404;盘点单已 COMMITTED/CANCELLED → 409
     *   - 码不存在(inventory_item 无此码) → 404
     *   - 该码已盘过 → 422
     */
    @Transactional
    public StockCheck scanCodeToCheck(int checkId, String code) {
        // 1) 盘点单状态校验(**加锁读,且必须是本事务第一条读** —— 理由见 StockCheckDao.findCheckByIdForUpdate)
        StockCheck c = dao.findCheckByIdForUpdate(checkId);
        if (c == null) throw new NotFoundException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 状态可扫码,当前: " + c.getStatus());
        }

        // 2) 查 inventory_item by code
        InventoryItem inv = itemDao.findByCode(code);
        if (inv == null) throw new NotFoundException("码不存在");

        // 3) 在本盘点单明细中找对应行
        StockCheckItem line = dao.findItemsByCheckId(checkId).stream()
                .filter(it -> it.getInventoryItemId().equals(inv.getId()))
                .findFirst()
                .orElse(null);

        if (line != null) {
            // 4a) 已在快照中:只要已有实盘数(含手工填的 0=盘亏)就视为已盘,
            //     不再覆盖——避免物理扫描把人工登记的盘亏悄悄改成 1
            if (line.getCountedQty() != null) {
                throw new UnprocessableException("该码已盘过,无需重复扫描");
            }
            dao.updateCountedQty(line.getId(), 1);
        } else {
            // 4b) 不在快照中(单外码 / 非在库码):追加盘盈行(应盘 0 / 实盘 1)
            //
            // 按仓库盘点时先挡住来自其他仓库的码:否则误扫的货会直接变成这张单的
            // 盘盈,而那张真正管着它仓库的盘点单可能也把它记成自己的盘盈 ——
            // 同一个实物被两张单各算一次。盘点范围是"这仓的货",不是"随便扫什么"。
            if (c.getWarehouseId() != null) {
                List<Integer> unitWh = jdbc.queryForList(
                        "SELECT warehouse_id FROM location WHERE code = ?",
                        Integer.class, inv.getLocationCode());
                Integer whId = unitWh.isEmpty() ? null : unitWh.get(0);
                if (!c.getWarehouseId().equals(whId)) {
                    throw new UnprocessableException(
                            "该码不在本次盘点的仓库范围内(它在库位 " + inv.getLocationCode()
                                    + (whId == null ? ",未挂到任何仓库" : ",属于其他仓库") + "),请确认是否扫错");
                }
            }
            StockCheckItem it = new StockCheckItem();
            it.setCheckId(checkId);
            it.setInventoryItemId(inv.getId());
            it.setCode(inv.getCode());
            it.setSku(inv.getSku());
            it.setProductName(inv.getProductName());
            it.setLocationCode(inv.getLocationCode());
            it.setSystemQty(0);
            it.setCountedQty(1);
            try {
                dao.insertItem(it);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发/重复扫码同时插入同一单元:唯一键(check_id, inventory_item_id)冲突
                throw new UnprocessableException("该码已盘过,无需重复扫描");
            }
        }

        // 5) 重算盘点单统计
        recalcStats(checkId);
        return get(checkId);
    }

    /** 单次盘点允许的最大条目数(防 IN 列表过长);SKU 列表与 WAREHOUSE 模式的库位列表共用 */
    private static final int MAX_ITEMS = 50;
    /** 原始输入字符上限,与 stock_check.scope_keyword VARCHAR(512) 对齐,防超列宽 */
    private static final int MAX_KEYWORD_CHARS = 500;

    /**
     * 解析多值输入(SKU 列表 / WAREHOUSE 模式的库位码列表):
     * 逗号(中英)/顿号/分号/空白 分隔,去重去空,保序。
     */
    private static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        if (raw.length() > MAX_KEYWORD_CHARS) {
            throw new IllegalArgumentException(
                    "关键字过长(最多 " + MAX_KEYWORD_CHARS + " 字符),请分批创建盘点单");
        }
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String s : raw.split("[,，、;；\\s]+")) {
            String v = s.trim();
            if (!v.isEmpty()) set.add(v);
        }
        if (set.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("一次最多盘点 " + MAX_ITEMS + " 项,当前 " + set.size() + " 项");
        }
        return new ArrayList<>(set);
    }

    /** scope_keyword 的列宽(VARCHAR(512)),与 sql/mysql-schema.sql 对齐 */
    private static final int SCOPE_KEYWORD_MAX = 512;

    /** 存进库前的最后一道闸:超长就报 400,而不是让数据库抛 Data too long */
    private void ensureScopeKeywordFits(StockCheck c) {
        String k = c.getScopeKeyword();
        if (k != null && k.length() > SCOPE_KEYWORD_MAX) {
            throw new IllegalArgumentException("盘点范围描述过长(" + k.length() + " 字符,上限 "
                    + SCOPE_KEYWORD_MAX + ");请减少勾选的库位/SKU 数量后重试");
        }
    }

    private static String genCheckNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        // 4 位随机后缀:同秒建单的碰撞概率从 1/900 降到 1/9000;
        // 真撞上了 create 里还有一次换号重试兜底
        int rnd = (int) (Math.random() * 9000) + 1000;
        return "CHK-" + ts + "-" + rnd;
    }
}
