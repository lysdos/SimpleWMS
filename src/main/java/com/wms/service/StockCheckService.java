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
    public StockCheck create(String scope, String keyword, String remark, String createdBy) {
        if (scope == null || scope.isBlank()) scope = StockCheck.SCOPE_ALL;
        if (!StockCheck.SCOPE_ALL.equals(scope) &&
            !StockCheck.SCOPE_SKU.equals(scope) &&
            !StockCheck.SCOPE_LOCATION.equals(scope)) {
            throw new IllegalArgumentException("scope 必须是 ALL / SKU / LOCATION");
        }
        if (createdBy == null || createdBy.isBlank()) createdBy = "操作员";

        StockCheck c = new StockCheck();
        c.setCheckNo(genCheckNo());
        c.setScope(scope);
        c.setScopeKeyword(keyword);
        c.setRemark(remark);
        c.setCreatedBy(createdBy);
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
            // 支持一次盘点多个 SKU:逗号/顿号/分号/空白 分隔
            List<String> skus = parseSkus(keyword);
            if (skus.isEmpty()) {
                throw new IllegalArgumentException("SKU 模式必须提供 SKU 关键字");
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(skus.size(), "?"));
            items = jdbc.query(
                    "SELECT * FROM inventory_item WHERE status = 'IN_STOCK' AND sku IN (" + placeholders + ") ORDER BY id",
                    (rs, n) -> {
                        InventoryItem i = new InventoryItem();
                        i.setId(rs.getInt("id"));
                        i.setCode(rs.getString("code"));
                        i.setSku(rs.getString("sku"));
                        i.setProductName(rs.getString("product_name"));
                        i.setLocationCode(rs.getString("location_code"));
                        i.setRemaining(rs.getInt("remaining"));
                        return i;
                    }, skus.toArray());
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
        recalcStats(checkId);
    }

    /**
     * 提交盘点 → 生成差异报告(status=COMMITTED),不再可改。
     * 允许带未盘行提交:未盘(实盘数为空)按 0 落库,即计入盘亏。
     * 注意:提交只固化差异记录,**不调整库存**(库存校正另行处理)。
     */
    @Transactional
    public StockCheck commit(int checkId) {
        StockCheck c = dao.findCheckById(checkId);
        if (c == null) throw new IllegalArgumentException("盘点单不存在, id=" + checkId);
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
        StockCheck c = dao.findCheckById(checkId);
        if (c == null) throw new IllegalArgumentException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 可删除明细,当前: " + c.getStatus());
        }
        int rows = dao.deleteItem(checkId, itemId);
        if (rows == 0) throw new IllegalArgumentException("明细不存在, id=" + itemId);
        recalcStats(checkId);
    }

    /** 统一重算盘点单统计:总数 / 已盘数 / 差异数(未盘行不计入已盘,也不计入差异) */
    private void recalcStats(int checkId) {
        List<StockCheckItem> all = dao.findItemsByCheckId(checkId);
        int counted = 0, diffCount = 0;
        for (StockCheckItem it : all) {
            if (it.getCountedQty() != null) {
                counted++;
                if (it.getCountedQty().intValue() != it.getSystemQty().intValue()) diffCount++;
            }
        }
        dao.updateStats(checkId, all.size(), counted, diffCount);
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
        // 1) 盘点单状态校验
        StockCheck c = dao.findCheckById(checkId);
        if (c == null) throw new IllegalArgumentException("盘点单不存在, id=" + checkId);
        if (!StockCheck.STATUS_DRAFT.equals(c.getStatus())) {
            throw new IllegalStateException("仅 DRAFT 状态可扫码,当前: " + c.getStatus());
        }

        // 2) 查 inventory_item by code
        InventoryItem inv = itemDao.findByCode(code);
        if (inv == null) throw new IllegalArgumentException("码不存在");

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

    /** 单次盘点允许的最大 SKU 数(防 IN 列表过长) */
    private static final int MAX_SKUS = 50;
    /** 原始输入字符上限,与 stock_check.scope_keyword VARCHAR(512) 对齐,防超列宽 */
    private static final int MAX_SKU_INPUT_CHARS = 500;

    /** 解析多 SKU 输入:逗号(中英)/顿号/分号/空白 分隔,去重去空,保序 */
    private static List<String> parseSkus(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        if (raw.length() > MAX_SKU_INPUT_CHARS) {
            throw new IllegalArgumentException(
                    "SKU 列表过长(最多 " + MAX_SKU_INPUT_CHARS + " 字符),请分批创建盘点单");
        }
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String s : raw.split("[,，、;；\\s]+")) {
            String v = s.trim();
            if (!v.isEmpty()) set.add(v);
        }
        if (set.size() > MAX_SKUS) {
            throw new IllegalArgumentException("一次最多盘点 " + MAX_SKUS + " 个 SKU,当前 " + set.size() + " 个");
        }
        return new ArrayList<>(set);
    }

    private static String genCheckNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rnd = (int) (Math.random() * 900) + 100;
        return "CHK-" + ts + "-" + rnd;
    }
}
