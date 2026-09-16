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
        java.sql.Date pd = rs.getDate("production_date");
        i.setProductionDate(pd == null ? null : pd.toLocalDate());
        int sld = rs.getInt("shelf_life_days");
        i.setShelfLifeDays(rs.wasNull() ? null : sld);
        java.sql.Date ed = rs.getDate("expiry_date");
        i.setExpiryDate(ed == null ? null : ed.toLocalDate());
        try {   // expiry_warn_days 来自联表(findItems),普通 SELECT * 查询没有该列
            int ewd = rs.getInt("expiry_warn_days");
            i.setExpiryWarnDays(rs.wasNull() ? null : ewd);
        } catch (java.sql.SQLException ignored) {}
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
                "inbound_order_id, inbound_item_id, created_by, production_date, shelf_life_days, expiry_date) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
            if (i.getProductionDate() == null) ps.setNull(12, java.sql.Types.DATE); else ps.setDate(12, java.sql.Date.valueOf(i.getProductionDate()));
            if (i.getShelfLifeDays() == null) ps.setNull(13, java.sql.Types.INTEGER); else ps.setInt(13, i.getShelfLifeDays());
            if (i.getExpiryDate() == null) ps.setNull(14, java.sql.Types.DATE); else ps.setDate(14, java.sql.Date.valueOf(i.getExpiryDate()));
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
        if (code == null || code.isBlank()) return null;
        String c = code.trim();
        List<InventoryItem> exact = jdbc.query(
                "SELECT * FROM inventory_item WHERE code = ?", ROW, c);
        if (!exact.isEmpty()) return exact.get(0);
        // 兼容条码前导零丢失:扫到的码比库里短(如 16 位 vs 24 位),退化为尾匹配(与 findByEpc 一致)
        String r = c.toUpperCase();
        List<InventoryItem> fuzzy = jdbc.query(
                "SELECT * FROM inventory_item WHERE RIGHT(code, ?) = ? ORDER BY id ASC LIMIT 1",
                ROW, r.length(), r);
        return fuzzy.isEmpty() ? null : fuzzy.get(0);
    }

    /**
     * EPC 长度由调用方传入(标准 24 hex = 12 字节 UHF Gen2,与 inventory_item.code 等长)。
     * 在 inventory_item.code 末 N 位里查找,命中后返回该 unit(任意状态)。
     */
    public InventoryItem findByEpc(String epc) {
        if (epc == null || epc.isBlank()) return null;
        String e = epc.trim().toUpperCase();
        List<InventoryItem> list = jdbc.query(
                "SELECT * FROM inventory_item WHERE RIGHT(code, ?) = ? ORDER BY id ASC LIMIT 1",
                ROW, e.length(), e);
        return list.isEmpty() ? null : list.get(0);
    }

    /** EPC + 状态同时匹配(给 RFID 出库用,挑 RESERVED 已锁定的) */
    public InventoryItem findByEpcAndStatus(String epc, String status) {
        if (epc == null || epc.isBlank()) return null;
        String e = epc.trim().toUpperCase();
        List<InventoryItem> list = jdbc.query(
                "SELECT * FROM inventory_item WHERE RIGHT(code, ?) = ? AND status = ? ORDER BY id ASC LIMIT 1",
                ROW, e.length(), e, status);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * UHF 扫码用:按 EPC 找"属于该出库单已锁定(RESERVED)的 unit"
     * @return 命中且属于该单 → unit;否则 null
     */
    public InventoryItem findReservedByEpcForOrder(String epc, int outboundOrderId) {
        if (epc == null || epc.isBlank()) return null;
        String e = epc.trim().toUpperCase();
        List<InventoryItem> list = jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE RIGHT(code, ?) = ? AND status = 'RESERVED' AND outbound_order_id = ? " +
                "ORDER BY id ASC LIMIT 1",
                ROW, e.length(), e, outboundOrderId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按 EPC 把 unit 标记为 OUT(剩余置 0),扣 1 件 */
    public int consumeForOutboundByEpc(String epc, int outboundOrderId) {
        if (epc == null || epc.isBlank()) return 0;
        String e = epc.trim().toUpperCase();
        return jdbc.update(
                "UPDATE inventory_item SET status = 'OUT', remaining = 0, " +
                "outbound_order_id = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE RIGHT(code, ?) = ? AND status = 'IN_STOCK'",
                outboundOrderId, e.length(), e);
    }

    /**
     * 拉取出库单**已锁定**的 inventory_item 清单(状态 RESERVED 且 outbound_order_id=本单)。
     * 这是 UHF 扫码端应该扫的清单,不是全 SKU 的 IN_STOCK 池。
     * 同时统计"已扫过"和"还差多少" — 简单起见用 inventory_log.type='OUT' AND order_id 计数。
     */
    public List<com.wms.model.ExpectedEpc> findExpectedEpcsForOutbound(int outboundOrderId) {
        List<com.wms.model.ExpectedEpc> result = new java.util.ArrayList<>();
        List<InventoryItem> units = jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE status = 'RESERVED' AND outbound_order_id = ? " +
                "ORDER BY id ASC",
                ROW, outboundOrderId);
        for (InventoryItem u : units) {
            result.add(new com.wms.model.ExpectedEpc(
                    u.getProductId(), u.getSku(), u.getProductName(),
                    u.getCode(), 0, 1));
        }
        return result;
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

    /**
     * 删除某明细行下指定数量的 PENDING 占位单元(DRAFT 单缩减数量/删行时用)。
     * 只删 PENDING(未生效),已激活的不碰。
     */
    public int deletePendingByInboundItem(int inboundItemId, int limit) {
        return jdbc.update(
                "DELETE FROM inventory_item WHERE inbound_item_id = ? AND status = ? LIMIT ?",
                inboundItemId, InventoryItem.STATUS_PENDING, limit);
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

    /** 列出某出库单某商品的所有库存单元(RESERVED + OUT,逐件显示用) */
    public List<InventoryItem> findByOutboundOrderAndProduct(int orderId, int productId) {
        return jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE outbound_order_id = ? AND product_id = ? " +
                "AND status IN ('RESERVED', 'OUT') ORDER BY id ASC",
                ROW, orderId, productId);
    }

    /**
     * 列表查询(库存管理用)。
     * @param sku     SKU 模糊匹配(可空)
     * @param location 库位精确匹配(可空)
     * @param status  IN_STOCK / OUT / SCRAPPED / TRANSFERRED / 空(=全部)
     * @param expiryStatus EXPIRING=临期(距到期<=商品预警天数,默认30) / EXPIRED=已过期 / 空(=全部)
     * @param expiryFrom, expiryTo 到期日范围(yyyy-MM-dd,可空)
     * @param page, pageSize
     */
    public List<InventoryItem> findItems(String sku, String location, String status,
                                          String expiryStatus, String expiryFrom, String expiryTo,
                                          int page, int pageSize) {
        StringBuilder sb = new StringBuilder("SELECT i.*, p.expiry_warn_days FROM inventory_item i " +
                "LEFT JOIN product p ON p.id = i.product_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendItemFilters(sb, args, sku, location, status, expiryStatus, expiryFrom, expiryTo);
        sb.append(" ORDER BY i.id DESC");
        // 导出场景:controller 传 pageSize=5000(导出全部),被 cap 成 20 = 漏数据。
        // 把上限放到 100000(只对导出生效,UI 列表仍走 pageSize=20),确保 export.csv 拿到全集。
        int lim;
        int offset;
        if (pageSize > 200) {
            // 导出 / 批量:不应用 200 的硬上限
            lim = pageSize;
            offset = 0;
        } else {
            lim = pageSize <= 0 ? 20 : pageSize;
            offset = page <= 0 ? 0 : (page - 1) * lim;
        }
        sb.append(" LIMIT ").append(lim).append(" OFFSET ").append(offset);
        return jdbc.query(sb.toString(), ROW, args.toArray());
    }

    public long countItems(String sku, String location, String status,
                           String expiryStatus, String expiryFrom, String expiryTo) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM inventory_item i " +
                "LEFT JOIN product p ON p.id = i.product_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendItemFilters(sb, args, sku, location, status, expiryStatus, expiryFrom, expiryTo);
        Long c = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return c == null ? 0L : c;
    }

    /** findItems / countItems 共用的过滤条件(注意:外层查询用了别名 i,product 别名 p) */
    private void appendItemFilters(StringBuilder sb, List<Object> args,
                                   String sku, String location, String status,
                                   String expiryStatus, String expiryFrom, String expiryTo) {
        if (sku != null && !sku.isBlank()) {
            sb.append(" AND i.sku LIKE ?");
            args.add("%" + sku.trim() + "%");
        }
        if (location != null && !location.isBlank()) {
            sb.append(" AND i.location_code = ?");
            args.add(location.trim());
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sb.append(" AND i.status = ?");
            args.add(status);
        }
        if (expiryFrom != null && !expiryFrom.isBlank()) {
            sb.append(" AND i.expiry_date >= ?");
            args.add(expiryFrom.trim());
        }
        if (expiryTo != null && !expiryTo.isBlank()) {
            sb.append(" AND i.expiry_date <= ?");
            args.add(expiryTo.trim());
        }
        if ("EXPIRED".equalsIgnoreCase(expiryStatus)) {
            sb.append(" AND i.expiry_date IS NOT NULL AND i.expiry_date < CURDATE()");
        } else if ("EXPIRING".equalsIgnoreCase(expiryStatus)) {
            // 临期:未过期且距到期日 <= 该商品的预警天数(NULL=默认30天)
            sb.append(" AND i.expiry_date IS NOT NULL AND i.expiry_date >= CURDATE()")
              .append(" AND i.expiry_date <= CURDATE() + INTERVAL COALESCE(p.expiry_warn_days, 30) DAY");
        }
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

    // ====== DRAFT 出库单 EPC 锁定(方案 A:建单立刻锁) ======

    /**
     * 为 DRAFT 出库单按 FIFO 锁定 N 个 inventory_item(整件一物一码)。
     * 锁定后状态 = RESERVED,outbound_order_id 写上。
     * 优先 remaining=1 的标准单元,不足时退到 remaining>1 的老数据(整批锁)。
     * @return 锁定的(行数,总件数)
     */
    public ReserveResult reserveForOutbound(int productId, String locationCode, int needQty, int outboundOrderId) {
        ReserveResult result = new ReserveResult();
        if (needQty <= 0) return result;

        // 1) remaining=1 标准单元
        List<InventoryItem> units = jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE product_id = ? AND location_code = ? AND status = 'IN_STOCK' AND remaining = 1 " +
                "ORDER BY id ASC",
                ROW, productId, locationCode);
        int left = needQty;
        for (InventoryItem it : units) {
            if (left <= 0) break;
            // 关键:UPDATE 带 AND status='IN_STOCK' 守卫,并发预留同一行时只有一个能成功
            int n = jdbc.update(
                    "UPDATE inventory_item SET status = ?, " +
                    "outbound_order_id = ?, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE id = ? AND status = 'IN_STOCK'",
                    InventoryItem.STATUS_RESERVED, outboundOrderId, it.getId());
            if (n > 0) { result.rows++; result.units += 1; left--; }
        }
        if (left <= 0) return result;

        // 2) remaining>1 老数据(整批锁,remaining 不动,等 commit 时再扣)
        List<InventoryItem> legacy = jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE product_id = ? AND location_code = ? AND status = 'IN_STOCK' AND remaining > 1 " +
                "ORDER BY id ASC",
                ROW, productId, locationCode);
        for (InventoryItem it : legacy) {
            if (left <= 0) break;
            int take = Math.min(left, it.getRemaining());
            // 同上加守卫,防两个 DRAFT 并发把同一老数据行预留两次
            int n = jdbc.update(
                    "UPDATE inventory_item SET status = ?, " +
                    "outbound_order_id = ?, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE id = ? AND status = 'IN_STOCK'",
                    InventoryItem.STATUS_RESERVED, outboundOrderId, it.getId());
            if (n > 0) {
                result.rows++;
                result.units += take;  // 老数据:一件 unit 可能贡献多个数量
                left -= take;
            }
        }
        if (left > 0) {
            // 库存不足,需要回滚已锁定的(调用方需在事务里感知异常)
            throw new IllegalStateException("库存不足:需要 " + needQty + " 件,只能锁 " + (needQty - left) + " 件");
        }
        return result;
    }

    /**
     * 取消 DRAFT 出库单:把所有 RESERVED 状态、属于该单的 inventory_item 退回 IN_STOCK。
     */
    public int releaseReservations(int outboundOrderId) {
        // 整件单元:RESERVED → IN_STOCK,清 outbound_order_id
        // 老数据(remaining>1)单元:RESERVED → IN_STOCK,清 outbound_order_id
        return jdbc.update(
                "UPDATE inventory_item " +
                "SET status = 'IN_STOCK', outbound_order_id = NULL, " +
                "    updated_at = CURRENT_TIMESTAMP " +
                "WHERE status = 'RESERVED' AND outbound_order_id = ?",
                outboundOrderId);
    }

    /**
     * 提交 DRAFT 出库单:把 RESERVED 单元正式设为 OUT,remaining 减 1(老数据整批扣减)。
     * 库存(product.quantity)由 Service 层用 adjustQuantity 减。
     */
    public List<ConsumeRecord> commitReservations(int outboundOrderId) {
        // 1) 整件一物一码:RESERVED → OUT, remaining=0
        List<ConsumeRecord> out = new ArrayList<>();
        List<InventoryItem> units = jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE status = 'RESERVED' AND outbound_order_id = ? AND remaining = 1 " +
                "ORDER BY id ASC",
                ROW, outboundOrderId);
        for (InventoryItem u : units) {
            jdbc.update(
                    "UPDATE inventory_item SET status = ?, remaining = 0, " +
                    "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    InventoryItem.STATUS_OUT, u.getId());
            out.add(new ConsumeRecord(u.getId(), 1));
        }
        // 2) 老数据(remaining>1):按 create 时的 take 数已占,这里整批扣 → OUT
        List<InventoryItem> legacy = jdbc.query(
                "SELECT * FROM inventory_item " +
                "WHERE status = 'RESERVED' AND outbound_order_id = ? AND remaining > 1 " +
                "ORDER BY id ASC",
                ROW, outboundOrderId);
        for (InventoryItem u : legacy) {
            int take = u.getRemaining();  // 整批都归本单
            jdbc.update(
                    "UPDATE inventory_item SET status = ?, remaining = 0, " +
                    "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    InventoryItem.STATUS_OUT, u.getId());
            out.add(new ConsumeRecord(u.getId(), take));
        }
        return out;
    }

    /** 锁定结果 */
    public static class ReserveResult {
        public int rows = 0;   // 锁定的 unit 行数
        public int units = 0;  // 锁定的总件数(老数据一件可能算多个)
    }

    /**
     * 统计"出库单已锁定的、按 product_id+location 划分的件数"。
     * 给老 DRAFT 单 reserveDraft 补全时,计算"还差几件"用。
     * 老数据(remaining>1)按整批贡献 1 个 unit,return 时按 remaining 算件数。
     */
    public Integer countReservedForOrder(int outboundOrderId, int productId, String location) {
        // 整件单元:每行 1 件
        Integer a = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_item " +
                "WHERE status = 'RESERVED' AND outbound_order_id = ? " +
                "  AND product_id = ? AND location_code = ? AND remaining = 1",
                Integer.class, outboundOrderId, productId, location);
        // 老数据:每行算 remaining 件
        Integer b = jdbc.queryForObject(
                "SELECT COALESCE(SUM(remaining), 0) FROM inventory_item " +
                "WHERE status = 'RESERVED' AND outbound_order_id = ? " +
                "  AND product_id = ? AND location_code = ? AND remaining > 1",
                Integer.class, outboundOrderId, productId, location);
        return (a == null ? 0 : a) + (b == null ? 0 : b);
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
