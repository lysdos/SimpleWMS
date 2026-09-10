package com.wms.dao;

import com.wms.model.InboundOrder;
import com.wms.model.InboundOrderItem;
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
public class InboundOrderDao {

    private final JdbcTemplate jdbc;

    public InboundOrderDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<InboundOrder> ORDER_ROW = (rs, n) -> {
        InboundOrder o = new InboundOrder();
        o.setId(rs.getInt("id"));
        o.setOrderNo(rs.getString("order_no"));
        o.setExternalNo(rs.getString("external_no"));
        o.setRemark(rs.getString("remark"));
        o.setOperator(rs.getString("operator"));
        o.setTotalQuantity(rs.getInt("total_quantity"));
        o.setItemCount(rs.getInt("item_count"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        o.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        // status 字段为兼容老数据(可能不存在)做 wasNull 处理
        try {
            String s = rs.getString("status");
            o.setStatus(rs.wasNull() ? InboundOrder.STATUS_DRAFT : s);
        } catch (java.sql.SQLException ignored) {
            o.setStatus(InboundOrder.STATUS_DRAFT);
        }
        return o;
    };

    private static final RowMapper<InboundOrderItem> ITEM_ROW = (rs, n) -> {
        InboundOrderItem it = new InboundOrderItem();
        it.setId(rs.getInt("id"));
        it.setOrderId(rs.getInt("order_id"));
        it.setProductId(rs.getInt("product_id"));
        it.setSku(rs.getString("sku"));
        it.setProductName(rs.getString("product_name"));
        it.setSupplier(rs.getString("supplier"));
        it.setQuantity(rs.getInt("quantity"));
        java.math.BigDecimal price = rs.getBigDecimal("unit_price");
        it.setUnitPrice(price);
        it.setBeforeQty(rs.getInt("before_qty"));
        it.setAfterQty(rs.getInt("after_qty"));
        it.setLocation(rs.getString("location"));
        it.setRowRemark(rs.getString("row_remark"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        it.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return it;
    };

    /** 插入入库单主表,返回自增 id */
    public int insertOrder(InboundOrder o) {
        String sql = "INSERT INTO inbound_order (order_no, external_no, remark, " +
                "operator, total_quantity, item_count, status) VALUES (?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, o.getOrderNo());
            ps.setString(2, o.getExternalNo());
            ps.setString(3, o.getRemark());
            ps.setString(4, o.getOperator());
            ps.setInt(5, o.getTotalQuantity() == null ? 0 : o.getTotalQuantity());
            ps.setInt(6, o.getItemCount() == null ? 0 : o.getItemCount());
            ps.setString(7, o.getStatus() == null ? InboundOrder.STATUS_DRAFT : o.getStatus());
            return ps;
        }, kh);
        Number k = kh.getKey();
        if (k == null) {
            Map<String, Object> keys = kh.getKeys();
            if (keys != null && keys.get("id") != null) k = (Number) keys.get("id");
        }
        return k == null ? 0 : k.intValue();
    }

    public int insertItem(InboundOrderItem it) {
        String sql = "INSERT INTO inbound_order_item (order_id, product_id, sku, product_name, " +
                "supplier, quantity, unit_price, before_qty, after_qty, location, row_remark) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        int rows = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, it.getOrderId());
            ps.setInt(2, it.getProductId());
            ps.setString(3, it.getSku());
            ps.setString(4, it.getProductName());
            if (it.getSupplier() == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, it.getSupplier());
            ps.setInt(6, it.getQuantity());
            ps.setBigDecimal(7, it.getUnitPrice());
            ps.setInt(8, it.getBeforeQty() == null ? 0 : it.getBeforeQty());
            ps.setInt(9, it.getAfterQty() == null ? 0 : it.getAfterQty());
            if (it.getLocation() == null) ps.setNull(10, java.sql.Types.VARCHAR); else ps.setString(10, it.getLocation());
            if (it.getRowRemark() == null) ps.setNull(11, java.sql.Types.VARCHAR); else ps.setString(11, it.getRowRemark());
            return ps;
        }, kh);
        Number k = kh.getKey();
        if (k != null) it.setId(k.intValue());
        return rows;
    }

