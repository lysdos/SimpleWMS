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
        String sql = "INSERT INTO stock_check (check_no, scope, scope_keyword, remark, " +
                "created_by, status, total_count, counted_count, diff_count) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.getCheckNo());
            ps.setString(2, c.getScope());
            ps.setString(3, c.getScopeKeyword());
            ps.setString(4, c.getRemark());
            ps.setString(5, c.getCreatedBy());
            ps.setString(6, c.getStatus() == null ? StockCheck.STATUS_DRAFT : c.getStatus());
            ps.setInt(7, c.getTotalCount() == null ? 0 : c.getTotalCount());
            ps.setInt(8, c.getCountedCount() == null ? 0 : c.getCountedCount());
            ps.setInt(9, c.getDiffCount() == null ? 0 : c.getDiffCount());
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

    /**
     * 加锁读盘点单头。**「改明细 + 重算汇总」的事务必须用这个、而且必须是该事务的第一条读。**
     *
     * 为什么:重算用的 {@link #updateStats(int)} 在 DML 里带相关子查询,是**加锁读**,会按
     * {@code uk(check_id, inventory_item_id)} 把该单整段明细索引范围锁住。两个并发扫码事务若各自
     * 先改了自己的明细行(已持锁)、再去锁对方那行,就是死锁 —— 现场 RFID 齐发时必现
     * (实测 15 个并发事务打同一张单:改明细路径每轮 2~4 个事务死锁,盘盈插入路径 8~9 个,
     * 且被回滚的那次**连插入的明细行一起丢**)。
     * 把单头行锁提到最前面,同一张单的事务从事务开头就排队,后面那段范围锁就永远没人跟它抢。
     *
     * ⚠️ 不能"先用普通 findCheckById 再补一条 FOR UPDATE":锁虽然拿到了,但一致性读视图已经在那之前
     * 建立,后面的 findItemsByCheckId 仍会读到旧快照 —— 表现为重复扫同一个码不再报「该码已盘过」。
     * FOR UPDATE 本身是当前读,一次读到位才不会留这个缝。
     */
    public StockCheck findCheckByIdForUpdate(int id) {
        List<StockCheck> list = jdbc.query(
                "SELECT * FROM stock_check WHERE id = ? FOR UPDATE", CHECK_ROW, id);
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

    /**
     * 盘盈行判定(等价于 {@code StockCheckService} 里原先那个 {@code isSurplus()}):**账面为 0 且实盘 > 0**。
     * 扫到盘点范围外的单元时 scanCodeToCheck 会自动追加这样一行(systemQty=0, countedQty=1),
     * 它不属于"应盘"的对象,所以不计入应盘/已盘/差异 —— 否则现场扫到一个范围外的码,
     * 「已盘 6/7」就会变成「已盘 6/8」,看着像盘点单自己变大了。
     *
     * ⚠️ `counted_qty IS NOT NULL` 不能省:counted_qty 可空(schema 是 DEFAULT NULL),
     * 少了它,`counted_qty > 0` 在 NULL 时求值为 NULL,整个 AND 变 NULL,`NOT NULL` 还是 NULL,
     * 该行会被 COUNT 静默丢掉 —— 而 Java 侧写的是 `counted != null`,是会把这种行算进应盘的。
     *
     * system_qty 目前是 NOT NULL,COALESCE 是防御性的(留个万一)。
     */
    private static final String SURPLUS_COND =
            "COALESCE(i.system_qty, 0) = 0 AND i.counted_qty IS NOT NULL AND i.counted_qty > 0";

    /**
     * 重算盘点单的三个汇总列。口径:应盘 = 非盘盈行;已盘 = 非盘盈且 counted_qty 非空;
     * 差异 = 非盘盈且已盘且实盘≠账面(未盘行不计入已盘,也不计入差异)。
     *
     * **为什么整段算在一条 SQL 里、不把明细读进 Java 再写回**:UHF 连续扫码会并发提交
     * (现场一秒几十个请求),若先在应用层 SELECT 明细、算好、再 UPDATE 写回字面量,两个事务的
     * SELECT 都可能落在对方提交之前,后提交的那个会把基于旧快照算出的数覆盖掉新值(丢失更新)——
     * 现场表现为「6 行实盘都是 1,表头却停在已盘 5」,而且重复扫同一个码会被 422 挡在重算之前,
     * 所以这个错数**不会自愈**。
     *
     * 相关子查询在 DML 里是**当前读**(实测:同一事务内普通 SELECT 看不到的并发提交,子查询看得到),
     * 所以它读到的永远是最新的已提交数据。
     *
     * 但**光有这条 SQL 挡不住并发**:它同时会对该单整段明细加范围锁,两个并发事务会各持自己的
     * 明细锁、再互锁对方那一行 → 死锁。串行化不来自这条 SQL,而来自**调用方在事务开头先锁单头行**
     * (见 {@link #findCheckByIdForUpdate(int)})。出入库单的 {@code refreshAggregates} 是同一形状,
     * 但那里是人工节奏、不存在同单并发。
     *
     * ⚠️ 三段子查询必须与 {@link #SURPLUS_COND} 保持同源,别只改一处。
     */
    public int updateStats(int checkId) {
        return jdbc.update(
                "UPDATE stock_check c SET " +
                "  total_count = (SELECT COUNT(*) FROM stock_check_item i " +
                "                 WHERE i.check_id = c.id AND NOT (" + SURPLUS_COND + ")), " +
                "  counted_count = (SELECT COUNT(*) FROM stock_check_item i " +
                "                   WHERE i.check_id = c.id AND NOT (" + SURPLUS_COND + ") " +
                "                     AND i.counted_qty IS NOT NULL), " +
                "  diff_count = (SELECT COUNT(*) FROM stock_check_item i " +
                "                WHERE i.check_id = c.id AND NOT (" + SURPLUS_COND + ") " +
                "                  AND i.counted_qty IS NOT NULL " +
                "                  AND i.counted_qty <> i.system_qty), " +
                "  updated_at = CURRENT_TIMESTAMP " +
                "WHERE c.id = ?",
                checkId);
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
