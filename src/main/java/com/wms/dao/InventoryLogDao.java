package com.wms.dao;

import com.wms.model.InventoryLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

@Repository
public class InventoryLogDao {

    private final JdbcTemplate jdbc;
    private static final RowMapper<InventoryLog> ROW = (rs, n) -> {
        InventoryLog l = new InventoryLog();
        l.setId(rs.getInt("id"));
        l.setType(rs.getString("type"));
        l.setProductId(rs.getInt("product_id"));
        l.setSku(rs.getString("sku"));
        l.setProductName(rs.getString("product_name"));
        l.setQuantity(rs.getInt("quantity"));
        l.setBeforeQty(rs.getInt("before_qty"));
        l.setAfterQty(rs.getInt("after_qty"));
        l.setRemark(rs.getString("remark"));
        l.setOperator(rs.getString("operator"));
        int oid = rs.getInt("order_id");
        l.setOrderId(rs.wasNull() ? null : oid);
        l.setCreatedAt(rs.getTimestamp("created_at") == null
                ? null : rs.getTimestamp("created_at").toLocalDateTime());
        return l;
    };

    public InventoryLogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(InventoryLog log) {
        String sql = "INSERT INTO inventory_log (type, product_id, sku, product_name, " +
                "quantity, before_qty, after_qty, remark, operator, order_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, log.getType());
            ps.setInt(2, log.getProductId());
            ps.setString(3, log.getSku());
            ps.setString(4, log.getProductName());
            ps.setInt(5, log.getQuantity());
            ps.setInt(6, log.getBeforeQty());
            ps.setInt(7, log.getAfterQty());
            ps.setString(8, log.getRemark());
            ps.setString(9, log.getOperator());
            if (log.getOrderId() == null) ps.setNull(10, java.sql.Types.INTEGER);
            else ps.setInt(10, log.getOrderId());
            return ps;
        }, kh);
        Map<String, Object> keys = kh.getKeys();
        if (keys != null && keys.get("id") != null) {
            return ((Number) keys.get("id")).intValue();
        }
        return 0;
    }

    public List<InventoryLog> findLogs(String type, String keyword, Integer limit) {
        StringBuilder sb = new StringBuilder(
                "SELECT * FROM inventory_log WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (type != null && !type.isBlank() && !"ALL".equalsIgnoreCase(type)) {
            sb.append(" AND type = ?");
            args.add(type);
        }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (sku LIKE ? OR product_name LIKE ? OR remark LIKE ? OR operator LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        sb.append(" ORDER BY id DESC");
        int lim = (limit == null || limit <= 0 || limit > 500) ? 200 : limit;
        sb.append(" LIMIT ").append(lim);

        return jdbc.query(sb.toString(), ROW, args.toArray());
    }
}