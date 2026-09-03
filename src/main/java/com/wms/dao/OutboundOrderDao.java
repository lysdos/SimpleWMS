package com.wms.dao;

import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
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
public class OutboundOrderDao {

    private final JdbcTemplate jdbc;

    public OutboundOrderDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<OutboundOrder> ORDER_ROW = (rs, n) -> {
        OutboundOrder o = new OutboundOrder();
        o.setId(rs.getInt("id"));
        o.setOrderNo(rs.getString("order_no"));
        o.setCustomer(rs.getString("customer"));
        o.setExternalNo(rs.getString("external_no"));
        o.setRemark(rs.getString("remark"));
        o.setOperator(rs.getString("operator"));
        o.setTotalQuantity(rs.getInt("total_quantity"));
        o.setItemCount(rs.getInt("item_count"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        o.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return o;
    };

    private static final RowMapper<OutboundOrderItem> ITEM_ROW = (rs, n) -> {
        OutboundOrderItem it = new OutboundOrderItem();
        it.setId(rs.getInt("id"));
        it.setOrderId(rs.getInt("order_id"));
        it.setProductId(rs.getInt("product_id"));
        it.setSku(rs.getString("sku"));
        it.setProductName(rs.getString("product_name"));
        it.setQuantity(rs.getInt("quantity"));
        java.math.BigDecimal price = rs.getBigDecimal("unit_price");
        it.setUnitPrice(price);
        it.setBeforeQty(rs.getInt("before_qty"));
        it.setAfterQty(rs.getInt("after_qty"));
        it.setRowRemark(rs.getString("row_remark"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        it.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return it;
    };

    /** 插入出库单主表,返回自增 id */
    public int insertOrder(OutboundOrder o) {
        String sql = "INSERT INTO outbound_order (order_no, customer, external_no, remark, " +
                "operator, total_quantity, item_count) VALUES (?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, o.getOrderNo());
            ps.setString(2, o.getCustomer());
            ps.setString(3, o.getExternalNo());
            ps.setString(4, o.getRemark());
            ps.setString(5, o.getOperator());
            ps.setInt(6, o.getTotalQuantity() == null ? 0 : o.getTotalQuantity());
            ps.setInt(7, o.getItemCount() == null ? 0 : o.getItemCount());
            return ps;
        }, kh);
        Number k = kh.getKey();
        if (k == null) {
            Map<String, Object> keys = kh.getKeys();
            if (keys != null && keys.get("id") != null) k = (Number) keys.get("id");
        }
        return k == null ? 0 : k.intValue();
    }

    public void insertItem(OutboundOrderItem it) {
        String sql = "INSERT INTO outbound_order_item (order_id, product_id, sku, product_name, " +
                "quantity, unit_price, before_qty, after_qty, row_remark) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";
        jdbc.update(sql,
                it.getOrderId(),
                it.getProductId(),
                it.getSku(),
                it.getProductName(),
                it.getQuantity(),
                it.getUnitPrice(),
                it.getBeforeQty(),
                it.getAfterQty(),
                it.getRowRemark());
    }

    public OutboundOrder findOrderById(int id) {
        List<OutboundOrder> list = jdbc.query(
                "SELECT * FROM outbound_order WHERE id = ?", ORDER_ROW, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<OutboundOrderItem> findItemsByOrderId(int orderId) {
        return jdbc.query(
                "SELECT * FROM outbound_order_item WHERE order_id = ? ORDER BY id ASC",
                ITEM_ROW, orderId);
    }

    /**
     * 列表查询(单库通用)。
     */
    public List<OutboundOrder> findOrders(String beginDate, String endDate,
                                          String keyword, String operator,
                                          int page, int pageSize) {
        StringBuilder sb = new StringBuilder("SELECT * FROM outbound_order WHERE 1=1");
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
            sb.append(" AND (order_no LIKE ? OR customer LIKE ? OR external_no LIKE ? OR remark LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        if (operator != null && !operator.isBlank()) {
            sb.append(" AND operator = ?");
            args.add(operator);
        }
        sb.append(" ORDER BY id DESC");
        int lim = pageSize <= 0 || pageSize > 200 ? 20 : pageSize;
        int offset = page <= 0 ? 0 : (page - 1) * lim;
        sb.append(" LIMIT ").append(lim).append(" OFFSET ").append(offset);
        return jdbc.query(sb.toString(), ORDER_ROW, args.toArray());
    }

    public long countOrders(String beginDate, String endDate,
                            String keyword, String operator) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM outbound_order WHERE 1=1");
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
            sb.append(" AND (order_no LIKE ? OR customer LIKE ? OR external_no LIKE ? OR remark LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        if (operator != null && !operator.isBlank()) {
            sb.append(" AND operator = ?");
            args.add(operator);
        }
        Long c = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return c == null ? 0L : c;
    }
}
