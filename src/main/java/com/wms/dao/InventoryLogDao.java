package com.wms.dao;

import com.wms.model.InventoryLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

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
        l.setCreatedBy(rs.getString("created_by"));
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
                "quantity, before_qty, after_qty, remark, created_by, order_id) " +
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
            ps.setString(9, log.getCreatedBy());
            if (log.getOrderId() == null) ps.setNull(10, java.sql.Types.INTEGER);
            else ps.setInt(10, log.getOrderId());
            return ps;
        }, kh);
        return GeneratedKeys.id(kh);
    }

    /**
     * 统计本出库单某 EPC 的扫码确认次数(SCAN 类型日志)。
     * 给 UHF scanEpc 用,>0 说明已扫过。
     */
    public int countScannedForOutboundEpc(int orderId, String epc) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_log " +
                "WHERE type = 'SCAN' AND order_id = ? AND remark = ?",
                Integer.class, orderId, "UHF 扫码确认, epc=" + epc);
        return n == null ? 0 : n;
    }

    /**
     * 统计本出库单某库存单元编码的条码扫码确认次数(SCAN 类型日志)。
     * 给条码路径 scanCodeToDraft 去重用,>0 说明已扫过。
     */
    public int countScannedForOutboundCode(int orderId, String code) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_log " +
                "WHERE type = 'SCAN' AND order_id = ? AND remark = ?",
                Integer.class, orderId, "条码扫码确认, code=" + code);
        return n == null ? 0 : n;
    }

    /**
     * 本出库单全部扫码确认日志的 remark(SCAN 类型)。
     * 给 getOrder 回填已扫码编码列表用(条码 remark 含 code=,UHF remark 含 epc=)。
     */
    public List<String> findScanRemarksForOutbound(int orderId) {
        return jdbc.queryForList(
                "SELECT remark FROM inventory_log WHERE type = 'SCAN' AND order_id = ? ORDER BY id ASC",
                String.class, orderId);
    }

    /**
     * 统计本出库单某 SKU 的"已扫码确认"件数(SCAN 类型日志条数)。
     * 注意:每扫一次 SCAN 日志=1 件,这里直接 count。
     */
    public int countScannedForOutbound(int orderId, int productId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_log " +
                "WHERE type = 'SCAN' AND order_id = ? AND product_id = ?",
                Integer.class, orderId, productId);
        return n == null ? 0 : n;
    }

    /**
     * 统计本出库单"已扫码确认"的总件数(不区分 SKU)。
     * 给 commitDraft 服务端校验用:scanned >= locked 才能 commit。
     */
    public int countScannedForOutboundAll(int orderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_log " +
                "WHERE type = 'SCAN' AND order_id = ?",
                Integer.class, orderId);
        return n == null ? 0 : n;
    }

    /**
     * 撤销本出库单的全部"扫码确认"(删 SCAN 类型日志)。
     * 用于错扫/重扫恢复:清了这些日志后同一 EPC 可以重新扫(否则会被
     * scanEpc 的"EPC 已扫过(本单)"去重挡住)。
     * 只删 SCAN 日志,不动 IN/OUT 真实流水,也不改 inventory_item 状态。
     * @return 删除条数
     */
    public int deleteScansForOutbound(int orderId) {
        return jdbc.update(
                "DELETE FROM inventory_log WHERE type = 'SCAN' AND order_id = ?",
                orderId);
    }

    /** 查询最近操作记录(排除 SCAN 扫码确认,按时间倒序) */
    public List<InventoryLog> findRecent(int limit) {
        return jdbc.query(
                "SELECT * FROM inventory_log WHERE type != 'SCAN' ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW, Math.max(1, Math.min(limit, 50)));
    }
}