package com.wms.service;

import com.wms.dao.InventoryLogDao;
import com.wms.dao.ProductDao;
import com.wms.model.DashboardStats;
import com.wms.model.InventoryLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final ProductDao productDao;
    private final InventoryLogDao logDao;
    private final JdbcTemplate jdbc;

    @Autowired
    public DashboardService(ProductDao productDao, InventoryLogDao logDao, JdbcTemplate jdbc) {
        this.productDao = productDao;
        this.logDao = logDao;
        this.jdbc = jdbc;
    }

    public DashboardStats stats() {
        DashboardStats s = new DashboardStats();
        s.setProductCount(productDao.count());
        s.setTotalQuantity(productDao.sumQuantity());
        s.setTotalValue(productDao.sumValue());
        s.setLowStockCount(productDao.countLowStock());
        s.setExpiringSoonCount(countExpiring("EXPIRING"));
        s.setExpiredCount(countExpiring("EXPIRED"));
        return s;
    }

    /**
     * 效期预警统计(在库批次):EXPIRING=临期(未过期,距到期<=商品预警天数,默认30) / EXPIRED=已过期。
     * 无效期数据的批次(production_date/shelf_life 缺失)不参与。
     */
    private long countExpiring(String kind) {
        String cond = "EXPIRED".equals(kind)
                ? "i.expiry_date < CURDATE()"
                : "i.expiry_date >= CURDATE() AND i.expiry_date <= CURDATE() + INTERVAL COALESCE(p.expiry_warn_days, 30) DAY";
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inventory_item i LEFT JOIN product p ON p.id = i.product_id " +
                    "WHERE i.status = 'IN_STOCK' AND i.expiry_date IS NOT NULL AND " + cond,
                    Long.class);
            return c == null ? 0L : c;
        } catch (Exception e) {
            return 0L;   // 新列尚未建(迁移未跑)时不阻断仪表盘
        }
    }

    /**
     * 临期/过期在库批次明细(仪表盘卡片列表)。
     * @param kind EXPIRING / EXPIRED
     * @param limit 最多返回条数(1-100)
     */
    public List<Map<String, Object>> expiringItems(String kind, int limit) {
        boolean expired = "EXPIRED".equalsIgnoreCase(kind);
        String cond = expired
                ? "i.expiry_date < CURDATE()"
                : "i.expiry_date >= CURDATE() AND i.expiry_date <= CURDATE() + INTERVAL COALESCE(p.expiry_warn_days, 30) DAY";
        int n = Math.max(1, Math.min(limit, 100));
        String sql = "SELECT i.id, i.code, i.sku, i.product_name, i.location_code, i.remaining, " +
                "i.production_date, i.expiry_date, p.expiry_warn_days, " +
                "DATEDIFF(i.expiry_date, CURDATE()) AS days_left " +
                "FROM inventory_item i LEFT JOIN product p ON p.id = i.product_id " +
                "WHERE i.status = 'IN_STOCK' AND i.expiry_date IS NOT NULL AND " + cond + " " +
                "ORDER BY i.expiry_date ASC LIMIT " + n;
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("code", rs.getString("code"));
                row.put("sku", rs.getString("sku"));
                row.put("productName", rs.getString("product_name"));
                row.put("locationCode", rs.getString("location_code"));
                row.put("remaining", rs.getInt("remaining"));
                java.sql.Date pd = rs.getDate("production_date");
                row.put("productionDate", pd == null ? null : pd.toString());
                java.sql.Date ed = rs.getDate("expiry_date");
                row.put("expiryDate", ed == null ? null : ed.toString());
                row.put("daysLeft", rs.getInt("days_left"));
                out.add(row);
            });
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * 库存趋势(过去 N 天,默认 30 天):每天新生成的库存单元累计数量。
     * 即「截至当日,在系统中已建档的库存单元总数」随时间变化的曲线。
     * 不依赖每日快照(系统无快照),用 inventory_item.created_at 聚合即可。
     *
     * 返回 [{date:'2026-08-09', cumulative: 12}, ...] 按日期升序,缺日补 0。
     */
    public List<Map<String, Object>> inventoryTrend(int days) {
        int n = Math.max(7, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(n - 1);
        // 1) 每天新增数(date, count)
        String sql = "SELECT DATE(created_at) AS d, COUNT(*) AS cnt " +
                     "FROM inventory_item " +
                     "WHERE created_at >= ? " +
                     "GROUP BY DATE(created_at) ORDER BY d ASC";
        Map<LocalDate, Long> daily = new LinkedHashMap<>();
        try {
            jdbc.query(sql, rs -> {
                daily.put(rs.getObject("d", LocalDate.class), rs.getLong("cnt"));
            }, start.atStartOfDay());
        } catch (Exception ignored) {}
        // 2) 起始日之前的累计
        long base = 0L;
        try {
            Long b = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inventory_item WHERE created_at < ?",
                    Long.class, start.atStartOfDay());
            if (b != null) base = b;
        } catch (Exception ignored) {}
        // 3) 输出每天累计
        List<Map<String, Object>> out = new ArrayList<>();
        long cum = base;
        for (int i = 0; i < n; i++) {
            LocalDate d = start.plusDays(i);
            long add = daily.getOrDefault(d, 0L);
            cum += add;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.format(DateTimeFormatter.ISO_LOCAL_DATE));
            row.put("cumulative", cum);
            out.add(row);
        }
        return out;
    }

    /**
     * 出库趋势(过去 N 天,默认 30 天):每天出库总数量(消耗的库存单元数)。
     * 数据源 outbound_order_item.quantity × 1 (一物一码,每条出库行 = 1 件)。
     */
    public List<Map<String, Object>> outboundTrend(int days) {
        int n = Math.max(7, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(n - 1);
        String sql = "SELECT DATE(oi.created_at) AS d, SUM(oi.quantity) AS qty " +
                     "FROM outbound_order_item oi " +
                     "WHERE oi.created_at >= ? " +
                     "GROUP BY DATE(oi.created_at) ORDER BY d ASC";
        Map<LocalDate, Long> daily = new LinkedHashMap<>();
        try {
            jdbc.query(sql, rs -> {
                daily.put(rs.getObject("d", LocalDate.class), rs.getLong("qty"));
            }, start.atStartOfDay());
        } catch (Exception ignored) {}
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate d = start.plusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.format(DateTimeFormatter.ISO_LOCAL_DATE));
            row.put("quantity", daily.getOrDefault(d, 0L));
            out.add(row);
        }
        return out;
    }

    /** 最近操作记录(排除扫码确认,默认10条) */
    public List<InventoryLog> recentOps(int limit) {
        return logDao.findRecent(Math.max(1, Math.min(limit, 50)));
    }
}