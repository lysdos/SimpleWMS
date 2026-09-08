package com.wms.dao;

import com.wms.model.InventoryItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class InventoryItemDao {

    private final JdbcTemplate jdbc;
    public InventoryItemDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<InventoryItem> ROW = (rs, n) -> {
        InventoryItem i = new InventoryItem();
        i.setId(rs.getInt("id"));
        i.setCode(rs.getString("code"));
        i.setProductId(rs.getInt("product_id"));
        i.setSku(rs.getString("sku"));
        i.setProductName(rs.getString("product_name"));
        i.setLocationCode(rs.getString("location_code"));
        i.setQuantity(rs.getInt("quantity"));
        i.setRemaining(rs.getInt("remaining"));
        i.setStatus(rs.getString("status"));
        int iid = rs.getInt("inbound_order_id");
        i.setInboundOrderId(rs.wasNull() ? null : iid);
        int iiid = rs.getInt("inbound_item_id");
        i.setInboundItemId(rs.wasNull() ? null : iiid);
        int oid = rs.getInt("outbound_order_id");
        i.setOutboundOrderId(rs.wasNull() ? null : oid);
        java.sql.Timestamp ts = rs.getTimestamp("scrapped_at");
        i.setScrappedAt(ts == null ? null : ts.toLocalDateTime());
        i.setScrappedRemark(rs.getString("scrapped_remark"));
        ts = rs.getTimestamp("transferred_at");
        i.setTransferredAt(ts == null ? null : ts.toLocalDateTime());
        i.setTransferredFrom(rs.getString("transferred_from"));
        i.setTransferredTo(rs.getString("transferred_to"));
        i.setCreatedBy(rs.getString("created_by"));
        ts = rs.getTimestamp("created_at");
        i.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        ts = rs.getTimestamp("updated_at");
        i.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return i;
    };

    public int insert(InventoryItem i) {
        String sql = "INSERT INTO inventory_item " +
                "(code, product_id, sku, product_name, location_code, quantity, remaining, status, " +
                "inbound_order_id, inbound_item_id, created_by) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, i.getCode());
            ps.setInt(2, i.getProductId());
            ps.setString(3, i.getSku());
            ps.setString(4, i.getProductName());
            ps.setString(5, i.getLocationCode());
            ps.setInt(6, i.getQuantity());
            ps.setInt(7, i.getRemaining() == null ? i.getQuantity() : i.getRemaining());
            ps.setString(8, i.getStatus() == null ? InventoryItem.STATUS_IN_STOCK : i.getStatus());
            if (i.getInboundOrderId() == null) ps.setNull(9, java.sql.Types.INTEGER); else ps.setInt(9, i.getInboundOrderId());
            if (i.getInboundItemId() == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, i.getInboundItemId());
            ps.setString(11, i.getCreatedBy());
            return ps;
        }, kh);
        Number k = kh.getKey();
        if (k == null) {
            Map<String, Object> keys = kh.getKeys();
            if (keys != null && keys.get("id") != null) k = (Number) keys.get("id");
        }
        if (k != null) i.setId(k.intValue());
        return k == null ? 0 : k.intValue();
    }

    public InventoryItem findById(int id) {
        List<InventoryItem> list = jdbc.query(
                "SELECT * FROM inventory_item WHERE id = ?", ROW, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public InventoryItem findByCode(String code) {
        List<InventoryItem> list = jdbc.query(
                "SELECT * FROM inventory_item WHERE code = ?", ROW, code);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 列出某入库单产生的所有库存单元 */
    public List<InventoryItem> findByInboundOrder(int orderId) {
        return jdbc.query(
                "SELECT * FROM inventory_item WHERE inbound_order_id = ? ORDER BY id ASC",
                ROW, orderId);
    }

    /** 列出某入库单明细行产生的所有库存单元(用于 getOrderWithCodes 精确分配码) */
    public List<InventoryItem> findByInboundItem(int inboundItemId) {
        return jdbc.query(
                "SELECT * FROM inventory_item WHERE inbound_item_id = ? ORDER BY id ASC",
                ROW, inboundItemId);
    }

    /**
     * commitDraft 时调用:把某明细行的所有 PENDING 库存单元升级为 IN_STOCK + remaining=1。
     * 这样库存才真正"生效"(加入 product.quantity 与库存总览)。
     */
    public int activateByInboundItem(int inboundItemId) {
        return jdbc.update(
                "UPDATE inventory_item " +
                "SET status = ?, remaining = quantity, updated_at = CURRENT_TIMESTAMP " +
                "WHERE inbound_item_id = ? AND status = ?",
                InventoryItem.STATUS_IN_STOCK, inboundItemId, InventoryItem.STATUS_PENDING);
    }

    /**
     * cancelDraft 时调用:删除某入库单所有 PENDING 占位行(DRAFT 期未生效,直接清理)。
     */
    public int deletePendingByInboundOrder(int orderId) {
        return jdbc.update(
                "DELETE FROM inventory_item WHERE inbound_order_id = ? AND status = ?",
                orderId, InventoryItem.STATUS_PENDING);
    }

    /** 把 inventory_item 的 inbound_item_id 字段回填(每件实物→它所属的明细行) */
    public int updateInboundItemId(int inboundItemId, int inventoryItemId) {
        // SQL 顺序:SET 新值, WHERE 列值
        return jdbc.update(
                "UPDATE inventory_item SET inbound_item_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                inboundItemId, inventoryItemId);
        // 注:参数顺序是 (新值, WHERE 列值) — 第一?是 SET inbound_item_id=?,第二?是 WHERE id=?
    }

    /** 列出某出库单消耗的所有库存单元(包含已 OUT 的) */
    public List<InventoryItem> findByOutboundOrder(int orderId) {
        return jdbc.query(
                "SELECT * FROM inventory_item WHERE outbound_order_id = ? ORDER BY id ASC",
                ROW, orderId);
    }

    /**
     * 列表查询(库存管理用)。
     * @param sku     SKU 模糊匹配(可空)
     * @param location 库位精确匹配(可空)
     * @param status  IN_STOCK / OUT / SCRAPPED / TRANSFERRED / 空(=全部)
     * @param page, pageSize
     */
    public List<InventoryItem> findItems(String sku, String location, String status,
                                          int page, int pageSize) {
        StringBuilder sb = new StringBuilder("SELECT * FROM inventory_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sku != null && !sku.isBlank()) {
            sb.append(" AND sku LIKE ?");
            args.add("%" + sku.trim() + "%");
        }
        if (location != null && !location.isBlank()) {
            sb.append(" AND location_code = ?");
            args.add(location.trim());
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sb.append(" AND status = ?");
            args.add(status);
        }
        sb.append(" ORDER BY id DESC");
        int lim = pageSize <= 0 || pageSize > 200 ? 20 : pageSize;
        int offset = page <= 0 ? 0 : (page - 1) * lim;
        sb.append(" LIMIT ").append(lim).append(" OFFSET ").append(offset);
        return jdbc.query(sb.toString(), ROW, args.toArray());
    }

    public long countItems(String sku, String location, String status) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM inventory_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sku != null && !sku.isBlank()) {
            sb.append(" AND sku LIKE ?");
            args.add("%" + sku.trim() + "%");
        }
        if (location != null && !location.isBlank()) {
            sb.append(" AND location_code = ?");
            args.add(location.trim());
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sb.append(" AND status = ?");
            args.add(status);
        }
        Long c = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return c == null ? 0L : c;
    }

    /**
     * 出库时按 FIFO 选同 SKU + 库位 + IN_STOCK 的库存单元,整件扣减。
     * 优先级:
     *   1) 优先选 remaining=1(标准一物一码)
     *   2) 不足时退到 remaining>1 的老数据,整批扣减(remaining→0)
     * 扣减后 remaining=0 设为 OUT;否则保留 IN_STOCK(老数据可能跨单据分批出库)。
     * outbound_order_id 仅在本次订单消耗了部分时记录。
     */
    public List<ConsumeRecord> consumeForOutbound(int productId, String locationCode, int needQty, int outboundOrderId) {
        List<ConsumeRecord> consumed = new ArrayList<>();
        if (needQty <= 0) return consumed;

        // 1) 优先选 remaining=1 的标准一物一码单元
        List<InventoryItem> units = jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE product_id = ? AND location_code = ? AND status = 'IN_STOCK' AND remaining = 1 " +
                "ORDER BY id ASC",
                ROW, productId, locationCode);

        int left = needQty;
        for (InventoryItem it : units) {
            if (left <= 0) break;
            jdbc.update(
                    "UPDATE inventory_item SET remaining = 0, status = ?, " +
                    "outbound_order_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    InventoryItem.STATUS_OUT, outboundOrderId, it.getId());
            consumed.add(new ConsumeRecord(it.getId(), 1));
            left--;
        }
        if (left <= 0) return consumed;

        // 2) 退路:选 remaining>1 的老数据单元,整批扣减
        List<InventoryItem> legacy = jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE product_id = ? AND location_code = ? AND status = 'IN_STOCK' AND remaining > 1 " +
                "ORDER BY id ASC",
                ROW, productId, locationCode);
        for (InventoryItem it : legacy) {
            if (left <= 0) break;
            int take = Math.min(left, it.getRemaining());
            int newRem = it.getRemaining() - take;
            String newStatus = (newRem <= 0) ? InventoryItem.STATUS_OUT : InventoryItem.STATUS_IN_STOCK;
            jdbc.update(
                    "UPDATE inventory_item SET remaining = ?, status = ?, " +
                    "outbound_order_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    newRem, newStatus, outboundOrderId, it.getId());
            consumed.add(new ConsumeRecord(it.getId(), take));
            left -= take;
        }
        return consumed;
    }

    /** 出库消耗记录 */
    public static class ConsumeRecord {
        public final int inventoryItemId;
        public final int quantity;
        public ConsumeRecord(int id, int qty) { this.inventoryItemId = id; this.quantity = qty; }
    }

    /** 报废 */
    public int scrap(int id, String remark) {
        return jdbc.update(
                "UPDATE inventory_item SET status = ?, scrapped_at = CURRENT_TIMESTAMP, " +
                "scrapped_remark = ?, remaining = 0, updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND status = 'IN_STOCK'",
                InventoryItem.STATUS_SCRAPPED, remark, id);
    }

    /** 调拨:改 location_code + 记录 from/to */
    public int transfer(int id, String toLocation) {
        return jdbc.update(
                "UPDATE inventory_item SET status = ?, transferred_at = CURRENT_TIMESTAMP, " +
                "transferred_from = location_code, transferred_to = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'IN_STOCK'",
                InventoryItem.STATUS_TRANSFERRED, toLocation, id);
    }

    /** 简单统计:在库总数(供仪表盘参考) */
    public Long sumRemaining() {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(remaining), 0) FROM inventory_item WHERE status = 'IN_STOCK'",
                Long.class);
        return v == null ? 0L : v;
    }

    /** 在库单元在某库位的剩余总数(供出库预校验) */
    public Long sumRemaining(int productId, String locationCode) {
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(remaining), 0) FROM inventory_item " +
                    "WHERE product_id = ? AND location_code = ? AND status = 'IN_STOCK'",
                    Long.class, productId, locationCode);
            return v == null ? 0L : v;
        } catch (Exception e) {
            return 0L;
        }
    }
}
