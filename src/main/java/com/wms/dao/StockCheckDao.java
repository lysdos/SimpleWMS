package com.wms.dao;

import com.wms.model.StockCheck;
import com.wms.model.StockCheckItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Repository
public class StockCheckDao {

    private final JdbcTemplate jdbc;
    public StockCheckDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<StockCheck> CHECK_ROW = (rs, n) -> {
        StockCheck c = new StockCheck();
        c.setId(rs.getInt("id"));
        c.setCheckNo(rs.getString("check_no"));
        c.setScope(rs.getString("scope"));
        c.setScopeKeyword(rs.getString("scope_keyword"));
        c.setRemark(rs.getString("remark"));
        c.setOperator(rs.getString("operator"));
        c.setStatus(rs.getString("status"));
        c.setTotalCount(rs.getInt("total_count"));
        c.setCountedCount(rs.getInt("counted_count"));
        c.setDiffCount(rs.getInt("diff_count"));
        java.sql.Timestamp ts = rs.getTimestamp("committed_at");
        c.setCommittedAt(ts == null ? null : ts.toLocalDateTime());
        ts = rs.getTimestamp("created_at");
        c.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        ts = rs.getTimestamp("updated_at");
        c.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return c;
    };

    private static final RowMapper<StockCheckItem> ITEM_ROW = (rs, n) -> {
        StockCheckItem it = new StockCheckItem();
        it.setId(rs.getInt("id"));
        it.setCheckId(rs.getInt("check_id"));
        it.setInventoryItemId(rs.getInt("inventory_item_id"));
        it.setCode(rs.getString("code"));
        it.setSku(rs.getString("sku"));
        it.setProductName(rs.getString("product_name"));
        it.setLocationCode(rs.getString("location_code"));
        it.setSystemQty(rs.getInt("system_qty"));
        int cq = rs.getInt("counted_qty");
        it.setCountedQty(rs.wasNull() ? null : cq);
        int df = rs.getInt("diff");
        it.setDiff(rs.wasNull() ? null : df);
        it.setRowRemark(rs.getString("row_remark"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        it.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        ts = rs.getTimestamp("updated_at");
        it.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return it;
    };

    public int insertCheck(StockCheck c) {
        String sql = "INSERT INTO stock_check (check_no, scope, scope_keyword, remark, " +
                "operator, status, total_count, counted_count, diff_count) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.getCheckNo());
            ps.setString(2, c.getScope());
            ps.setString(3, c.getScopeKeyword());
            ps.setString(4, c.getRemark());
            ps.setString(5, c.getOperator());
            ps.setString(6, c.getStatus() == null ? StockCheck.STATUS_DRAFT : c.getStatus());
            ps.setInt(7, c.getTotalCount() == null ? 0 : c.getTotalCount());
            ps.setInt(8, c.getCountedCount() == null ? 0 : c.getCountedCount());
            ps.setInt(9, c.getDiffCount() == null ? 0 : c.getDiffCount());
            return ps;
        }, kh);
        Number k = kh.getKey();
        if (k == null) {
            Map<String, Object> keys = kh.getKeys();
            if (keys != null && keys.get("id") != null) k = (Number) keys.get("id");
        }
        return k == null ? 0 : k.intValue();
    }

    public void insertItem(StockCheckItem it) {
        String sql = "INSERT INTO stock_check_item " +
                "(check_id, inventory_item_id, code, sku, product_name, location_code, system_qty, row_remark) " +
                "VALUES (?,?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, it.getCheckId());
            ps.setInt(2, it.getInventoryItemId());
            ps.setString(3, it.getCode());
            ps.setString(4, it.getSku());
            ps.setString(5, it.getProductName());
            ps.setString(6, it.getLocationCode());
            ps.setInt(7, it.getSystemQty());
            ps.setString(8, it.getRowRemark());
            return ps;
        }, kh);
        Number k = kh.getKey();
        if (k != null) it.setId(k.intValue());
    }

    public StockCheck findCheckById(int id) {
        List<StockCheck> list = jdbc.query(
                "SELECT * FROM stock_check WHERE id = ?", CHECK_ROW, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<StockCheckItem> findItemsByCheckId(int checkId) {
        return jdbc.query(
                "SELECT * FROM stock_check_item WHERE check_id = ? ORDER BY id ASC",
                ITEM_ROW, checkId);
    }

    public List<StockCheck> findChecks(String beginDate, String endDate, String status, int page, int pageSize) {
        StringBuilder sb = new StringBuilder("SELECT * FROM stock_check WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (beginDate != null && !beginDate.isBlank() && !"all".equalsIgnoreCase(beginDate)) {
            sb.append(" AND created_at >= ?"); args.add(beginDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isBlank() && !"all".equalsIgnoreCase(endDate)) {
            sb.append(" AND created_at <= ?"); args.add(endDate + " 23:59:59");
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sb.append(" AND status = ?"); args.add(status);
        }
        sb.append(" ORDER BY id DESC");
        int lim = pageSize <= 0 || pageSize > 200 ? 20 : pageSize;
        int offset = page <= 0 ? 0 : (page - 1) * lim;
        sb.append(" LIMIT ").append(lim).append(" OFFSET ").append(offset);
        return jdbc.query(sb.toString(), CHECK_ROW, args.toArray());
    }

    public long countChecks(String beginDate, String endDate, String status) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM stock_check WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (beginDate != null && !beginDate.isBlank() && !"all".equalsIgnoreCase(beginDate)) {
            sb.append(" AND created_at >= ?"); args.add(beginDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isBlank() && !"all".equalsIgnoreCase(endDate)) {
            sb.append(" AND created_at <= ?"); args.add(endDate + " 23:59:59");
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sb.append(" AND status = ?"); args.add(status);
        }
        Long c = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return c == null ? 0L : c;
    }

    public int updateItemCount(int itemId, int countedQty, String remark) {
        // diff 由 service 计算填入(避免 UPDATE 时再读一次)
        return jdbc.update(
                "UPDATE stock_check_item SET counted_qty = ?, row_remark = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                countedQty, remark, itemId);
    }

    /** 仅累加 counted_qty(扫码场景),不覆盖 row_remark */
    public int updateCountedQty(int itemId, int countedQty) {
        return jdbc.update(
                "UPDATE stock_check_item SET counted_qty = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                countedQty, itemId);
    }

    public int setItemDiff(int itemId, int diff) {
        return jdbc.update(
                "UPDATE stock_check_item SET diff = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                diff, itemId);
    }

    public int updateStats(int checkId, int countedCount, int diffCount) {
        return jdbc.update(
                "UPDATE stock_check SET counted_count = ?, diff_count = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                countedCount, diffCount, checkId);
    }

    public int commit(int checkId) {
        return jdbc.update(
                "UPDATE stock_check SET status = ?, committed_at = CURRENT_TIMESTAMP, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?",
                StockCheck.STATUS_COMMITTED, checkId, StockCheck.STATUS_DRAFT);
    }

    public int cancel(int checkId) {
        return jdbc.update(
                "UPDATE stock_check SET status = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND status = ?",
                StockCheck.STATUS_CANCELLED, checkId, StockCheck.STATUS_DRAFT);
    }
}
