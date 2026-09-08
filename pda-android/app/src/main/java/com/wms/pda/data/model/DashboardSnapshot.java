package com.wms.pda.data.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 仪表盘快照:对接 /api/dashboard/stats,4 个核心字段 + 最近操作列表。
 */
public class DashboardSnapshot {
    public int productCount;       // 商品种类数
    public int totalQuantity;      // 库存总件数
    public BigDecimal totalValue;  // 库存总值
    public int lowStockSkuCount;   // 低库存预警 SKU 数
    public List<OpLog> recentOps;  // 最近操作(后端暂无,空列表)

    public DashboardSnapshot() {}

    public DashboardSnapshot(int productCount, int totalQuantity, BigDecimal totalValue,
                             int lowStockSkuCount, List<OpLog> recentOps) {
        this.productCount = productCount;
        this.totalQuantity = totalQuantity;
        this.totalValue = totalValue;
        this.lowStockSkuCount = lowStockSkuCount;
        this.recentOps = recentOps;
    }
}