    /** 更新入库单状态(无守卫)—— 仅 cancelDraft 取消前的内部状态用 */
    public int updateStatus(int orderId, String status) {
        return jdbc.update(
                "UPDATE inbound_order SET status = ? WHERE id = ?",
                status, orderId);
    }

    /** 仅当订单仍为 DRAFT 时才推进状态(影响行数 0 = 已被并发/手工修改过,commit/cancel 应放弃) */
    public int updateStatusFromDraft(int orderId, String newStatus) {
        return jdbc.update(
                "UPDATE inbound_order SET status = ? WHERE id = ? AND status = ?",
                newStatus, orderId, InboundOrder.STATUS_DRAFT);
    }

    /** 重算 total_quantity 和 item_count(明细增删后调用) */
    public void refreshAggregates(int orderId) {
        jdbc.update(
                "UPDATE inbound_order o SET " +
                "  total_quantity = COALESCE((SELECT SUM(quantity) FROM inbound_order_item WHERE order_id = o.id), 0), " +
                "  item_count    = COALESCE((SELECT COUNT(*) FROM inbound_order_item WHERE order_id = o.id), 0) " +
                "WHERE o.id = ?",
                orderId);
    }

    /** 删除指定单的全部明细(rollback 时用) */
    public int deleteItemsByOrderId(int orderId) {
        return jdbc.update("DELETE FROM inbound_order_item WHERE order_id = ?", orderId);
    }

    public InboundOrder findOrderById(int id) {
        List<InboundOrder> list = jdbc.query(
                "SELECT * FROM inbound_order WHERE id = ?", ORDER_ROW, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<InboundOrderItem> findItemsByOrderId(int orderId) {
        return jdbc.query(
                "SELECT * FROM inbound_order_item WHERE order_id = ? ORDER BY id ASC",
                ITEM_ROW, orderId);
    }

    /**
     * 列表查询(单库通用)。
     * 必传: beginDate, endDate (yyyy-MM-dd 或 'all')
     * 选传: keyword (单号/供应商/外部单号/备注 模糊), operator
     *      status (DRAFT / COMMITTED / CANCELLED,空=全部)
     * 分页: page, pageSize
     */
    public List<InboundOrder> findOrders(String beginDate, String endDate,
                                        String keyword, String operator, String status,
                                        int page, int pageSize) {
        StringBuilder sb = new StringBuilder("SELECT * FROM inbound_order WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (beginDate != null && !beginDate.isBlank() && !"all".equalsIgnoreCase(beginDate)) {
            sb.append(" AND created_at >= ?");
            args.add(beginDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isBlank() && !"all".equalsIgnoreCase(endDate)) {
            sb.append(" AND created_at <= ?");
            args.add(endDate + " 23:59:59");
        }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (order_no LIKE ? OR external_no LIKE ? OR remark LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (operator != null && !operator.isBlank()) {
            sb.append(" AND operator = ?");
            args.add(operator);
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sb.append(" AND status = ?");
            args.add(status);
        }
        sb.append(" ORDER BY id DESC");
        int lim = pageSize <= 0 || pageSize > 200 ? 20 : pageSize;
        int offset = page <= 0 ? 0 : (page - 1) * lim;
        sb.append(" LIMIT ").append(lim).append(" OFFSET ").append(offset);
        return jdbc.query(sb.toString(), ORDER_ROW, args.toArray());
    }

    public long countOrders(String beginDate, String endDate,
                            String keyword, String operator, String status) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM inbound_order WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (beginDate != null && !beginDate.isBlank() && !"all".equalsIgnoreCase(beginDate)) {
            sb.append(" AND created_at >= ?");
            args.add(beginDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isBlank() && !"all".equalsIgnoreCase(endDate)) {
            sb.append(" AND created_at <= ?");
            args.add(endDate + " 23:59:59");
        }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (order_no LIKE ? OR external_no LIKE ? OR remark LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (operator != null && !operator.isBlank()) {
            sb.append(" AND operator = ?");
            args.add(operator);
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sb.append(" AND status = ?");
            args.add(status);
        }
        Long c = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return c == null ? 0L : c;
    }
}
