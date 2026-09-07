package com.wms.service;

import com.wms.controller.UnprocessableException;
import com.wms.dao.InventoryItemDao;
import com.wms.dao.StockCheckDao;
import com.wms.model.InventoryItem;
import com.wms.model.StockCheck;
import com.wms.model.StockCheckItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

    /**
     * 创建盘点单 — 立即把当前在库单元(IN_STOCK)按 scope 快照到 stock_check_item。
     */
    @Transactional
    public StockCheck create(String scope, String keyword, String remark, String operator) {
        if (scope == null || scope.isBlank()) scope = StockCheck.SCOPE_ALL;
        if (!StockCheck.SCOPE_ALL.equals(scope) &&
            !StockCheck.SCOPE_SKU.equals(scope) &&
            !StockCheck.SCOPE_LOCATION.equals(scope)) {
            throw new IllegalArgumentException("scope 必须是 ALL / SKU / LOCATION");
        }
        if (operator == null || operator.isBlank()) operator = "操作员";

        StockCheck c = new StockCheck();
        c.setCheckNo(genCheckNo());
        c.setScope(scope);
        c.setScopeKeyword(keyword);
        c.setRemark(remark);
        c.setOperator(operator);
        c.setStatus(StockCheck.STATUS_DRAFT);
        c.setTotalCount(0);
        c.setCountedCount(0);
        c.setDiffCount(0);

        // 查范围
        List<InventoryItem> items = new ArrayList<>();
        if (StockCheck.SCOPE_ALL.equals(scope)) {
            items = jdbc.query(
                    "SELECT * FROM inventory_item WHERE status = 'IN_STOCK' ORDER BY id",
                    (rs, n) -> {
                        InventoryItem i = new InventoryItem();
                        i.setId(rs.getInt("id"));
                        i.setCode(rs.getString("code"));
                        i.setSku(rs.getString("sku"));
                        i.setProductName(rs.getString("product_name"));
                        i.setLocationCode(rs.getString("location_code"));
                        i.setRemaining(rs.getInt("remaining"));
                        return i;
                    });
        } else if (StockCheck.SCOPE_SKU.equals(scope)) {
            if (keyword == null || keyword.isBlank()) {
                throw new IllegalArgumentException("SKU 模式必须提供 SKU 关键字");
            }
            items = jdbc.query(
                    "SELECT * FROM inventory_item WHERE status = 'IN_STOCK' AND sku = ? ORDER BY id",
                    (rs, n) -> {
                        InventoryItem i = new InventoryItem();
                        i.setId(rs.getInt("id"));
                        i.setCode(rs.getString("code"));
                        i.setSku(rs.getString("sku"));
                        i.setProductName(rs.getString("product_name"));
                        i.setLocationCode(rs.getString("location_code"));
                        i.setRemaining(rs.getInt("remaining"));
                        return i;
                    }, keyword.trim());
        } else {
            if (keyword == null || keyword.isBlank()) {
                throw new IllegalArgumentException("LOCATION 模式必须提供库位编码");
            }
            items = jdbc.query(
                    "SELECT * FROM inventory_item WHERE status = 'IN_STOCK' AND location_code = ? ORDER BY id",
                    (rs, n) -> {
                        InventoryItem i = new InventoryItem();
                        i.setId(rs.getInt("id"));
                        i.setCode(rs.getString("code"));
                        i.setSku(rs.getString("sku"));
                        i.setProductName(rs.getString("product_name"));
                        i.setLocationCode(rs.getString("location_code"));
                        i.setRemaining(rs.getInt("remaining"));
                        return i;
                    }, keyword.trim());
        }

        c.setTotalCount(items.size());
        int checkId = dao.insertCheck(c);
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
        StockCheck c = dao.findCheckById(checkId);
        if (c == null) throw new IllegalArgumentException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 状态可填实盘数,当前: " + c.getStatus());
        }
        // 找明细并校验属于该盘点单
        StockCheckItem target = dao.findItemsByCheckId(checkId).stream()
                .filter(it -> it.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("明细不存在, id=" + itemId));
        dao.updateItemCount(itemId, countedQty, remark);
        dao.setItemDiff(itemId, countedQty - target.getSystemQty());
        // 重算盘点单统计(自己事务内写的行,后续 SELECT 一定可见)
        List<StockCheckItem> all = dao.findItemsByCheckId(checkId);
        int counted = 0, diffCount = 0;
        for (StockCheckItem it : all) {
            if (it.getCountedQty() != null) {
                counted++;
                if (it.getCountedQty().intValue() != it.getSystemQty().intValue()) diffCount++;
            }
        }
        dao.updateStats(checkId, counted, diffCount);
    }

    /**
     * 提交盘点 → 生成差异报告(status=COMMITTED),不再可改。
     */
    @Transactional
    public StockCheck commit(int checkId) {
        StockCheck c = dao.findCheckById(checkId);
        if (c == null) throw new IllegalArgumentException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 可提交,当前: " + c.getStatus());
        }
        // 校验所有明细都已填实盘数
        List<StockCheckItem> items = dao.findItemsByCheckId(checkId);
        boolean allFilled = items.stream().allMatch(it -> it.getCountedQty() != null);
        if (!allFilled) {
            throw new IllegalStateException("还有未填的实盘数,无法提交");
        }
        int rows = dao.commit(checkId);
        if (rows == 0) throw new IllegalStateException("提交失败,状态已变更");
        // 提交后重新计算 diff 数(counted 不等于 system)
        int diffCount = 0;
        for (StockCheckItem it : items) {
            if (it.getCountedQty().intValue() != it.getSystemQty().intValue()) diffCount++;
        }
        dao.updateStats(checkId, items.size(), diffCount);
        return get(checkId);
    }

    /**
     * 取消盘点(DRAFT → CANCELLED),不删除明细,保留追溯。
     */
    @Transactional
    public void cancel(int checkId) {
        StockCheck c = dao.findCheckById(checkId);
        if (c == null) throw new IllegalArgumentException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 可取消");
        }
        dao.cancel(checkId);
    }

    /**
     * PDA 扫一个码 → 校验 + 累加该盘点单对应明细的 counted_qty。
     * 流程:
     *   1) 盘点单存在 + DRAFT
     *   2) inventory_item 存在 + IN_STOCK
     *   3) 在本盘点单明细中找到对应行(创建时已快照) → counted_qty += 1
     *   4) 返回更新后的 StockCheck(含 items)
     *
     * 错误码(由 GlobalExceptionHandler 映射):
     *   - 盘点单不存在 → 404
     *   - 盘点单已 COMMITTED/CANCELLED → 409
     *   - 码不存在 → 404
     *   - 码已 OUT/SCRAPPED/TRANSFERRED → 409
     *   - 码不在本盘点单明细中 → 422
     */
    @Transactional
    public StockCheck scanCodeToCheck(int checkId, String code) {
        // 1) 盘点单状态校验
        StockCheck c = dao.findCheckById(checkId);
        if (c == null) throw new IllegalArgumentException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 状态可扫码,当前: " + c.getStatus());
        }

        // 2) 查 inventory_item by code
        InventoryItem inv = itemDao.findByCode(code);
        if (inv == null) throw new IllegalArgumentException("码不存在");
        if (!InventoryItem.STATUS_IN_STOCK.equals(inv.getStatus())) {
            throw new IllegalStateException("该码状态不可盘点: " + inv.getStatus());
        }

        // 3) 在本盘点单明细中找对应行
        StockCheckItem line = dao.findItemsByCheckId(checkId).stream()
                .filter(it -> it.getInventoryItemId().equals(inv.getId()))
                .findFirst()
                .orElseThrow(() -> new UnprocessableException("该码不在本盘点单中,需先创建覆盖该范围的盘点单"));

        // 4) 累加 counted_qty(不覆盖 row_remark)
        int newQty = (line.getCountedQty() == null ? 0 : line.getCountedQty()) + 1;
        dao.updateCountedQty(line.getId(), newQty);

        // 5) 重算盘点单统计
        List<StockCheckItem> all = dao.findItemsByCheckId(checkId);
        int counted = 0, diffCount = 0;
        for (StockCheckItem it : all) {
            if (it.getCountedQty() != null) {
                counted++;
                if (it.getCountedQty().intValue() != it.getSystemQty().intValue()) diffCount++;
            }
        }
        dao.updateStats(checkId, counted, diffCount);

        return get(checkId);
    }

    private static String genCheckNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rnd = (int) (Math.random() * 900) + 100;
        return "CHK-" + ts + "-" + rnd;
    }
}
