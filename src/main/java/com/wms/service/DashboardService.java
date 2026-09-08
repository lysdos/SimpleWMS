package com.wms.service;

import com.wms.dao.ProductDao;
import com.wms.model.DashboardStats;
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
    private final JdbcTemplate jdbc;

    @Autowired
    public DashboardService(ProductDao productDao, JdbcTemplate jdbc) {
        this.productDao = productDao;
        this.jdbc = jdbc;
    }

    public DashboardStats stats() {
        DashboardStats s = new DashboardStats();
        s.setProductCount(productDao.count());
        s.setTotalQuantity(productDao.sumQuantity());
        s.setTotalValue(productDao.sumValue());
        s.setLowStockCount(productDao.countLowStock());
        return s;
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
}