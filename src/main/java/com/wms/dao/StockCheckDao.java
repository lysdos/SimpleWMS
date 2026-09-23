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
        int wid = rs.getInt("warehouse_id");
        c.setWarehouseId(rs.wasNull() ? null : wid);
        c.setRemark(rs.getString("remark"));
        c.setCreatedBy(rs.getString("created_by"));
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
        String sql = "INSERT INTO stock_check (check_no, scope, scope_keyword, warehouse_id, remark, " +
                "created_by, status, total_count, counted_count, diff_count) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.getCheckNo());
            ps.setString(2, c.getScope());
            ps.setString(3, c.getScopeKeyword());
            if (c.getWarehouseId() == null) ps.setNull(4, java.sql.Types.INTEGER);
            else ps.setInt(4, c.getWarehouseId());
            ps.setString(5, c.getRemark());
            ps.setString(6, c.getCreatedBy());
            ps.setString(7, c.getStatus() == null ? StockCheck.STATUS_DRAFT : c.getStatus());
            ps.setInt(8, c.getTotalCount() == null ? 0 : c.getTotalCount());
            ps.setInt(9, c.getCountedCount() == null ? 0 : c.getCountedCount());
            ps.setInt(10, c.getDiffCount() == null ? 0 : c.getDiffCount());
            return ps;
        }, kh);
        return GeneratedKeys.id(kh);
    }

    public void insertItem(StockCheckItem it) {
        String sql = "INSERT INTO stock_check_item " +
                "(check_id, inventory_item_id, code, sku, product_name, location_code, system_qty, counted_qty, diff, row_remark) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)";
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
            // 快照行为未盘(写 NULL);扫到单外码追加的盘盈行直接带实盘数 1,
            // diff 必须一并写入,否则 Web 差异列显示空白却已计入差异数
            Integer counted = it.getCountedQty();
            if (counted == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, counted);
                ps.setInt(9, counted - it.getSystemQty());
            }
            ps.setString(10, it.getRowRemark());
            return ps;
        }, kh);
        int k = GeneratedKeys.id(kh);
        if (k != 0) it.setId(k);
    }

    /** 删除明细行(仅调用方已校验 DRAFT);返回影响行数 */
    public int deleteItem(int checkId, int itemId) {
        return jdbc.update(
                "DELETE FROM stock_check_item WHERE id = ? AND check_id = ?",
                itemId, checkId);
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

    /** 设置 counted_qty 并同步 diff(= counted - system_qty,由 SQL 端计算);
     *  扫码/提交补 0 场景都走这里,保证 Web 差异列不再依赖手工 setItemDiff */
    public int updateCountedQty(int itemId, int countedQty) {
        return jdbc.update(
                "UPDATE stock_check_item SET counted_qty = ?, diff = ? - system_qty, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                countedQty, countedQty, itemId);
    }

    public int setItemDiff(int itemId, int diff) {
        return jdbc.update(
                "UPDATE stock_check_item SET diff = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                diff, itemId);
    }

    public int updateStats(int checkId, int totalCount, int countedCount, int diffCount) {
        return jdbc.update(
                "UPDATE stock_check SET total_count = ?, counted_count = ?, diff_count = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                totalCount, countedCount, diffCount, checkId);
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
