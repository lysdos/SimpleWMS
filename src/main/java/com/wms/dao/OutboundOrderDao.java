package com.wms.dao;

import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class OutboundOrderDao {
    private final JdbcTemplate jdbc;
    public OutboundOrderDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final RowMapper<OutboundOrder> ORDER_ROW = (rs,n)->{ OutboundOrder o=new OutboundOrder(); o.setId(rs.getInt("id"));o.setOrderNo(rs.getString("order_no"));o.setCustomer(rs.getString("customer"));o.setExternalNo(rs.getString("external_no"));o.setRemark(rs.getString("remark"));o.setCreatedBy(rs.getString("created_by"));o.setTotalQuantity(rs.getInt("total_quantity"));o.setItemCount(rs.getInt("item_count"));java.sql.Timestamp t=rs.getTimestamp("created_at");o.setCreatedAt(t==null?null:t.toLocalDateTime());try{String s=rs.getString("status");o.setStatus(rs.wasNull()?OutboundOrder.STATUS_DRAFT:s);}catch(java.sql.SQLException e){o.setStatus(OutboundOrder.STATUS_DRAFT);}
        try{java.sql.Timestamp d=rs.getTimestamp("uhf_dispatched_at");o.setUhfDispatchedAt(d==null?null:d.toLocalDateTime());}catch(java.sql.SQLException e){o.setUhfDispatchedAt(null);}
        try{o.setUhfDispatchedBy(rs.getString("uhf_dispatched_by"));}catch(java.sql.SQLException e){o.setUhfDispatchedBy(null);}
        try{o.setUhfDeviceCode(rs.getString("uhf_device_code"));}catch(java.sql.SQLException e){o.setUhfDeviceCode(null);}
        try{java.sql.Timestamp c=rs.getTimestamp("uhf_claimed_at");o.setUhfClaimedAt(c==null?null:c.toLocalDateTime());}catch(java.sql.SQLException e){o.setUhfClaimedAt(null);}
        return o;};
    private static final RowMapper<OutboundOrderItem> ITEM_ROW=(rs,n)->{OutboundOrderItem i=new OutboundOrderItem();i.setId(rs.getInt("id"));i.setOrderId(rs.getInt("order_id"));i.setProductId(rs.getInt("product_id"));i.setSku(rs.getString("sku"));i.setProductName(rs.getString("product_name"));i.setQuantity(rs.getInt("quantity"));i.setUnitPrice(rs.getBigDecimal("unit_price"));i.setBeforeQty(rs.getInt("before_qty"));i.setAfterQty(rs.getInt("after_qty"));i.setLocation(rs.getString("location"));i.setRowRemark(rs.getString("row_remark"));java.sql.Timestamp t=rs.getTimestamp("created_at");i.setCreatedAt(t==null?null:t.toLocalDateTime());return i;};
    public int insertOrder(OutboundOrder o){String q="INSERT INTO outbound_order (order_no, customer, external_no, remark, created_by, total_quantity, item_count, status) VALUES (?,?,?,?,?,?,?,?)";KeyHolder h=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement(q,new String[]{"id"});p.setString(1,o.getOrderNo());p.setString(2,o.getCustomer());p.setString(3,o.getExternalNo());p.setString(4,o.getRemark());p.setString(5,o.getCreatedBy());p.setInt(6,o.getTotalQuantity()==null?0:o.getTotalQuantity());p.setInt(7,o.getItemCount()==null?0:o.getItemCount());p.setString(8,o.getStatus()==null?OutboundOrder.STATUS_DRAFT:o.getStatus());return p;},h);Number k=h.getKey();if(k==null){Map<String,Object>m=h.getKeys();if(m!=null&&m.get("id")!=null)k=(Number)m.get("id");}return k==null?0:k.intValue();}
    public void insertItem(OutboundOrderItem i){jdbc.update("INSERT INTO outbound_order_item (order_id, product_id, sku, product_name, quantity, unit_price, before_qty, after_qty, location, row_remark) VALUES (?,?,?,?,?,?,?,?,?,?)",i.getOrderId(),i.getProductId(),i.getSku(),i.getProductName(),i.getQuantity(),i.getUnitPrice(),i.getBeforeQty(),i.getAfterQty(),i.getLocation(),i.getRowRemark());}
    public int updateItemQuantity(int itemId,int quantity){return jdbc.update("UPDATE outbound_order_item SET quantity=? WHERE id=?",quantity,itemId);}
    /** 仅当订单仍为 DRAFT 时才推进状态(影响行数 0 = 已被并发/手工修改过,commit/cancel 应放弃) */
    public int updateStatusFromDraft(int id, String newStatus) {
        return jdbc.update(
            "UPDATE outbound_order SET status=? WHERE id=? AND status=?",
            newStatus, id, OutboundOrder.STATUS_DRAFT);
    }
    /** 更新 DRAFT 单头(客户/外部单号/备注),仅 DRAFT 生效(防并发提交/取消后改写),返回影响行数 */
    public int updateHeader(int id, String customer, String externalNo, String remark) {
        return jdbc.update(
            "UPDATE outbound_order SET customer=?, external_no=?, remark=? WHERE id=? AND status=?",
            customer, externalNo, remark, id, OutboundOrder.STATUS_DRAFT);
    }
    public void refreshAggregates(int id){jdbc.update("UPDATE outbound_order o SET total_quantity=COALESCE((SELECT SUM(quantity) FROM outbound_order_item WHERE order_id=o.id),0),item_count=COALESCE((SELECT COUNT(*) FROM outbound_order_item WHERE order_id=o.id),0) WHERE o.id=?",id);}
    public int deleteItemsByOrderId(int id){return jdbc.update("DELETE FROM outbound_order_item WHERE order_id=?",id);}
    public OutboundOrder findOrderById(int id){List<OutboundOrder>l=jdbc.query("SELECT * FROM outbound_order WHERE id=?",ORDER_ROW,id);return l.isEmpty()?null:l.get(0);}
    public List<OutboundOrderItem> findItemsByOrderId(int id){return jdbc.query("SELECT * FROM outbound_order_item WHERE order_id=? ORDER BY id ASC",ITEM_ROW,id);}
    private void filters(StringBuilder s,List<Object>a,String b,String e,String k,String op,String st){if(b!=null&&!b.isBlank()&&!"all".equalsIgnoreCase(b)){s.append(" AND created_at>=?");a.add(b+" 00:00:00");}if(e!=null&&!e.isBlank()&&!"all".equalsIgnoreCase(e)){s.append(" AND created_at<=?");a.add(e+" 23:59:59");}if(k!=null&&!k.isBlank()){s.append(" AND (order_no LIKE ? OR customer LIKE ? OR external_no LIKE ? OR remark LIKE ?)");String x="%"+k.trim()+"%";a.add(x);a.add(x);a.add(x);a.add(x);}if(op!=null&&!op.isBlank()){s.append(" AND created_by=?");a.add(op);}if(st!=null&&!st.isBlank()&&!"ALL".equalsIgnoreCase(st)){s.append(" AND status=?");a.add(st);}}
    public List<OutboundOrder> findOrders(String b,String e,String k,String op,String st,int page,int size){StringBuilder s=new StringBuilder("SELECT * FROM outbound_order WHERE 1=1");List<Object>a=new ArrayList<>();filters(s,a,b,e,k,op,st);s.append(" ORDER BY id DESC");int lim=size<=0||size>200?20:size;int off=page<=0?0:(page-1)*lim;s.append(" LIMIT ").append(lim).append(" OFFSET ").append(off);return jdbc.query(s.toString(),ORDER_ROW,a.toArray());}
    public long countOrders(String b,String e,String k,String op,String st){StringBuilder s=new StringBuilder("SELECT COUNT(*) FROM outbound_order WHERE 1=1");List<Object>a=new ArrayList<>();filters(s,a,b,e,k,op,st);Long c=jdbc.queryForObject(s.toString(),Long.class,a.toArray());return c==null?0:c;}

    // ===== UHF 派单 =====
    /**
     * 把指定出库单标记为"已下发到 UHF"
     * 条件: 状态必须是 DRAFT,且尚未下发
     * @return 影响行数(0=不满足条件,1=下发成功)
     */
    public int dispatchToUhf(int orderId, String deviceCode, String createdBy) {
        return jdbc.update(
            "UPDATE outbound_order " +
            "SET uhf_dispatched_at = CURRENT_TIMESTAMP, " +
            "    uhf_dispatched_by = ?, " +
            "    uhf_device_code   = ?, " +
            "    uhf_claimed_at    = NULL " +
            "WHERE id = ? AND status = 'DRAFT' AND uhf_dispatched_at IS NULL",
            createdBy, deviceCode, orderId);
    }

    /**
     * UHF 端认领: 把指定出库单标记为"已认领",同时只允许没被认领过或同设备重认领
     * @return 影响行数(0=已被别人认领或不满足条件,1=认领成功)
     */
    public int claimDispatch(int orderId, String deviceCode) {
        return jdbc.update(
            "UPDATE outbound_order " +
            "SET uhf_claimed_at = CURRENT_TIMESTAMP " +
            "WHERE id = ? AND status = 'DRAFT' " +
            "  AND uhf_dispatched_at IS NOT NULL " +
            "  AND (uhf_claimed_at IS NULL OR uhf_device_code = ?)",
            orderId, deviceCode);
    }

    /**
     * UHF 端拉取"待处理的派单"列表(已下发 + 状态 DRAFT + 还没被任何设备认领)
     * 可选按 deviceCode 过滤:传 null 拉全部,传具体值只拉发给该设备的单
     */
    public List<OutboundOrder> findDispatchedOrders(String deviceCode, int limit) {
        int lim = (limit <= 0 || limit > 200) ? 50 : limit;
        String sql;
        Object[] args;
        if (deviceCode == null || deviceCode.isBlank()) {
            sql = "SELECT * FROM outbound_order " +
                  "WHERE status = 'DRAFT' " +
                  "  AND uhf_dispatched_at IS NOT NULL " +
                  "  AND uhf_claimed_at IS NULL " +
                  "ORDER BY uhf_dispatched_at DESC " +
                  "LIMIT " + lim;
            args = new Object[0];
        } else {
            sql = "SELECT * FROM outbound_order " +
                  "WHERE status = 'DRAFT' " +
                  "  AND uhf_dispatched_at IS NOT NULL " +
                  "  AND uhf_claimed_at IS NULL " +
                  "  AND uhf_device_code = ? " +
                  "ORDER BY uhf_dispatched_at DESC " +
                  "LIMIT " + lim;
            args = new Object[]{ deviceCode };
        }
        return jdbc.query(sql, ORDER_ROW, args);
    }
}
